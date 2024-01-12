package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.ProjectIdentifier
import kotlin.coroutines.EmptyCoroutineContext

class ReportBuildStatusStepExecution(
    private val actions: List<PostBuildStatusAction>,
    private val buildStatus: CommitExecutionStatus,
    private val buildUrl: String,
    private val taskName: String,
    private val taskBuildId: String,
    private val taskBuildStartTimeInMillis: Long,
    private val taskBuildDescription: String?,
    context: StepContext
) : StepExecution(context) {

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
            ?: error("Space plugin configuration cannot be resolved")
        coroutineScope.launch {
            try {
                val jobs = supervisorScope {
                    actions.map { action ->
                        val spaceConnection = spacePluginConfiguration.getConnectionById(action.spaceConnectionId)
                            ?: error("No Space connection found by specified id")

                        launch {
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
                                    taskBuildId = taskBuildId,
                                    timestamp = taskBuildStartTimeInMillis,
                                    description = taskBuildDescription
                                )
                            }
                        }
                    }
                }
                jobs.joinAll()
                context.onSuccess(Unit)
            } catch (ex: Throwable) {
                context.onFailure(ex)
            }
        }
        return false
    }
}

class FailureStepExecution(private val message: String, context: StepContext) : StepExecution(context) {
    override fun start(): Boolean {
        val ex: Throwable = IllegalArgumentException(message)
        ex.stackTrace = arrayOfNulls(0)
        context.onFailure(ex)
        return true
    }
}
