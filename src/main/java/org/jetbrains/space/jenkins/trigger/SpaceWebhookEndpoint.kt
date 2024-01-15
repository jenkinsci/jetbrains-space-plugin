package org.jetbrains.space.jenkins.trigger

import hudson.ExtensionList
import hudson.model.CauseAction
import io.ktor.http.*
import jenkins.model.Jenkins
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionByClientId
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.StaplerResponse
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.helpers.processPayload
import space.jetbrains.api.runtime.ktorClientForSpace
import space.jetbrains.api.runtime.types.*
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.servlet.http.HttpServletResponse

@OptIn(ExperimentalSpaceSdkApi::class)
fun SpaceWebhookEndpoint.doTrigger(request: StaplerRequest, response: StaplerResponse) {
    val contentType = request.contentType
    if (contentType != null && !contentType.startsWith("application/json")) {
        LOGGER.warning(String.format("Invalid content type %s", contentType))
        response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Expected application/json content type")
        return
    }

    val requestAdapter = RequestAdapter(request, response)
    runBlocking {
        Space.processPayload(requestAdapter, ktorClientForSpace, spaceAppInstanceStorage) { payload ->
            if (payload !is WebhookRequestPayload) {
                LOGGER.warning("Got payload of type " + payload.javaClass.getSimpleName())
                return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
            }

            val allJobs = Jenkins.get().getAllItems(TriggeredItem::class.java)
            val (job, trigger) = allJobs.findBySpaceWebhookId(payload.webhookId)
                ?: run {
                    // fallback to fetching and parsing webhook name
                    clientWithClientCredentials().fetchWebhookById(payload.webhookId)
                        ?.let { getTriggerId(it) }
                        ?.let { allJobs.findByTriggerId(it) }
                        .also {
                            if (it != null) {
                                it.second.ensureSpaceWebhooks()
                            } else {
                                LOGGER.warning("Trigger found for webhook id = ${payload.webhookId} found by fallback to webhook name")
                            }
                        }
                }
                ?: run {
                    LOGGER.warning("No registered trigger found for webhook id = ${payload.webhookId}")
                    return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                }

            val triggerItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job)
            if (triggerItem == null) {
                LOGGER.info("Cannot trigger item of type ${job::class.qualifiedName}")
                return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
            }

            val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
                ?: error("Space plugin configuration cannot be resolved")

            val spaceConnection = spacePluginConfiguration.getConnectionByClientId(payload.clientId)
                ?: error("Space connection cannot be found for client id specified in webhook payload")

            val result = getTriggeredBuildCause(trigger, payload.payload, spaceConnection)

            when (result) {
                is WebhookEventResult.RunBuild -> {
                    val causeAction = CauseAction(result.cause)
                    NoIdeaFreeze.triggerBuild(triggerItem, causeAction)
                    SpaceHttpResponse.RespondWithOk
                }

                is WebhookEventResult.UnexpectedEvent -> {
                    trigger.ensureSpaceWebhooks()
                    SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                }

                is WebhookEventResult.IgnoredEvent ->
                    SpaceHttpResponse.RespondWithCode(HttpStatusCode.Accepted)
            }
        }
    }

    if (!requestAdapter.responded) {
        LOGGER.warning("Webhook processing resulted in no response, responding OK by default")
        response.status = HttpStatusCode.OK.value
    }
}

