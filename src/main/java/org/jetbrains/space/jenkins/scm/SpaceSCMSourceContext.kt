package org.jetbrains.space.jenkins.scm

import hudson.model.TaskListener
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceCriteria
import jenkins.scm.api.trait.SCMSourceContext

class SpaceSCMSourceContext(criteria: SCMSourceCriteria, observer: SCMHeadObserver, val eventHeads: Collection<SCMHead>) :
    SCMSourceContext<SpaceSCMSourceContext, SpaceSCMSourceRequest>(criteria, observer) {

    val discoveryHandlers = mutableListOf<SpaceSCMHeadDiscoveryHandler>()

    override fun newRequest(source: SCMSource, listener: TaskListener?): SpaceSCMSourceRequest {
        return SpaceSCMSourceRequest(source, this, listener)
    }

    fun withDiscoveryHandler(handler: SpaceSCMHeadDiscoveryHandler) {
        discoveryHandlers.add(handler)
    }
}
