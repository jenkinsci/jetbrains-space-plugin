package org.jetbrains.space.jenkins.scm

import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMRevision
import java.util.stream.Stream

/**
 * Handles the discovery of different head types to be used by the
 * [SpaceSCMSource.retrieve] method as part
 * of processing a [request][SpaceSCMSourceRequest].
 *
 * @since 4.0.0
 */
interface SpaceSCMHeadDiscoveryHandler {
    /**
     * @return as stream of [heads][SCMHead] to be used for processing the source request
     */
    fun discoverHeads(): Stream<out SCMHead>

    /**
     * Creates a [revision][SCMRevision] for the specified head.
     *
     * @param head the head to create a revision for
     * @return the revision for the specified head
     */
    fun toRevision(head: SCMHead): SCMRevision
}
