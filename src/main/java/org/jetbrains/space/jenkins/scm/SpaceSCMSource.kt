package org.jetbrains.space.jenkins.scm

import hudson.ExtensionList
import hudson.model.Action
import hudson.model.TaskListener
import jenkins.scm.api.*
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.trait.SCMSourceContext
import jenkins.scm.api.trait.SCMSourceRequest
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getSpaceApiClientForMultiBranchProject
import org.jetbrains.space.jenkins.config.getOrgConnection
import org.jetbrains.space.jenkins.listeners.mergeRequestFields
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerDefinition
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.BatchInfo
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import java.net.URLEncoder

enum class SpaceSCMSourceType {
    Branches,
    MergeRequests
}

/**
 * Prepares input data for creating a webhook in SpaceCode for a given branch source of a multibranch project
 */
fun SpaceSCMSource.getWebhookDefinition() =
    when (type) {
        SpaceSCMSourceType.Branches ->
            SpaceWebhookTriggerDefinition.Branches(branchSpec)

        SpaceSCMSourceType.MergeRequests ->
            SpaceWebhookTriggerDefinition.MergeRequests(
                titleRegex = mergeRequestTitleRegex,
                sourceBranchSpec = mergeRequestSourceBranchSpec,
                targetBranchSpec = mergeRequestTargetBranchSpec,
                isMergeRequestApprovalsRequired = false
            )
    }

/**
 * Performs the discovery of heads (branches and merge requests) to create jobs for.
 */
fun SpaceSCMSource.retrieve(criteria: SCMSourceCriteria?, observer: SCMHeadObserver, event: SCMHeadEvent<*>?, listener: TaskListener) {
    SpaceSCMSourceContext(criteria, observer).withTraits(traits.orEmpty()).newRequest(this, listener).use { request ->
        getSpaceApiClientForMultiBranchProject(
            projectFullName = owner!!.fullName,
            spaceConnectionId = spaceConnectionId,
            projectKey = projectKey
        )?.use { spaceClient ->
            fun doProcess(scmHead: SCMHead, scmRevision: SCMRevision) {
                request.process(
                    scmHead,
                    scmRevision,
                    { head, _ ->
                        SpaceSCMProbe(
                            head, spaceClient, ProjectIdentifier.Key(projectKey), repository,
                            ownsSpaceApiClient = false
                        )
                    },
                    { head, revision, isMatch ->
                        listener.logger.println("Head: ${head.name}, ref: ${revision?.head}, isMatch: $isMatch")
                    }
                )
            }

            runBlocking {
                when (type) {
                    SpaceSCMSourceType.Branches -> {
                        if (event is SpaceBranchSCMHeadEvent) {
                            event.heads(this@retrieve).filterKeys { it is SpaceBranchSCMHead }.forEach {
                                doProcess(it.key, it.value)
                            }
                            return@runBlocking
                        }

                        var batch = spaceClient.projects.repositories.getHeads(
                            ProjectIdentifier.Key(projectKey),
                            repository,
                            branchSpec.takeUnless { it.isNullOrBlank() || it == "*" }?.split(',').orEmpty(),
                            batchInfo = BatchInfo(null, BATCH_SIZE)
                        )
                        while (batch.data.isNotEmpty()) {
                            val commits = fetchCommits(spaceClient, batch.data.map { it.ref })
                            batch.data
                                .map {
                                    SpaceBranchSCMHead(
                                        name = it.head.removePrefix(REFS_HEADS_PREFIX),
                                        latestCommit = it.ref,
                                        lastUpdated = commits[it.ref]?.commitDate ?: -1L,
                                        triggerCause = TriggerCause.BranchPush(
                                            head = it.head,
                                            commitId = it.ref,
                                            url = buildSpaceCommitUrl(
                                                spaceClient.server.serverUrl,
                                                projectKey,
                                                repository,
                                                it.ref
                                            )
                                        )
                                    )
                                }
                                .forEach { scmHead ->
                                    doProcess(scmHead, SpaceSCMRevision(scmHead, scmHead.latestCommit))
                                }

                            batch = spaceClient.projects.repositories.getHeads(
                                ProjectIdentifier.Key(projectKey),
                                repository,
                                branchSpec.takeUnless { it.isNullOrBlank() || it == "*" }?.split(',').orEmpty(),
                                batchInfo = BatchInfo(batch.next, BATCH_SIZE)
                            ) {
                                head()
                                ref()
                            }
                        }
                    }

                    SpaceSCMSourceType.MergeRequests -> {
                        if (event is SpaceMergeRequestSCMHeadEvent) {
                            event.heads(this@retrieve).filterKeys { it is SpaceMergeRequestSCMHead }.forEach {
                                doProcess(it.key, it.value)
                            }
                            return@runBlocking
                        }

                        var batch = spaceClient.projects.codeReviews.getAllCodeReviews(
                            ProjectIdentifier.Key(projectKey),
                            repository = repository,
                            state = CodeReviewStateFilter.Opened,
                            type = ReviewType.MergeRequest,
                            batchInfo = BatchInfo(null, BATCH_SIZE)
                        ) {
                            review(mergeRequestFields)
                        }
                        val titleRegex = mergeRequestTitleRegex.takeUnless { it.isNullOrBlank() }?.let { Regex(it) }
                        while (batch.data.isNotEmpty()) {
                            val mergeRequests = batch.data.map { it.review as MergeRequestRecord }.filter { review ->
                                val branchPair = review.branchPairs.first()
                                if (titleRegex != null && !titleRegex.matches(review.title)) {
                                    listener.logger.println("Head: ${branchPair.sourceBranchInfo?.head}, ref: ${branchPair.sourceBranchInfo?.ref}, isMatch: false (title regex)")
                                    return@filter false
                                }

                                if (!mergeRequestSourceBranchSpec.isNullOrBlank() && !isMatch(
                                        branchPair.sourceBranchInfo?.head,
                                        mergeRequestSourceBranchSpec
                                    )
                                ) {
                                    listener.logger.println("Head: ${branchPair.sourceBranchInfo?.head}, ref: ${branchPair.sourceBranchInfo?.ref}, isMatch: false (source branch spec)")
                                    return@filter false
                                }

                                if (!mergeRequestTargetBranchSpec.isNullOrBlank() && !isMatch(
                                        branchPair.targetBranchInfo?.head,
                                        mergeRequestTargetBranchSpec
                                    )
                                ) {
                                    listener.logger.println("Head: ${branchPair.sourceBranchInfo?.head}, ref: ${branchPair.sourceBranchInfo?.ref}, isMatch: false (target branch spec, ${branchPair.targetBranchInfo?.head})")
                                    return@filter false
                                }

                                true
                            }

                            val commitIds = mergeRequests
                                .flatMap { it.branchPairs }
                                .flatMap { listOfNotNull(it.targetBranchInfo?.ref, it.sourceBranchInfo?.ref) }
                            val commits = fetchCommits(spaceClient, commitIds)
                            mergeRequests.forEach { review ->
                                val branchPair = review.branchPairs.first()
                                val target = branchPair.targetBranchInfo!!.toSpaceBranchSCMHead(
                                    commits,
                                    spaceClient,
                                    this@retrieve
                                )
                                val mergeRequestHead = SpaceMergeRequestSCMHead(
                                    mergeRequestId = review.id,
                                    sourceBranchName = branchPair.sourceBranchInfo!!.displayName,
                                    latestCommit = branchPair.sourceBranchInfo!!.ref,
                                    lastUpdated = commits[branchPair.sourceBranchInfo!!.ref]?.commitDate ?: -1,
                                    target = target,
                                    checkoutStrategy = ChangeRequestCheckoutStrategy.HEAD,
                                    triggerCause = TriggerCause.fromMergeRequest(review, spaceClient.server.serverUrl)
                                )
                                doProcess(
                                    mergeRequestHead,
                                    SpaceSCMRevision(mergeRequestHead, branchPair.sourceBranchInfo!!.ref)
                                )
                            }

                            batch = spaceClient.projects.codeReviews.getAllCodeReviews(
                                ProjectIdentifier.Key(projectKey),
                                repository = repository,
                                state = CodeReviewStateFilter.Opened,
                                type = ReviewType.MergeRequest,
                                batchInfo = BatchInfo(batch.next, BATCH_SIZE)
                            )
                        }
                    }
                }
            }
        }
    }
}