private fun getTriggeredBuildCause(trigger: SpaceWebhookTrigger, event: WebhookEvent, spaceConnection: SpaceConnection): WebhookEventResult {
    return when (trigger.triggerType) {
        SpaceWebhookTriggerType.Branches -> {
            if (event !is SRepoHeadsWebhookEvent) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event but expected ${SRepoCommitsWebhookEvent::class.simpleName} for the build trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            trigger.customSpaceRepository?.let { repo ->
                if (event.projectKey.key != repo.projectKey || event.repository != repo.repositoryName) {
                    LOGGER.warning("Event project and repository do not match those configured for the build trigger ${trigger.id}")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            // TODO - figure out branches that triggered the build and trigger a separate build for each of them,
            // putting branch name and latest commit id to environment variables
            WebhookEventResult.RunBuild(
                SpaceWebhookTriggerCause(
                    spaceConnectionId = spaceConnection.id,
                    spaceUrl = spaceConnection.baseUrl,
                    projectKey = event.projectKey.key,
                    repositoryName = event.repository,
                    triggerType = trigger.triggerType
                )
            )
        }

        SpaceWebhookTriggerType.MergeRequests -> {
            val (review, eventMatchesTrigger) = when (event) {
                is CodeReviewWebhookEvent ->
                    event.review to event.matchesTrigger(trigger)

                is CodeReviewUpdatedWebhookEvent ->
                    event.review to event.matchesTrigger(trigger)

                is CodeReviewParticipantWebhookEvent ->
                    event.review to event.matchesTrigger(trigger)

                is CodeReviewCommitsUpdatedWebhookEvent ->
                    event.review to true

                else -> {
                    LOGGER.warning("Got unexpected ${event.javaClass.simpleName} event type instead of code review event for the build trigger ${trigger.id}")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            if (review == null) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event without review for the build trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            if (review !is MergeRequestRecord) {
                LOGGER.info("Got ${event.javaClass.simpleName} event for commit set review for the build trigger ${trigger.id}")
                return WebhookEventResult.IgnoredEvent
            }

            trigger.customSpaceRepository?.let { repo ->
                if (review.project.key != repo.projectKey || review.branchPairs.firstOrNull()?.repository != repo.repositoryName) {
                    LOGGER.warning("Event project and repository do not match those configured for trigger ${trigger.id}")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            if (!eventMatchesTrigger) {
                // specific message should have been logged while checking the match
                return WebhookEventResult.UnexpectedEvent
            }

            if (trigger.triggerType == SpaceWebhookTriggerType.MergeRequests) {
                if (trigger.isMergeRequestApprovalsRequired) {
                    val reviewers = review.participants?.filter { it.role == CodeReviewParticipantRole.Reviewer }.orEmpty()
                    if (reviewers.isEmpty() || !reviewers.all { it.state == ReviewerState.Accepted }) {
                        LOGGER.info("Ignoring webhook for trigger ${trigger.id} because merge request hasn't been accepted by all reviewers")
                        return WebhookEventResult.IgnoredEvent
                    }
                }

                if (!trigger.mergeRequestTitleRegex.isNullOrBlank()) {
                    val regex = Regex(trigger.mergeRequestTitleRegex)
                    if (event is CodeReviewUpdatedWebhookEvent) {
                        if (!regex.matches(event.titleMod?.new.orEmpty())) {
                            LOGGER.info("Ignoring webhook for trigger ${trigger.id} because new title does not match filter")
                            return WebhookEventResult.IgnoredEvent
                        }

                        if (regex.matches(event.titleMod?.old.orEmpty())) {
                            LOGGER.info("Ignoring webhook for trigger ${trigger.id} because old title matched filter as well")
                            return WebhookEventResult.IgnoredEvent
                        }
                    }
                    if (!regex.matches(review.title)) {
                        LOGGER.warning("Got event for review with title that doesn't match filter for trigger ${trigger.id}")
                        return WebhookEventResult.UnexpectedEvent
                    }
                }
            }

            WebhookEventResult.RunBuild(
                SpaceWebhookTriggerCause(
                    spaceConnectionId = spaceConnection.id,
                    spaceUrl = spaceConnection.baseUrl,
                    projectKey = review.project.key,
                    repositoryName = review.branchPairs.firstOrNull()?.repository.orEmpty(),
                    triggerType = trigger.triggerType,
                    mergeRequest = MergeRequest(
                        projectKey = review.project.key,
                        id = review.id,
                        number = review.number,
                        title = review.title,
                        sourceBranch = review.branchPairs.firstOrNull()?.sourceBranchInfo?.head,
                        url = "${spaceConnection.baseUrl}/p/${review.project.key}/reviews/${review.number}"
                    )
                )
            )
        }
    }
}

private sealed class WebhookEventResult {
    class RunBuild(val cause: SpaceWebhookTriggerCause) : WebhookEventResult()
    data object UnexpectedEvent : WebhookEventResult()
    data object IgnoredEvent : WebhookEventResult()
}

private fun CodeReviewWebhookEvent.matchesTrigger(trigger: SpaceWebhookTrigger) =
    (meta?.method == "Created").logIfFalse("Event meta is ${meta?.method}, expected 'Created' for trigger ${trigger.id}") &&
            (!trigger.isMergeRequestApprovalsRequired).logIfFalse("Got event for review creation but trigger ${trigger.id} is set up to run only after getting all approvals")

private fun CodeReviewUpdatedWebhookEvent.matchesTrigger(trigger: SpaceWebhookTrigger): Boolean {
    if (titleMod == null) {
        LOGGER.warning("Got review updated event without title change for trigger ${trigger.id}")
        return false
    }

    if (trigger.mergeRequestTitleRegex.isNullOrBlank()) {
        LOGGER.warning("Got event for review title change but trigger ${trigger.id} does not has filter by review title applied")
        return false
    }

    return true
}

private fun CodeReviewParticipantWebhookEvent.matchesTrigger(trigger: SpaceWebhookTrigger) =
    (reviewerState != null).logIfFalse("Got CodeReviewParticipantWebhookEvent without reviewer state change for trigger ${trigger.id}") &&
            trigger.isMergeRequestApprovalsRequired.logIfFalse("Got CodeReviewParticipantWebhookEvent but trigger ${trigger.id} does not require reviewer approvals to run build")


private fun List<TriggeredItem>.findBySpaceWebhookId(webhookId: String) =
    firstNotNullOfOrNull { job ->
        job.triggers.values
            .filterIsInstance<SpaceWebhookTrigger>()
            .firstOrNull { it.spaceWebhookIds?.contains(webhookId) ?: false }
            ?.let { job to it }
    }

private fun List<TriggeredItem>.findByTriggerId(triggerId: String) =
    firstNotNullOfOrNull { job ->
        job.triggers.values
            .filterIsInstance<SpaceWebhookTrigger>()
            .firstOrNull { it.id == triggerId }
            ?.let { job to it }
    }

private suspend fun SpaceClient.fetchWebhookById(webhookId: String) =
    getRegisteredWebhooks().map { it.webhook }.firstOrNull { it.id == webhookId }

private fun Boolean.logIfFalse(message: String) = this.also {
    if (!it) LOGGER.warning(message)
}

private val ktorClientForSpace = ktorClientForSpace();

@OptIn(ExperimentalSpaceSdkApi::class)
private class RequestAdapter(private val request: StaplerRequest, private val response: StaplerResponse) : space.jetbrains.api.runtime.helpers.RequestAdapter {
    var responded = false
        private set

    override fun getHeader(headerName: String): String? {
        return request.getHeader(headerName)
    }

    override suspend fun receiveText(): String {
        @Suppress("BlockingMethodInNonBlockingContext")
        return request.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
    }

    override suspend fun respond(httpStatusCode: Int, body: String) {
        response.setStatus(httpStatusCode, body)
        responded = true
    }
}

private val LOGGER = Logger.getLogger(SpaceWebhookEndpoint::class.java.name)
