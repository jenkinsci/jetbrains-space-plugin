package org.jetbrains.space.jenkins.trigger

import hudson.model.Cause

class SpaceWebhookTriggerCause(val triggerType: SpaceWebhookTriggerType) : Cause() {

    override fun getShortDescription(): String {
        return when (triggerType) {
            SpaceWebhookTriggerType.Branches ->
                "Triggered by push to git branch"
            SpaceWebhookTriggerType.MergeRequests ->
                "Triggered by merge request updated"
        }
    }
}