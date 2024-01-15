package org.jetbrains.space.jenkins.trigger

import hudson.model.Cause

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

class MergeRequest(val projectKey: String, val id: String, val number: Int, val title: String, val sourceBranch: String?, val url: String)