class SpaceSCMSourceContext(criteria: SCMSourceCriteria?, observer: SCMHeadObserver)
    : SCMSourceContext<SpaceSCMSourceContext, SpaceSCMSourceRequest>(criteria, observer) {

    override fun newRequest(source: SCMSource, listener: TaskListener?): SpaceSCMSourceRequest {
        return SpaceSCMSourceRequest(source, this, listener)
    }
}

class SpaceSCMSourceRequest(source: SCMSource, context: SpaceSCMSourceContext, listener: TaskListener?)
    : SCMSourceRequest(source, context, listener) {
}

/**
 * Returns the list of actions to be applied to the job created by this branch source for a given head.
 */
fun SpaceSCMSource.retrieveActions(head: SCMHead): List<Action> {
    return when (head) {
        is SpaceBranchSCMHead -> {
            val spaceUrl = getOrgConnection(spaceConnectionId)?.baseUrl ?: return emptyList()
            listOf(SpaceBranchJobAction(spaceUrl, projectKey, repository, head.name))
        }
        is SpaceMergeRequestSCMHead ->
            listOf(SpaceMergeRequestJobAction(head.triggerCause))
        else ->
            emptyList()
    }
}

private fun isMatch(head: String?, branchSpec: String): Boolean {
    if (head == null)
        return false
    return BranchPattern.matchesPattern(branchSpec.split(","), isRegex = false, head)
}

suspend fun SpaceSCMSource.fetchCommits(spaceClient: SpaceClient, ids: List<String>) =
    spaceClient.projects.repositories.commits(
        ProjectIdentifier.Key(projectKey),
        repository,
        "id:" + ids.joinToString(",")
    ) {
        id()
        commitDate()
    }.data.associateBy { it.id }

private const val BATCH_SIZE = 30

fun buildSpaceCommitUrl(spaceUrl: String, projectKey: String, repository: String, head: String) =
    "$spaceUrl/p/$projectKey/repositories/$repository/commits?query=${URLEncoder.encode("head:" + head, Charsets.UTF_8)}"
