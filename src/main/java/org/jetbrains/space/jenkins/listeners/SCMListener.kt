package org.jetbrains.space.jenkins.listeners

import hudson.model.*
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.RevisionParameterAction
import hudson.scm.SCM
import io.ktor.http.*
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.*
import org.jetbrains.space.jenkins.scm.*
import org.jetbrains.space.jenkins.steps.PostBuildStatusAction
import org.jetbrains.space.jenkins.trigger.BuildIdPrefix
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import space.jetbrains.api.runtime.types.MergeRequestRecord
import space.jetbrains.api.runtime.types.ProjectIdentifier
import space.jetbrains.api.runtime.types.partials.CodeReviewRecordPartial
import java.util.logging.Logger

/**
 * Handles the event of checking out code from a git repository.
 * Posts build status RUNNING to Space and appends an instance of [SpaceGitScmCheckoutAction] to the build metadata.
 * Invoked from [SCMListenerImpl]
 */
fun onScmCheckout(build: Run<*, *>, scm: SCM, listener: TaskListener, spacePluginConfiguration: SpacePluginConfiguration) {
    LOGGER.info("SpaceSCMListener.onCheckout called for ${scm.type} on ${build.displayName}")

    val underlyingGitScm = getUnderlyingGitSCM(scm)
    if (underlyingGitScm == null) {
        LOGGER.info("SpaceSCMListener.onCheckout - no underlying Git SCM instance")
        return
    }
    val gitScmEnv = mutableMapOf<String, String>()
    underlyingGitScm.buildEnvironment(build, gitScmEnv)

    val (space, forcedMergeRequest) = when {
        scm is SpaceSCM -> {
            val (space, _, mergeRequest) = spacePluginConfiguration.getSpaceGitCheckoutParams(scm, build.parent, build)
            space to mergeRequest
        }

        gitScmEnv[GitSCM.GIT_URL] != null && gitScmEnv[GitSCM.GIT_COMMIT] != null -> {
            spacePluginConfiguration.getSpaceGitCheckoutParamsByCloneUrl(gitScmEnv[GitSCM.GIT_URL]!!)?.let { space ->
                space to build.getForcedMergeRequest(space)
            }
        }

        else ->
            null
    } ?: run  {
        LOGGER.info("SpaceSCMListener.onCheckout - no Space git repository found")
        return
    }

    val triggerCause = forcedMergeRequest
        ?.let { TriggerCause.fromMergeRequest(it, space.connection) }
        ?.takeIf { it.sourceBranch == gitScmEnv[GitSCM.GIT_BRANCH] }
        ?: build.getCause(SpaceWebhookTriggerCause::class.java)
            ?.takeIf {
                it.spaceConnectionId == space.connection.id &&
                        it.projectKey == space.projectKey &&
                        it.repositoryName == space.repositoryName &&
                        it.cause.branchForCheckout == gitScmEnv[GitSCM.GIT_BRANCH]
            }
            ?.cause

    val spaceGitCheckoutAction = SpaceGitScmCheckoutAction(
        spaceConnectionId = space.connection.id,
        spaceUrl = space.connection.baseUrl,
        projectKey = space.projectKey,
        repositoryName = space.repositoryName,
        branch = gitScmEnv[GitSCM.GIT_BRANCH].orEmpty(),
        revision = gitScmEnv[GitSCM.GIT_COMMIT]!!,
        postBuildStatusToSpace = triggerCause != null && ((scm as? SpaceSCM)?.postBuildStatusToSpace ?: false),
        gitScmEnv = gitScmEnv,
        cause = triggerCause
    )
    val existingActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
    val duplicate = existingActions.firstOrNull { it.urlName == spaceGitCheckoutAction.urlName }
    if (duplicate == null) {
        build.addAction(spaceGitCheckoutAction)
    } else if (!duplicate.postBuildStatusToSpace && spaceGitCheckoutAction.postBuildStatusToSpace) {
        build.removeAction(duplicate)
        build.addAction(spaceGitCheckoutAction)
    }

    if (existingActions.any()) {
        val allActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
        val hasDistinctSpaceConnections = allActions.distinctBy { it.spaceConnectionId }.size > 1
        build.removeActions(SpaceGitScmCheckoutAction::class.java)
        allActions
            .sortedBy { it.cause == null }
            .forEach { a ->
                build.addAction(
                    a.copy(
                        sourceDisplayName = listOfNotNull(
                            a.spaceConnectionId
                                .takeIf { hasDistinctSpaceConnections },
                            a.projectKey
                                .takeIf { allActions.hasDistinctProjects(a.spaceConnectionId) },
                            a.repositoryName
                                .takeIf { allActions.hasDistinctRepos(a.spaceConnectionId, a.projectKey) },
                            a.branch
                                .takeIf { allActions.hasDistinctBranches(a.spaceConnectionId, a.projectKey, a.repositoryName) }
                        ).takeUnless { it.isEmpty() }?.joinToString(" \\ ")
                    )
                )
            }
    }

    if (triggerCause != null) {
        build.addAction(RevisionParameterAction(triggerCause.commitId))
    }

    if (spaceGitCheckoutAction.postBuildStatusToSpace && (duplicate == null || !duplicate.postBuildStatusToSpace)) {
        try {
            runBlocking {
                space.connection.getApiClient().postBuildStatus(spaceGitCheckoutAction, build)
            }
        } catch (ex: Throwable) {
            val message = "Failed to post build status to JetBrains Space, details: $ex"
            LOGGER.warning(message)
            listener.logger.println(message)
        }
    }
}

