package org.jetbrains.space.jenkins.trigger

import hudson.model.Cause
import org.jetbrains.space.jenkins.steps.*

/**
 * Represents the cause of a build triggered by a Space webhook.
 * This cause is stored in the build metadata and is available to all the other parts of the job processing pipeline.
 *
 * Specifically, it is used to inject Space-related environment variables and to get default Space connection parameters
 * and merge request number for [ReportBuildStatusStep] and [PostReviewTimelineMessageStep].
 */
class SpaceWebhookTriggerCause(
    val spaceConnectionId: String,
    val spaceUrl: String,
    val projectKey: String,
    val repositoryName: String,
    val triggerType: SpaceWebhookTriggerType,
    val mergeRequest: MergeRequest? = null
) : Cause() {

    override fun getShortDescription(): String {
        return when (triggerType) {
            SpaceWebhookTriggerType.Branches ->
                "Triggered by push to git branch"
            SpaceWebhookTriggerType.MergeRequests ->
                "Triggered by merge request updated"
        }
    }
}

class MergeRequest(
    val projectKey: String,
    val id: String,
    val number: Int,
    val title: String,
    val sourceBranch: String?,
    val url: String
)
