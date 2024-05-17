package org.jetbrains.space.jenkins.scm

import hudson.ExtensionList
import hudson.scm.SCM
import jenkins.scm.api.*
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getSpaceApiClientForMultiBranchProject
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.types.*

/**
 * Implementation of the [SCMHeadEvent] that is fired when a merge request matching one of the branch sources of a multibranch project gets updated.
 *
 * @see <a href="https://github.com/jenkinsci/scm-api-plugin/blob/master/docs/implementation.adoc">SCM API implementation guide</a>
 */
class SpaceMergeRequestSCMHeadEvent(val review: MergeRequestRecord, type: SCMEvent.Type, spaceUrl: String)
    : SCMHeadEvent<MergeRequestRecord>(type, review, spaceUrl) {

    override fun isMatch(navigator: SCMNavigator) =
        false

    override fun isMatch(scm: SCM) =
        scm is SpaceSCM

    override fun getSourceName() =
        "Merge request in ${review.branchPairs.first().repository}"

    /**
     * Lists heads and revisions associated with this event - one for the merge request itself and another for its source branch
     */
    override fun heads(source: SCMSource): MutableMap<SCMHead, SCMRevision> {
        if (source !is SpaceSCMSource) return mutableMapOf()

        val branchPair = review.branchPairs.first()
        return runBlocking {
            getSpaceApiClientForMultiBranchProject(
                projectFullName = source.owner!!.fullName,
                spaceConnectionId = source.spaceConnectionId,
                projectKey = source.projectKey
            )?.use { spaceClient ->
                val commits = source.fetchCommits(
                    spaceClient,
                    listOf(branchPair.sourceBranchInfo!!.ref, branchPair.targetBranchInfo!!.ref)
                )
                val sourceHead = branchPair.sourceBranchInfo!!.toSpaceBranchSCMHead(commits, spaceClient, source)
                val targetHead = branchPair.targetBranchInfo!!.toSpaceBranchSCMHead(commits, spaceClient, source)

                val mergeRequestHead = SpaceMergeRequestSCMHead(
                    mergeRequestId = review.id,
                    sourceBranchName = branchPair.sourceBranchInfo!!.displayName,
                    latestCommit = branchPair.sourceBranchInfo!!.ref,
                    lastUpdated = commits[branchPair.sourceBranchInfo!!.ref]?.commitDate ?: -1,
                    triggerCause = TriggerCause.fromMergeRequest(review, spaceClient.server.serverUrl),
                    target = targetHead,
                    checkoutStrategy = ChangeRequestCheckoutStrategy.HEAD
                )

                val sourceRevision = SpaceSCMRevision(sourceHead, branchPair.sourceBranchInfo!!.ref)

                mutableMapOf(
                    mergeRequestHead to sourceRevision,
                    sourceHead to sourceRevision
                )
            }
                ?: mutableMapOf()
        }
    }
}

fun CodeReviewRecord.createScmHeadEvent(appInstance: SpaceAppInstance, meta: KMetaMod?) =
    (this as? MergeRequestRecord)?.let {
        SpaceMergeRequestSCMHeadEvent(
            review = it,
            type = when (meta?.method) {
                "Created" -> SCMEvent.Type.CREATED
                "Deleted" -> SCMEvent.Type.REMOVED
                else -> SCMEvent.Type.UPDATED
            },
            spaceUrl = appInstance.spaceServer.serverUrl
        )
    }