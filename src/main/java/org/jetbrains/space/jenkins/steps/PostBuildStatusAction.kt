package org.jetbrains.space.jenkins.steps

import hudson.model.Action
import java.io.Serializable

class PostBuildStatusAction(
    val spaceConnectionId: String,
    val projectKey: String,
    val repositoryName: String,
    val branch: String,
    val revision: String
) : Action, Serializable {

    override fun getIconFileName() = null
    override fun getDisplayName() = "Post build status to JetBrains Space"
    override fun getUrlName() = null
}