private fun List<SpaceGitScmCheckoutAction>.hasDistinctProjects(spaceConnectionId: String) =
    filter { it.spaceConnectionId == spaceConnectionId }.distinctBy { it.projectKey }.size > 1

private fun List<SpaceGitScmCheckoutAction>.hasDistinctRepos(spaceConnectionId: String, projectKey: String) =
    filter { it.spaceConnectionId == spaceConnectionId && it.projectKey == projectKey }.distinctBy { it.repositoryName }.size > 1

private fun List<SpaceGitScmCheckoutAction>.hasDistinctBranches(spaceConnectionId: String, projectKey: String, repositoryName: String) =
    filter { it.spaceConnectionId == spaceConnectionId && it.projectKey == projectKey && it.repositoryName == repositoryName }.distinctBy { it.branch }.size > 1

val mergeRequestFields: CodeReviewRecordPartial.() -> Unit = {
    id()
    number()
    title()
    project {
        key()
    }
    branchPairs {
        repository()
        sourceBranchInfo()
        targetBranchInfo()
    }
}

/**
 * Handles the event of build completion.
 * If a build has been triggered by a change in Space or has checked out source code from a Space git repository,
 * posts the resulting build status to Space (unless automatic posting is disabled).
 * Invoked from [RunListenerImpl]
 */
