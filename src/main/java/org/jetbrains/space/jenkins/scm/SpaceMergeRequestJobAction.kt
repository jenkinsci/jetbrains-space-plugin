package org.jetbrains.space.jenkins.scm

import hudson.model.Action
import org.jetbrains.space.jenkins.trigger.TriggerCause

/**
 * Job action that is stored in build run metadata and contains SpaceCode merge request information
 * for the job of a multibranch project set up for merge requests discovery.
 */
class SpaceMergeRequestJobAction(val triggerCause: TriggerCause.MergeRequest) : Action {
    override fun getDisplayName() =
        "Merge request in JetBrains SpaceCode"

    override fun getIconFileName() =
        "symbol-space plugin-jetbrains-space"

    override fun getUrlName() =
        triggerCause.url
}