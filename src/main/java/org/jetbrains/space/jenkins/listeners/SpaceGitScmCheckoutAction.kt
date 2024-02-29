package org.jetbrains.space.jenkins.listeners

import hudson.EnvVars
import hudson.model.EnvironmentContributingAction
import hudson.model.Run
import org.jetbrains.space.jenkins.Env
import org.jetbrains.space.jenkins.config.SPACE_LOGO_ICON
import org.jetbrains.space.jenkins.trigger.TriggerCause
import org.jetbrains.space.jenkins.trigger.TriggerCause.MergeRequest
import java.net.URLEncoder

/**
 * An implementation of [hudson.model.Action] that is stored in build run metadata
 * and provides information about the source code checked out from Space git repository
 * and the Space-specific settings of the checkout step.
 */
data class SpaceGitScmCheckoutAction(
    val spaceConnectionId: String,
    val spaceConnectionName: String,
    val spaceUrl: String,
    // lgtm[jenkins/plaintext-storage]
    val projectKey: String,
    val repositoryName: String,
    val branch: String,
    val revision: String,
    val postBuildStatusToSpace: Boolean,
    val gitScmEnv: Map<String, String>,
    val cause: TriggerCause?,
    val sourceDisplayName: String? = null
) : EnvironmentContributingAction {

    override fun getIconFileName() = SPACE_LOGO_ICON

    override fun getDisplayName() =
        if (cause is TriggerCause.MergeRequest)
            "Merge Request in " + (sourceDisplayName ?: "JetBrains Space")
        else
            "Commits in " + (sourceDisplayName ?: "JetBrains Space")


    override fun getUrlName() =
        if (cause is TriggerCause.MergeRequest)
            cause.url
        else {
            val query = URLEncoder.encode("head:" + revision, Charsets.UTF_8)
            "$spaceUrl/p/$projectKey/repositories/$repositoryName/commits?query=$query&commits=$revision"
        }

    override fun buildEnvironment(run: Run<*, *>, env: EnvVars) {
        env[Env.SPACE_URL] = spaceUrl
        env[Env.PROJECT_KEY] = projectKey
        env[Env.REPOSITORY_NAME] = repositoryName

        (cause as? TriggerCause.MergeRequest)?.let {
            env.putMergeRequestProperties(it)
        }

        env.putAll(this.gitScmEnv)
    }
}

fun EnvVars.putMergeRequestProperties(mergeRequest: MergeRequest) {
    put(Env.MERGE_REQUEST_ID, mergeRequest.id)
    put(Env.MERGE_REQUEST_NUMBER, mergeRequest.number.toString())
    put(Env.MERGE_REQUEST_URL, mergeRequest.url)
    put(Env.MERGE_REQUEST_TITLE, mergeRequest.title)
    if (mergeRequest.sourceBranch != null) {
        put(Env.MERGE_REQUEST_SOURCE_BRANCH, mergeRequest.sourceBranch)
    }
    if (mergeRequest.targetBranch != null) {
        put(Env.MERGE_REQUEST_TARGET_BRANCH, mergeRequest.targetBranch)
    }

    var safeMerge = mergeRequest.safeMerge
    put(Env.IS_SAFE_MERGE, (safeMerge != null).toString())
    if (safeMerge != null) {
        put(Env.IS_DRY_RUN, safeMerge.isDryRun.toString())
        put(Env.SAFE_MERGE_STARTED_BY_USER_ID, safeMerge.startedByUserId)
    }
}