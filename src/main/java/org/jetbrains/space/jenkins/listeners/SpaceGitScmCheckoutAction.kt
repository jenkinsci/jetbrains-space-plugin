package org.jetbrains.space.jenkins.listeners

import hudson.EnvVars
import hudson.model.EnvironmentContributingAction
import hudson.model.Run
import org.jetbrains.space.jenkins.Env
import org.jetbrains.space.jenkins.config.SPACE_LOGO_ICON
import org.jetbrains.space.jenkins.trigger.TriggerCause
import java.net.URLEncoder

/**
 * An implementation of [hudson.model.Action] that is stored in build run metadata
 * and provides information about the source code checked out from Space git repository
 * and the Space-specific settings of the checkout step.
 */
class SpaceGitScmCheckoutAction(
    val spaceConnectionId: String,
    val spaceUrl: String,
    val projectKey: String,
    val repositoryName: String,
    val branch: String,
    val revision: String,
    val postBuildStatusToSpace: Boolean,
    val gitScmEnv: Map<String, String>,
    val cause: TriggerCause?
) : EnvironmentContributingAction {

    override fun getIconFileName() = SPACE_LOGO_ICON

    override fun getDisplayName() =
        if (cause is TriggerCause.MergeRequest)
            "Merge Request in JetBrains Space"
        else
            "Commits in JetBrains Space"


    override fun getUrlName() =
        if (cause is TriggerCause.MergeRequest)
            "$spaceUrl/p/$projectKey/reviews/${cause.number}/timeline"
        else {
            val query = URLEncoder.encode("head:" + revision, Charsets.UTF_8)
            "$spaceUrl/p/$projectKey/repositories/$repositoryName/commits?query=$query&commits=$revision"
        }

    override fun buildEnvironment(run: Run<*, *>, env: EnvVars) {
        env[Env.SPACE_URL] = spaceUrl
        env[Env.PROJECT_KEY] = projectKey
        env[Env.REPOSITORY_NAME] = repositoryName
        env.putAll(this.gitScmEnv)
    }
}