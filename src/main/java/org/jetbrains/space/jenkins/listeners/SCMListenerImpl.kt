package org.jetbrains.space.jenkins.listeners

import hudson.Extension
import hudson.FilePath
import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.SCMListener
import hudson.plugins.git.GitSCM
import hudson.scm.SCM
import hudson.scm.SCMRevisionState
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.SpaceRepository
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.jetbrains.space.jenkins.scm.SpaceSCM
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.io.File
import java.util.*
import java.util.logging.Logger
import javax.inject.Inject

@Extension
class SCMListenerImpl : SCMListener() {

    @Inject
    private lateinit var spacePluginConfiguration: SpacePluginConfiguration

    private fun getUnderlyingGitSCM(scm: SCM?): GitSCM? {
        if (scm is GitSCM) {
            // Already a git SCM
            return scm
        }
        if (scm is SpaceSCM) {
            return scm.gitSCM
        }
        return null
    }

    override fun onCheckout(
        build: Run<*, *>,
        scm: SCM,
        workspace: FilePath,
        listener: TaskListener,
        changelogFile: File?,
        pollingBaseline: SCMRevisionState?
    ) {
        LOGGER.info("SpaceSCMListener.onCheckout called for " + scm.type)

        val underlyingGitScm = getUnderlyingGitSCM(scm)
        if (underlyingGitScm == null) {
            LOGGER.info("SpaceSCMListener.onCheckout - no underlying Git SCM instance")
            return
        }
        val env: Map<String, String> = HashMap()
        underlyingGitScm.buildEnvironment(build, env)

        val spaceRepository = if (scm is SpaceSCM) {
            spacePluginConfiguration
                .getConnectionByIdOrName(scm.spaceConnectionId, scm.spaceConnection)
                ?.let { SpaceRepository(it, scm.projectKey, scm.repositoryName) }
        } else {
//            spaceRepository = spacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(env.get(GitSCM.GIT_URL));
            null
        }

        if (spaceRepository == null) {
            LOGGER.info("SpaceSCMListener.onCheckout - no Space SCM found")
            return
        }

        val spaceConnection = spaceRepository.spaceConnection
        val revisionAction = SpaceGitScmCheckoutAction(
            spaceConnectionId = spaceConnection.id,
            projectKey = spaceRepository.projectKey,
            repositoryName = spaceRepository.repositoryName,
            branch = env[GitSCM.GIT_BRANCH]!!,
            revision = env[GitSCM.GIT_COMMIT]!!,
            postBuildStatusToSpace = (scm as? SpaceSCM)?.postBuildStatusToSpace ?: false
        )
        build.addAction(revisionAction)

        if (revisionAction.postBuildStatusToSpace) {
            try {
                runBlocking {
                    spaceConnection.getApiClient().postBuildStatus(revisionAction, build)
                }
            } catch (ex: Throwable) {
                val message = "Failed to post build status to JetBrains Space, details: $ex"
                LOGGER.warning(message)
                listener.logger.println(message)
            }
        }
    }
}

suspend fun SpaceClient.postBuildStatus(action: SpaceGitScmCheckoutAction, build: Run<*, *>, status: CommitExecutionStatus? = null) {
    projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
        project = ProjectIdentifier.Key(action.projectKey),
        repository = action.repositoryName,
        revision = action.revision,
        branch = action.branch,
        changes = emptyList(),
        executionStatus = status ?: getStatusFromBuild(build),
        url = build.absoluteUrl,
        externalServiceName = "Jenkins",
        taskName = build.parent.fullName,
        taskId = build.parent.fullName,
        taskBuildId = build.getNumber().toString(),
        timestamp = build.startTimeInMillis,
        description = build.description
    )
}

private fun getStatusFromBuild(build: Run<*, *>): CommitExecutionStatus {
    val status = if (build.isBuilding) {
        CommitExecutionStatus.RUNNING
    } else if (successfulResults.contains(build.result)) {
        CommitExecutionStatus.SUCCEEDED
    } else if (cancelledResults.contains(build.result)) {
        CommitExecutionStatus.TERMINATED
    } else {
        CommitExecutionStatus.FAILED
    }
    return status
}

private val successfulResults = listOf(Result.SUCCESS, Result.UNSTABLE)
private val cancelledResults = listOf(Result.ABORTED, Result.NOT_BUILT)

private val LOGGER = Logger.getLogger(SCMListenerImpl::class.simpleName)
