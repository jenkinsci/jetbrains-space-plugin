package org.jetbrains.space.jenkins.scm

import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.scm.api.SCMHead
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.mixin.ChangeRequestSCMHead2
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.types.GitCommitInfo
import space.jetbrains.api.runtime.types.MergeRequestBranch

/**
 * SCM head (branch or merge request) that is produced as a result of discovery performed by the [SpaceSCMSource] instance
 * to instantiate child jobs for a multibranch project.
 *
 * @see <a href="https://github.com/jenkinsci/scm-api-plugin/blob/master/docs/implementation.adoc">SCM API implementation guide</a>
 */
abstract class SpaceSCMHead(name: String, val latestCommit: String, val lastUpdated: Long) : SCMHead(name) {
    abstract fun getFullRef(): String

    abstract val triggerCause: TriggerCause?

    override fun toString(): String {
        return "${javaClass.simpleName}{'$name'}"
    }
}

/**
 * Represents a branch in SpaceCode git repository for which a Jenkins job should be automatically created within the multibranch project.
 */
class SpaceBranchSCMHead(
    name: String,
    latestCommit: String,
    lastUpdated: Long,
    override val triggerCause: TriggerCause.BranchPush
) : SpaceSCMHead(name, latestCommit, lastUpdated) {
    override fun getFullRef() = if (name.startsWith("refs/")) name else REFS_HEADS_PREFIX + name
}

/**
 * Represents a merge request in SpaceCode for which a Jenkins job should be automatically created within the multibranch project.
 */
class SpaceMergeRequestSCMHead(
    private val mergeRequestId: String,
    private val sourceBranchName: String,
    latestCommit: String,
    lastUpdated: Long,
    override val triggerCause: TriggerCause.MergeRequest,
    private val target: SpaceBranchSCMHead,
    private val checkoutStrategy: ChangeRequestCheckoutStrategy,
) : SpaceSCMHead(sourceBranchName, latestCommit, lastUpdated), ChangeRequestSCMHead2 {

    override fun getId() = mergeRequestId

    override fun getTarget() = target

    override fun getCheckoutStrategy() = checkoutStrategy

    override fun getOriginName() = sourceBranchName

    override fun getFullRef() =
        if (sourceBranchName.startsWith("refs/")) sourceBranchName else REFS_HEADS_PREFIX + sourceBranchName
}

/**
 * Represents a git commit referenced by the [SpaceSCMHead] instance (either a branch or a merge request)
 */
class SpaceSCMRevision(head: SpaceSCMHead, commitHash: String) : AbstractGitSCMSource.SCMRevisionImpl(head, commitHash) {
    override fun toString(): String {
        return "SpaceSCMRevision{'${head.name}':'$hash'}"
    }
}

val REFS_HEADS_PREFIX: String = "refs/heads/"

fun MergeRequestBranch.toSpaceBranchSCMHead(
    commits: Map<String, GitCommitInfo>,
    spaceClient: SpaceClient,
    source: SpaceSCMSource
) = SpaceBranchSCMHead(
    name = displayName,
    latestCommit = ref,
    lastUpdated = commits[ref]?.commitDate ?: -1,
    triggerCause = TriggerCause.BranchPush(
        head = displayName,
        commitId = ref,
        url = buildSpaceCommitUrl(
            spaceClient.server.serverUrl,
            source.projectKey,
            source.repository,
            displayName
        )
    )
)