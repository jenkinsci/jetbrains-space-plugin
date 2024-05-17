package org.jetbrains.space.jenkins.scm

import hudson.model.Action
import io.ktor.http.*

/**
 * Job action that is stored in build run metadata and contains SpaceCode repository and branch information
 * for the job of a multibranch project set up for branches discovery.
 */
class SpaceBranchJobAction(val spaceUrl: String, val projectKey: String, val repository: String, val branchName: String) : Action {
    override fun getDisplayName() =
        "Branch in JetBrains SpaceCode"

    override fun getIconFileName() =
        "symbol-space plugin-jetbrains-space"

    override fun getUrlName() =
        URLBuilder("$spaceUrl/p/$projectKey/repositories/$repository/commits").apply {
            parameters.apply {
                append("query", "head:$branchName")
            }
        }.buildString()
}