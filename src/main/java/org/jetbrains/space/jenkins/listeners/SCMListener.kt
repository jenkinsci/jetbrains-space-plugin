package org.jetbrains.space.jenkins.listeners

import hudson.model.*
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.RevisionParameterAction
import hudson.scm.SCM
import io.ktor.http.*
import jenkins.branch.MultiBranchProject
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.SpaceProjectConnection
import org.jetbrains.space.jenkins.config.getApiClient
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
 * Posts build status RUNNING to SpaceCode and appends an instance of [SpaceGitScmCheckoutAction] to the build metadata.
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
    val gitUrl = gitScmEnv[GitSCM.GIT_URL]

    val (spaceScmCheckoutParams, forcedMergeRequest) = when {
        scm is SpaceSCM -> {
            val (space, _, mergeRequest) = spacePluginConfiguration.getSpaceGitCheckoutParams(scm, build.parent, build)
            space to mergeRequest
        }

        gitUrl != null && gitScmEnv[GitSCM.GIT_COMMIT] != null -> {
            spacePluginConfiguration
                .getSpaceGitCheckoutParamsByCloneUrl(build.getParent(), gitScmEnv[GitSCM.GIT_URL]!!)
                ?.let { space ->
                    space to build.getForcedMergeRequest(space)
                }
        }

        else ->
            null
    } ?: run  {
        LOGGER.info("SpaceSCMListener.onCheckout - no SpaceCode git repository found")
        return
    }

    val (spaceConnection, spaceUrl) = build.getParent().getProjectConnection()
        ?: run {
            LOGGER.warning("SpaceSCMListener.onCheckout - SpaceCode connection not found")
            return
        }

    val triggerCause = forcedMergeRequest
        ?.let { TriggerCause.fromMergeRequest(it, spaceUrl) }
        ?.takeIf { it.sourceBranch == gitScmEnv[GitSCM.GIT_BRANCH] }
        ?: build.getCause(SpaceWebhookTriggerCause::class.java)
            ?.takeIf { cause ->
                val branchName = gitScmEnv[GitSCM.GIT_BRANCH]?.let {
                    if (it.startsWith(cause.repositoryName))
                        it.replace(cause.repositoryName, "refs/heads")
                    else
                        it
                }
                cause.repositoryName == spaceScmCheckoutParams.repositoryName
                        && cause.cause.branchForCheckout == branchName
            }
            ?.cause
        ?: (build.getParent().getProperty(BranchJobProperty::class.java)?.branch?.head as? SpaceSCMHead)?.triggerCause

    val spaceGitCheckoutAction = SpaceGitScmCheckoutAction(
        spaceUrl = spaceUrl,
        projectKey = spaceConnection.projectKey,
        repositoryName = spaceScmCheckoutParams.repositoryName,
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
        build.removeActions(SpaceGitScmCheckoutAction::class.java)
        allActions
            .sortedBy { it.cause == null }
            .forEach { a ->
                build.addAction(
                    a.copy(
                        sourceDisplayName = listOfNotNull(
                            a.repositoryName.takeIf { allActions.hasDistinctRepos() },
                            a.branch.takeIf { allActions.hasDistinctBranches(a.repositoryName) }
                        ).takeUnless { it.isEmpty() }?.joinToString(" \\ ")
                    )
                )
            }
    }

    triggerCause?.commitId?.let {
        build.addAction(RevisionParameterAction(it))
    }

    if (spaceGitCheckoutAction.postBuildStatusToSpace && (duplicate == null || !duplicate.postBuildStatusToSpace)) {
        runBlocking {
            try {
                spaceScmCheckoutParams.getApiClient().use {
                    it.postBuildStatus(spaceGitCheckoutAction, build)
                }
            } catch (ex: Throwable) {
                val message = "Failed to post build status to JetBrains SpaceCode, details: $ex"
                LOGGER.warning(message)
                listener.logger.println(message)
            }
        }
    }
}

private fun List<SpaceGitScmCheckoutAction>.hasDistinctRepos() =
    distinctBy { it.repositoryName }.size > 1

private fun List<SpaceGitScmCheckoutAction>.hasDistinctBranches(repositoryName: String) =
    filter { it.repositoryName == repositoryName }.distinctBy { it.branch }.size > 1

/**
 * Defines the list of fields to request from SpaceCode when fetching merge request data
 */
val mergeRequestFields: CodeReviewRecordPartial.() -> Unit = {
    id()
    number()
    title()
    project {
        key()
    }
    branchPairs {
        repository()
        sourceBranchInfo {
            ref()
            head()
            displayName()
        }
        targetBranchInfo  {
            ref()
            head()
            displayName()
        }
    }
}

