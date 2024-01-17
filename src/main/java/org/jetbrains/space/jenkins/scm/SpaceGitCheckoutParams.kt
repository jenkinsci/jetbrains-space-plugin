package org.jetbrains.space.jenkins.scm

import hudson.model.Job
import hudson.plugins.git.BranchSpec
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionById
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerType

class SpaceGitCheckoutParams(val connection: SpaceConnection, val projectKey: String, val repositoryName: String)

fun getSpaceGitCheckoutParams(scm: SpaceSCM, job: Job<*, *>, spacePluginConfiguration: SpacePluginConfiguration): Pair<SpaceGitCheckoutParams, List<BranchSpec>> {
    val spaceWebhookTrigger = getSpaceWebhookTrigger(job)
    val customSpaceConnection = scm.customSpaceConnection
    return when {
        customSpaceConnection != null -> {
            val params = SpaceGitCheckoutParams(
                connection = spacePluginConfiguration.getConnectionByIdOrName(customSpaceConnection.spaceConnectionId, customSpaceConnection.spaceConnection)
                    ?: throw IllegalArgumentException("Specified JetBrains Space connection does not exist"),
                projectKey = customSpaceConnection.projectKey,
                repositoryName = customSpaceConnection.repository
            )
            val branches = customSpaceConnection.branches.takeUnless { it.isNullOrEmpty() } ?: listOf(BranchSpec("refs/heads/*"))
            params to branches
        }

        spaceWebhookTrigger != null -> {
            val params = SpaceGitCheckoutParams(
                connection = spacePluginConfiguration.getConnectionById(spaceWebhookTrigger.spaceConnectionId)
                    ?: throw IllegalArgumentException("Space connection specified in trigger not found"),
                projectKey = spaceWebhookTrigger.projectKey,
                repositoryName = spaceWebhookTrigger.repositoryName
            )
            val branches = when (spaceWebhookTrigger.triggerType) {
                SpaceWebhookTriggerType.Branches ->
                    spaceWebhookTrigger.branchSpec

                SpaceWebhookTriggerType.MergeRequests ->
                    spaceWebhookTrigger.mergeRequestBranchesSpec
            }.takeUnless { it.isNullOrBlank() }.let {
                listOf(BranchSpec(it ?: "refs/heads/*"))
            }
            params to branches
        }

        else -> {
            throw IllegalArgumentException("JetBrains Space connection is not specified neither in the source checkout settings nor in build trigger")
        }
    }
}