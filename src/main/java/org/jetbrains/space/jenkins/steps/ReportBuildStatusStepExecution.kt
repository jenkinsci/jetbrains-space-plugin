package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Run
import jenkins.model.Jenkins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.trigger.BuildIdPrefix
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.ProjectIdentifier
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [ReportBuildStatusStep], which is responsible for reporting the build status
 * to JetBrains Space.
 */
class ReportBuildStatusStepExecution(
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
        fun start(step: ReportBuildStatusStep, context: StepContext, spacePluginConfiguration: SpacePluginConfiguration) : StepExecution {
            val build = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val triggerCause = build.getAction(CauseAction::class.java)?.findCause(SpaceWebhookTriggerCause::class.java)
            val checkoutAction = build.getAction(SpaceGitScmCheckoutAction::class.java)

            val (connection, projectKey, repositoryName) = when {
                step.spaceConnection != null -> {
                    val connection = spacePluginConfiguration.getConnectionById(step.spaceConnection)
                        ?: return FailureStepExecution(
                            "No Space connection found by specified id",
                            context
                        )
                    val projectKey = step.projectKey
                        ?: return FailureStepExecution(
                            "Project key must be specified together with the Space connection",
                            context
                        )
                    val repository = step.repository
                        ?: return FailureStepExecution(
                            "Repository name must be specified together with the Space connection",
                            context
                        )
                    Triple(connection, projectKey, repository)
                }

                triggerCause != null -> {
                    val connection = spacePluginConfiguration.getConnectionById(triggerCause.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for the Space webhook call that triggered the build",
                            context
                        )
                    Triple(connection, triggerCause.projectKey, triggerCause.repositoryName)
                }

                checkoutAction != null -> {
                    val connection = spacePluginConfiguration.getConnectionById(checkoutAction.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for checkout action",
                            context
                        )
                    Triple(connection, checkoutAction.projectKey, checkoutAction.repositoryName)
                }

                else ->
                    Triple(null, null, null)
            }

            if (connection == null) {
                return FailureStepExecution(
                    "Space connection cannot be inferred from the build trigger or git checkout settings and is not specified explicitly in the workflow step",
                    context
                )
            }

            if (projectKey == null) {
                return FailureStepExecution(
                    "Space project cannot be inferred from the build trigger or git checkout settings and is not specified explicitly in the workflow step",
                    context
                )
            }

            if (repositoryName == null) {
                return FailureStepExecution(
                    "Space repositoryName cannot be inferred from the build trigger or git checkout settings and is not specified explicitly in the workflow step",
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

            val action = PostBuildStatusAction(connection.id, projectKey, repositoryName, branch, revision)
            build.addAction(action)

            @Suppress("DEPRECATION")
            return ReportBuildStatusStepExecution(
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
        val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
            ?: error("Space plugin configuration cannot be resolved")
        coroutineScope.launch {
            try {
                val spaceConnection = spacePluginConfiguration.getConnectionById(action.spaceConnectionId)
                    ?: error("No Space connection found by specified id")

                spaceConnection.getApiClient().use { spaceApiClient ->
                    spaceApiClient.projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
                        project = ProjectIdentifier.Key(action.projectKey),
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
