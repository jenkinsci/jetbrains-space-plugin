package org.jetbrains.space.jenkins.steps

import hudson.model.Action
import java.io.Serializable

/**
 * An implementation of [hudson.model.Action] that is stored in build run metadata
 * to notify the build completion listener [RunListenerImpl] that build status reporting has already happened
 * and it shouldn't post the final status on build completion, overwriting the one provided by this step
 */
class PostBuildStatusAction(
    val repositoryName: String,
    val branch: String,
    val revision: String
) : Action, Serializable {

    override fun getIconFileName() = null
    override fun getDisplayName() = "Post build status to JetBrains SpaceCode"
    override fun getUrlName() = null
}
