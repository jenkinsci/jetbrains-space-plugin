package org.jetbrains.space.jenkins.trigger

import hudson.model.Cause
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.steps.*
import space.jetbrains.api.runtime.types.MergeRequestRecord

/**
 * Represents the cause of a build triggered by a Space event or safe merge command.
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
    val cause: TriggerCause
) : Cause() {

    companion object {
        fun fromMergeRequest(mergeRequest: MergeRequestRecord, spaceConnection: SpaceConnection, safeMerge: TriggerCauseSafeMerge? = null) =
            SpaceWebhookTriggerCause(
                spaceConnectionId = spaceConnection.id,
                spaceUrl = spaceConnection.baseUrl,
                projectKey = mergeRequest.project.key,
                repositoryName = mergeRequest.branchPairs.firstOrNull()?.repository.orEmpty(),
                triggerType = SpaceWebhookTriggerType.MergeRequests,
                cause = TriggerCause.fromMergeRequest(mergeRequest, spaceConnection, safeMerge)
            )
    }

    val mergeRequest get() = cause as? TriggerCause.MergeRequest
    val isDryRun get() = mergeRequest?.safeMerge?.isDryRun == true
    val isSafeMerge get() = mergeRequest?.safeMerge?.isDryRun == false
    val branchPush get() = cause as? TriggerCause.BranchPush

    override fun getShortDescription(): String {
        return when (cause) {
            is TriggerCause.BranchPush ->
                "Triggered by push to \"${branchPush?.head}\""
            is TriggerCause.MergeRequest ->
                when {
                    isDryRun ->
                        "Triggered by dry run for the merge request \"${mergeRequest?.title}\" (${mergeRequest?.url})"
                    isSafeMerge ->
                        "Triggered by safe merge for the merge request \"${mergeRequest?.title}\" (${mergeRequest?.url})"
                    else ->
                        "Triggered by changes to the merge request \"${mergeRequest?.title}\" (${mergeRequest?.url})"
                }
        }
    }
}

/**
 * Contains data specific for each type of the triggering event - either git branch push or merge request update or safe merge
 */
sealed interface TriggerCause {
    companion object {
        fun fromMergeRequest(mergeRequest: MergeRequestRecord, spaceConnection: SpaceConnection, safeMerge: TriggerCauseSafeMerge? = null) =
            TriggerCause.MergeRequest(
                projectKey = mergeRequest.project.key,
                id = mergeRequest.id,
                number = mergeRequest.number,
                title = mergeRequest.title,
                sourceBranch = mergeRequest.branchPairs.firstOrNull()?.sourceBranchInfo?.head,
                targetBranch = mergeRequest.branchPairs.firstOrNull()?.targetBranchInfo?.head,
                url = "${spaceConnection.baseUrl}/p/${mergeRequest.project.key}/reviews/${mergeRequest.number}",
                safeMerge = safeMerge
            )
    }

    class MergeRequest(
        val projectKey: String,
        val id: String,
        val number: Int,
        val title: String,
        val sourceBranch: String?,
        val targetBranch: String?,
        val url: String,
        val safeMerge: TriggerCauseSafeMerge? = null
    ): TriggerCause {
        override val commitId = safeMerge?.safeMergeCommit

        override val branchForCheckout =
            safeMerge?.safeMergeBranch ?: sourceBranch ?: error("source branch for the merge request not specified")
    }

    class BranchPush(
        val head: String,
        override val commitId: String,
        val url: String
    ): TriggerCause {
        override val branchForCheckout = head
    }

    val commitId: String?
    val branchForCheckout: String
}

/**
 * Contains information about the merge request safe merge operation that triggered the build
 *
 * @param safeMergeBranch Temporary branch created by Space for merging merge request source into target and running checks
 * @param safeMergeCommit Temporary merge commit pointed to by the temporary merge branch
 * @param isDryRun Whether Space will run the build to perform the checks without actually merging source branch into target even if all checks succeed
 * @param startedByUserId Id of the Space user that initiated safe merge operation
 */
class TriggerCauseSafeMerge(
    val safeMergeBranch: String,
    val safeMergeCommit: String,
    val isDryRun: Boolean,
    val startedByUserId: String?
)
