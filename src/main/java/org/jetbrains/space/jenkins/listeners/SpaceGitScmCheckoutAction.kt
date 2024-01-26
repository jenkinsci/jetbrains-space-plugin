package org.jetbrains.space.jenkins.listeners

import hudson.EnvVars
import hudson.model.EnvironmentContributingAction
import hudson.model.Run
import org.jetbrains.space.jenkins.Env

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
    val gitScmEnv: Map<String, String>
) : EnvironmentContributingAction {

    override fun getIconFileName() = null

    override fun getDisplayName() = "Checkout sources from JetBrains Space"

    override fun getUrlName() = null

    override fun buildEnvironment(run: Run<*, *>, env: EnvVars) {
        env[Env.SPACE_URL] = spaceUrl
        env[Env.PROJECT_KEY] = projectKey
        env[Env.REPOSITORY_NAME] = repositoryName
        env.putAll(this.gitScmEnv)
    }
}