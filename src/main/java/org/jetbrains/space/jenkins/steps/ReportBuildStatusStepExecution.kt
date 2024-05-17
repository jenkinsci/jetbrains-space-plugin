package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Run
import jenkins.branch.MultiBranchProject
import jenkins.model.Jenkins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getProjectConnection
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.trigger.BuildIdPrefix
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.ProjectIdentifier
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [ReportBuildStatusStep], which is responsible for reporting the build status
 * to JetBrains SpaceCode.
 */
class ReportBuildStatusStepExecution(
    private val jenkinsItemFullName: String,
    private val spaceConnectionId: String,
    private val spaceProjectKey: String?,
    private val action: PostBuildStatusAction,
    private val buildStatus: CommitExecutionStatus,
    private val buildUrl: String,
    private val taskName: String,
    private val taskBuildId: String,
    private val taskBuildStartTimeInMillis: Long,
    private val taskBuildDescription: String?,
    context: StepContext
) : StepExecution(context) {

    companion object {
        private final val serialVersionUID = 1L

        /**
         * Obtain all the necessary parameters from the build trigger or git checkout settings if necessary and start the step execution.
         *
         * An instance of [PostBuildStatusAction] is also added to the build metadata
         * to notify the build completion listener that build status reporting has already happened
         * and it shouldn't post the final status on build completion, overwriting the one provided by this step.
         */
        fun start(step: ReportBuildStatusStep, context: StepContext) : StepExecution {
            val build = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val multiProjectScmSource = build.getParent().getMultiBranchSpaceScmSource()
            val spaceConnectionId = multiProjectScmSource?.spaceConnectionId
                ?: build.getParent().getSpaceConnectionId()
                ?: return FailureStepExecution("SpaceCode connection not found", context)

            val triggerCause = build.getAction(CauseAction::class.java)?.findCause(SpaceWebhookTriggerCause::class.java)
            val checkoutAction = build.getAction(SpaceGitScmCheckoutAction::class.java)

            val repositoryName = when {
                step.repository != null ->
                    step.repository

                triggerCause != null ->
                    triggerCause.repositoryName

                checkoutAction != null ->
                    checkoutAction.repositoryName

                else ->
                    null
            }

            if (repositoryName == null) {
                return FailureStepExecution(
                    "SpaceCode repositoryName cannot be inferred from the build trigger or git checkout settings and is not specified explicitly in the workflow step",
                    context
                )
            }

            val branch = (step.branch ?: checkoutAction?.branch)
                ?: return FailureStepExecution(
                    "Git branch cannot be inferred from the build trigger or checkout setting and is not specified explicitly in the workflow step",
                    context
                )
            val revision = (step.revision ?: checkoutAction?.revision)
                ?: return FailureStepExecution(
                    "Git commit cannot be inferred from the build trigger or checkout setting and is not specified explicitly in the workflow step",
                    context
                )

            val action = PostBuildStatusAction(repositoryName, branch, revision)
            build.addAction(action)

            val jenkinsItem = (build.getParent().getParent() as? MultiBranchProject<*, *>) ?: build.getParent()

            @Suppress("DEPRECATION")
            return ReportBuildStatusStepExecution(
                jenkinsItemFullName = jenkinsItem.fullName,
                spaceConnectionId = spaceConnectionId,
                spaceProjectKey = multiProjectScmSource?.projectKey,
                action = action,
                buildStatus = CommitExecutionStatus.valueOf(step.buildStatus),
                buildUrl = if (Jenkins.get().rootUrl != null) build.absoluteUrl else build.url,
                taskName = build.parent.fullName,
                taskBuildId = build.getNumber().toString(),
                taskBuildStartTimeInMillis = build.startTimeInMillis,
                taskBuildDescription = build.description,
                context = context
            )
        }
    }

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        coroutineScope.launch {
            try {
                val (spaceConnection, spaceUrl) = getProjectConnection(jenkinsItemFullName, spaceConnectionId, spaceProjectKey)

                spaceConnection.getApiClient(spaceUrl).use { spaceApiClient ->
                    spaceApiClient.projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
                        project = ProjectIdentifier.Key(spaceConnection.projectKey),
                        repository = action.repositoryName,
                        revision = action.revision,
                        branch = action.branch,
                        changes = emptyList(),
                        executionStatus = buildStatus,
                        url = buildUrl,
                        externalServiceName = "Jenkins",
                        taskName = taskName,
                        taskId = taskName,
                        taskBuildId = BuildIdPrefix.BUILD + taskBuildId,
                        timestamp = taskBuildStartTimeInMillis,
                        description = taskBuildDescription
                    )
                }
                context.onSuccess(null)
            } catch (ex: Throwable) {
                context.onFailure(ex)
            }
        }
        return false
    }
}
