package org.jetbrains.space.jenkins.scm

import hudson.ExtensionList
import hudson.scm.SCM
import jenkins.scm.api.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getSpaceApiClientForMultiBranchProject
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.types.SRepoPushWebhookEvent

/**
 * Implementation of the [SCMHeadEvent] that is fired when a git branch matching one of the branch sources of a multibranch project gets updated.
 *
 * @see <a href="https://github.com/jenkinsci/scm-api-plugin/blob/master/docs/implementation.adoc">SCM API implementation guide</a>
 */
class SpaceBranchSCMHeadEvent(payload: SRepoPushWebhookEvent, spaceUrl: String) : SCMHeadEvent<SRepoPushWebhookEvent>(
    when {
        payload.created -> Type.CREATED
        payload.deleted -> Type.REMOVED
        else -> Type.UPDATED
    },
    payload,
    spaceUrl
) {

    override fun isMatch(navigator: SCMNavigator) =
        false

    override fun isMatch(scm: SCM) =
        scm is SpaceSCM

    override fun getSourceName() =
        payload.repository

    /**
     * Lists heads and revisions associated with this event (one  head-revision pair for the updated branch)
     */
    override fun heads(source: SCMSource): MutableMap<SCMHead, SCMRevision> {
        if (source !is SpaceSCMSource) return mutableMapOf()
        val commitRef = payload.newCommitId ?: return mutableMapOf()

        val head = runBlocking {
            getSpaceApiClientForMultiBranchProject(
                projectFullName = source.owner!!.fullName,
                spaceConnectionId = source.spaceConnectionId,
                projectKey = source.projectKey
            )?.use { spaceClient ->
                val commit = source.fetchCommits(spaceClient, listOf(commitRef))[commitRef] ?: return@use null
                SpaceBranchSCMHead(
                    name = payload.head.removePrefix(REFS_HEADS_PREFIX),
                    latestCommit = commit.id,
                    lastUpdated = commit.commitDate,
                    triggerCause = TriggerCause.BranchPush(
                        head = payload.head,
                        commitId = commit.id,
                        url = buildSpaceCommitUrl(
                            spaceClient.server.serverUrl,
                            source.projectKey,
                            source.repository,
                            commit.id
                        )
                    )
                )
            }
        } ?: return mutableMapOf<SCMHead, SCMRevision>()

        return mutableMapOf(head to SpaceSCMRevision(head, head.latestCommit))
    }
}
