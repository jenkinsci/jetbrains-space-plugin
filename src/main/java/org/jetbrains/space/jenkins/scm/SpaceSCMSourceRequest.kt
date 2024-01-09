package org.jetbrains.space.jenkins.scm

import hudson.model.TaskListener
import jenkins.scm.api.SCMSource
import jenkins.scm.api.trait.SCMSourceRequest

open class SpaceSCMSourceRequest(source: SCMSource, context: SpaceSCMSourceContext, listener: TaskListener?) : SCMSourceRequest(source, context, listener) {
    val discoveryHandlers = context.discoveryHandlers
}
