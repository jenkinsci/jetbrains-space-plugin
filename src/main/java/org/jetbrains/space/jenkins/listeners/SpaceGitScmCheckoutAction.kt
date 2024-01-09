package org.jetbrains.space.jenkins.listeners

import hudson.model.Action

class SpaceGitScmCheckoutAction(
    val spaceConnectionId: String,
    val projectKey: String,
    val repositoryName: String,
    val branch: String,
    val revision: String,
    val postBuildStatusToSpace: Boolean
) : Action {

    override fun getIconFileName() = null

    override fun getDisplayName() = "Checkout sources from JetBrains Space"

    override fun getUrlName() = null
}