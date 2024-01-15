package org.jetbrains.space.jenkins.listeners

import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import hudson.plugins.git.GitSCM
import hudson.scm.SCM
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.*
import org.jetbrains.space.jenkins.scm.SpaceSCM
import org.jetbrains.space.jenkins.steps.PostBuildStatusAction
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.util.logging.Level
import java.util.logging.Logger

fun onScmCheckout(build: Run<*, *>, scm: SCM, listener: TaskListener, spacePluginConfiguration: SpacePluginConfiguration) {
    LOGGER.info("SpaceSCMListener.onCheckout called for ${scm.type}")

    val underlyingGitScm = getUnderlyingGitSCM(scm)
    if (underlyingGitScm == null) {
        LOGGER.info("SpaceSCMListener.onCheckout - no underlying Git SCM instance")
        return
    }
    val gitScmEnv = mutableMapOf<String, String>()
    underlyingGitScm.buildEnvironment(build, gitScmEnv)

    val spaceRepository = when {
        scm is SpaceSCM ->
            spacePluginConfiguration
                .getConnectionByIdOrName(scm.spaceConnectionId, scm.spaceConnection)
                ?.let { SpaceRepository(it, scm.projectKey, scm.repositoryName) }

        gitScmEnv[GitSCM.GIT_URL] != null && gitScmEnv[GitSCM.GIT_COMMIT] != null ->
            spacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(gitScmEnv[GitSCM.GIT_URL]!!)

        else ->
            null
    }
    if (spaceRepository == null) {
        LOGGER.info("SpaceSCMListener.onCheckout - no Space git repository found")
        return
    }

    val spaceConnection = spaceRepository.spaceConnection
    val revisionAction = SpaceGitScmCheckoutAction(
        spaceConnectionId = spaceConnection.id,
        spaceUrl = spaceConnection.baseUrl,
        projectKey = spaceRepository.projectKey,
        repositoryName = spaceRepository.repositoryName,
        branch = gitScmEnv[GitSCM.GIT_BRANCH].orEmpty(),
        revision = gitScmEnv[GitSCM.GIT_COMMIT]!!,
        postBuildStatusToSpace = (scm as? SpaceSCM)?.postBuildStatusToSpace ?: false,
        gitScmEnv
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

fun onBuildCompleted(build: Run<*, *>, listener: TaskListener, spacePluginConfiguration: SpacePluginConfiguration) {
    val postBuildStatusActions = build.getActions(PostBuildStatusAction::class.java)
    val checkoutActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
        .filter(SpaceGitScmCheckoutAction::postBuildStatusToSpace)
        .filter { action: SpaceGitScmCheckoutAction ->
            postBuildStatusActions.stream()
                .noneMatch { a: PostBuildStatusAction -> a.spaceConnectionId == action.spaceConnectionId && a.projectKey == action.projectKey && a.repositoryName == action.repositoryName }
        }

    for (action in checkoutActions) {
        val spaceConnection = spacePluginConfiguration.getConnectionById(action.spaceConnectionId)
        if (spaceConnection == null) {
            LOGGER.info("RunListenerImpl.onCompleted - could not find Space connection")
            return
        }
        try {
            runBlocking {
                spaceConnection.getApiClient().postBuildStatus(action, build)
            }
        } catch (ex: Throwable) {
            val message = "Failed to post build status to JetBrains Space, details: $ex"
            LOGGER.warning(message)
            listener.logger.println(message)
        }
    }

    // update webhooks registered in Space according to the actual SCMs used in the last completed build
    try {
        (build.parent as? TriggeredItem)?.triggers?.values
            ?.filterIsInstance<SpaceWebhookTrigger>()
            ?.forEach { it.ensureSpaceWebhooks() }
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, "Failure while ensuring webhook settings in Space", ex)
    }
}


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

private suspend fun SpaceClient.postBuildStatus(action: SpaceGitScmCheckoutAction, build: Run<*, *>, status: CommitExecutionStatus? = null) {
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

private val LOGGER = Logger.getLogger("SCMListener")