/**
 * Handles the event of build completion.
 * If a build has been triggered by a change in SpaceCode or has checked out source code from a SpaceCode git repository,
 * posts the resulting build status to SpaceCode (unless automatic posting is disabled).
 * Invoked from [RunListenerImpl]
 */
fun onBuildCompleted(build: Run<*, *>, listener: TaskListener) {
    val postBuildStatusActions = build.getActions(PostBuildStatusAction::class.java)
    val checkoutActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
        .filter(SpaceGitScmCheckoutAction::postBuildStatusToSpace)
        .filter { action: SpaceGitScmCheckoutAction ->
            postBuildStatusActions.stream()
                .noneMatch { a: PostBuildStatusAction -> a.repositoryName == action.repositoryName }
        }

    val (spaceConnection, spaceUrl) = build.getParent().getProjectConnection()
        ?: run {
            val message = "SpaceCode connection not found"
            LOGGER.warning(message)
            listener.logger.println(message)
            return
        }

    for (action in checkoutActions) {
        try {
            runBlocking {
                spaceConnection.getApiClient(spaceUrl).use {
                    it.postBuildStatus(action, build)
                }
            }
        } catch (ex: Exception) {
            val message = "Failed to post build status to JetBrains SpaceCode, details: $ex"
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

/**
 * Groups together SpaceCode url, project-level connection and repository name
 */
class SpaceGitCheckoutParams(
    val baseUrl: String,
    val connection: SpaceProjectConnection,
    val repositoryName: String
) {
    fun getApiClient() =
        connection.getApiClient(baseUrl)
}

/**
 * Retrieves SpaceCode connection, project, repository and the list of branch specs for a given job with SpaceCode SCM settings.
 * Takes the checkout parameters from the SCM settings itself if specified explicitly there, otherwise falls back to build trigger settings.
 *
 * @throws IllegalArgumentException If the SpaceCode connection is not specified in the source checkout settings or build trigger.
 */
fun SpacePluginConfiguration.getSpaceGitCheckoutParams(scm: SpaceSCM, job: Job<*, *>, build: Run<*, *>?): Triple<SpaceGitCheckoutParams, String?, MergeRequestRecord?> {

    val customSpaceRepository = scm.customSpaceRepository
    val spaceWebhookTrigger = getSpaceWebhookTrigger(job)
    val triggerCause = build?.getCause(SpaceWebhookTriggerCause::class.java)
    val spaceScmHead = job.getProperty(BranchJobProperty::class.java)?.branch?.head as? SpaceSCMHead
    val multiBranchProjectScmSource = job.getMultiBranchSpaceScmSource()

    val (spaceConnection, spaceUrl) = job.getProjectConnection()
        ?: error("JetBrains SpaceCode connection not found")

    val repositoryName = customSpaceRepository?.repository
        ?: spaceWebhookTrigger?.repositoryName
        ?: multiBranchProjectScmSource?.repository
        ?: error("JetBrains SpaceCode connection is not specified neither in the source checkout settings nor in the build trigger")

    val params = SpaceGitCheckoutParams(spaceUrl, spaceConnection, repositoryName)
    val forcedMergeRequest = build?.getForcedMergeRequest(params)

    val branchToBuild =
        forcedMergeRequest?.branchPairs?.firstOrNull()?.sourceBranchInfo?.head
            ?: build?.getForcedBranchName()
            ?: when {
                spaceWebhookTrigger != null ->
                    triggerCause?.cause?.branchForCheckout
                spaceScmHead != null ->
                    spaceScmHead.getFullRef()
                customSpaceRepository != null && !customSpaceRepository.refspec.contains('*') && !customSpaceRepository.refspec.contains(' ') ->
                    customSpaceRepository.refspec
                else ->
                    null
            }

    return Triple(params, branchToBuild, forcedMergeRequest)
}

/**
 * Tries to match git clone url to the one of the SpaceCode connections configured globally.
 * If a SpaceCode connection is found, project key and repository name are extracted from the git clone url itself.
 */
private fun SpacePluginConfiguration.getSpaceGitCheckoutParamsByCloneUrl(job: Job<*, *>, gitCloneUrl: String): SpaceGitCheckoutParams? {
    val url = try {
        Url(gitCloneUrl)
    } catch (e: URLParserException) {
        LOGGER.warning("Malformed git SCM url - $gitCloneUrl");
        return null;
    }

    if (!url.host.startsWith("git."))
        return null;

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

    val (spaceConnection, spaceUrl) = job.getProjectConnection() ?: return null

    return SpaceGitCheckoutParams(spaceUrl, spaceConnection, repositoryName).takeIf {
        try {
            Url(spaceUrl).host == baseUrlHostname && it.connection.projectKey == projectKey
        } catch (e: URLParserException) {
            LOGGER.warning("Malformed SpaceCode connection base url - ${it.baseUrl}")
            false
        }
    }
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