fun onBuildCompleted(build: Run<*, *>, listener: TaskListener, spacePluginConfiguration: SpacePluginConfiguration) {
    val postBuildStatusActions = build.getActions(PostBuildStatusAction::class.java)
    val checkoutActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
        .filter(SpaceGitScmCheckoutAction::postBuildStatusToSpace)
        .filter { action: SpaceGitScmCheckoutAction ->
            postBuildStatusActions.stream()
                .noneMatch { a: PostBuildStatusAction ->
                    a.spaceConnectionId == action.spaceConnectionId &&
                            a.projectKey == action.projectKey
                            && a.repositoryName == action.repositoryName
                }
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
        } catch (ex: Exception) {
            val message = "Failed to post build status to JetBrains Space, details: $ex"
            LOGGER.warning(message)
            listener.logger.println(message)
        }
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

class SpaceGitCheckoutParams(
    val connection: SpaceConnection,
    @field:SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    val projectKey: String,
    val repositoryName: String
)

/**
 * Retrieves Space connection, project, repository and the list of branch specs for a given job with Space SCM settings.
 * Takes the checkout parameters from the SCM settings itself if specified explicitly there, otherwise falls back to build trigger settings.
 *
 * @throws IllegalArgumentException If the Space connection is not specified in the source checkout settings or build trigger.
 */
fun SpacePluginConfiguration.getSpaceGitCheckoutParams(scm: SpaceSCM, job: Job<*, *>, build: Run<*, *>?): Triple<SpaceGitCheckoutParams, List<BranchSpec>, MergeRequestRecord?> {

    val customSpaceConnection = scm.customSpaceConnection
    val spaceWebhookTrigger = getSpaceWebhookTrigger(job)
    val triggerCause = build?.getCause(SpaceWebhookTriggerCause::class.java)

    return when {
        customSpaceConnection != null -> {
            val params = SpaceGitCheckoutParams(
                connection = getConnectionById(customSpaceConnection.spaceConnection)
                    ?: throw IllegalArgumentException("JetBrains Space connection with id \"${customSpaceConnection.spaceConnection}\" does not exist"),
                projectKey = customSpaceConnection.projectKey,
                repositoryName = customSpaceConnection.repository
            )
            val forcedMergeRequest = build?.getForcedMergeRequest(params)
            val branchesFromTrigger = triggerCause
                ?.takeIf { it.projectKey == customSpaceConnection.projectKey && it.repositoryName == customSpaceConnection.repository }
                ?.let { listOf(BranchSpec(it.cause.branchForCheckout)) }
                .orEmpty()
            val branches = forcedMergeRequest?.branchPairs?.firstOrNull()?.sourceBranchInfo?.head?.let { listOf(BranchSpec(it)) }
                ?: build?.getForcedBranchName()?.let { listOf(BranchSpec(it)) }
                ?: (customSpaceConnection.branches.orEmpty() + branchesFromTrigger).takeUnless { it.isEmpty() }
                ?: listOf(BranchSpec("refs/heads/*"))

            Triple(params, branches, forcedMergeRequest)
        }

        spaceWebhookTrigger != null -> {
            val params = SpaceGitCheckoutParams(
                connection = getConnectionById(spaceWebhookTrigger.spaceConnectionId)
                    ?: throw IllegalArgumentException("Space connection specified in trigger not found"),
                projectKey = spaceWebhookTrigger.projectKey,
                repositoryName = spaceWebhookTrigger.repositoryName
            )
            val forcedMergeRequest = build?.getForcedMergeRequest(params)
            val branches = forcedMergeRequest?.branchPairs?.firstOrNull()?.sourceBranchInfo?.head?.let { listOf(BranchSpec(it)) }
                ?: build?.getForcedBranchName()?.let { listOf(BranchSpec(it)) }
                ?: listOf(BranchSpec(triggerCause?.let { it.cause.branchForCheckout } ?: "refs/*"))

            Triple(params, branches, forcedMergeRequest)
        }

        else -> {
            throw IllegalArgumentException("JetBrains Space connection is not specified neither in the source checkout settings nor in build trigger")
        }
    }
}

/**
 * Tries to match git clone url to the one of the Space connections configured globally.
 * If a Space connection is found, project key and repository name are extracted from the git clone url itself.
 */
private fun SpacePluginConfiguration.getSpaceGitCheckoutParamsByCloneUrl(gitCloneUrl: String): SpaceGitCheckoutParams? {
    val url = try {
        Url(gitCloneUrl)
    } catch (e: URLParserException) {
        LOGGER.warning("Malformed git SCM url - $gitCloneUrl");
        return null;
    }

    if (!url.host.startsWith("git.")) {
        return null;
    }

    val (baseUrlHostname, projectKey, repositoryName) =
        if (url.host.endsWith(".jetbrains.space")) {
            // cloud space, path contains org name, project key and repo name
            if (url.pathSegments.size < 3) {
                return null
            }
            Triple(url.pathSegments[0] + ".jetbrains.space", url.pathSegments[1], url.pathSegments[2])
        } else {
            // path contains project key and repo name
            if (url.pathSegments.size < 2) {
                return null
            }
            Triple(url.host.substring("git.".length), url.pathSegments[0], url.pathSegments[1])
        }

    return connections
        .firstOrNull {
            try {
                baseUrlHostname == Url(it.baseUrl).host
            } catch (e: URLParserException) {
                LOGGER.warning("Malformed Space connection base url - ${it.baseUrl}")
                false
            }
        }
        ?.let { SpaceGitCheckoutParams(it, projectKey, repositoryName) }
}

private suspend fun SpaceClient.postBuildStatus(action: SpaceGitScmCheckoutAction, build: Run<*, *>, status: CommitExecutionStatus? = null) {
    @Suppress("DEPRECATION")
    projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
        project = ProjectIdentifier.Key(action.projectKey),
        repository = action.repositoryName,
        revision = action.revision,
        branch = action.branch,
        changes = emptyList(),
        executionStatus = status ?: getStatusFromBuild(build),
        url = if (Jenkins.get().rootUrl != null) build.absoluteUrl else build.url,
        externalServiceName = "Jenkins",
        taskName = build.parent.fullName,
        taskId = build.parent.fullName,
        taskBuildId = BuildIdPrefix.BUILD + build.getNumber().toString(),
        timestamp = build.startTimeInMillis,
        description = build.description
    )
}

private fun getStatusFromBuild(build: Run<*, *>) = when {
    build.isBuilding ->
        CommitExecutionStatus.RUNNING

    successfulResults.contains(build.result) ->
        CommitExecutionStatus.SUCCEEDED

    cancelledResults.contains(build.result) ->
        CommitExecutionStatus.TERMINATED

    else ->
        CommitExecutionStatus.FAILED
}

private val successfulResults = listOf(Result.SUCCESS, Result.UNSTABLE)
private val cancelledResults = listOf(Result.ABORTED, Result.NOT_BUILT)

private val LOGGER = Logger.getLogger("SCMListener")
