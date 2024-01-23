package org.jetbrains.space.jenkins.trigger

/**
 * Determines whether a trigger will react to new commits in git branches or updates in merge requests.
 */
enum class SpaceWebhookTriggerType {
    Branches,
    MergeRequests
}