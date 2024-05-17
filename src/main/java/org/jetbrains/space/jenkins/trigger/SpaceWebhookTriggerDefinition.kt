package org.jetbrains.space.jenkins.trigger

/**
 * Represents input data for creating a webhook in SpaceCode for either a job trigger or a multibranch project branch source.
 */
sealed class SpaceWebhookTriggerDefinition {
    class Branches(val branchSpec: String) : SpaceWebhookTriggerDefinition()

    class MergeRequests(
        val titleRegex: String?,
        val sourceBranchSpec: String?,
        val targetBranchSpec: String?,
        val isMergeRequestApprovalsRequired: Boolean
    ) : SpaceWebhookTriggerDefinition()
}