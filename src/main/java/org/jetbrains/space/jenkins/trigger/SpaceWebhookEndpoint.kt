package org.jetbrains.space.jenkins.trigger

import groovy.json.JsonOutput
import hudson.ExtensionList
import hudson.model.CauseAction
import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import jenkins.model.Jenkins
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceAppInstanceStorageImpl
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionByClientId
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.StaplerResponse
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.helpers.ProcessingScope
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.helpers.processPayload
import space.jetbrains.api.runtime.ktorClientForSpace
import space.jetbrains.api.runtime.types.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.servlet.http.HttpServletResponse
import kotlin.text.Charsets

/**
 * Handles HTTP requests from Space (webhook callbacks or safe merge commands)
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
@OptIn(ExperimentalSpaceSdkApi::class)
fun SpaceWebhookEndpoint.doProcess(request: StaplerRequest, response: StaplerResponse) {
    val contentType = request.contentType
    if (contentType != null && !contentType.startsWith("application/json")) {
        LOGGER.warning(String.format("Invalid content type %s", contentType))
        response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Expected application/json content type")
        return
    }

    val requestAdapter = RequestAdapterImpl(request, response)
    runBlocking {
        // wrap request processing with the Space SDK function call that handles some common logic
        // like verifying request signature and deserializing the payload for us
        Space.processPayload(requestAdapter, ktorClientForSpace, SpaceAppInstanceStorageImpl()) { payload ->
            when (payload) {
                is WebhookRequestPayload ->
                    processWebhookCallback(payload)
                is SafeMergeCommandPayload ->
                    when (val command = payload.command) {
                        is SafeMergeCommand.Start ->
                            startSafeMerge(payload.clientId, command, requestAdapter)
                        is SafeMergeCommand.Stop ->
                            stopSafeMerge(payload.clientId, command, requestAdapter)
                        is SafeMergeCommand.FetchStatus ->
                            fetchSafeMergeStatus(payload.clientId, command, requestAdapter)
                    }
                else -> {
                    LOGGER.warning("Got payload of type " + payload.javaClass.getSimpleName())
                    return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                }
            }
        }
    }

    if (!requestAdapter.responded) {
        LOGGER.warning("Webhook processing resulted in no response, responding OK by default")
        response.status = HttpStatusCode.OK.value
    }
}

/**
 * Match received webhook callback with trigger in Jenkins
 * by comparing webhook id in the callback payload with the Space webhook id stored for each trigger
 * and trigger the build if conditions are satisfied
 */
@OptIn(ExperimentalSpaceSdkApi::class)
private suspend fun ProcessingScope.processWebhookCallback(payload: WebhookRequestPayload): SpaceHttpResponse {
    val allJobs = Jenkins.get().getAllItems(TriggeredItem::class.java)
    val (job, trigger) = allJobs.findBySpaceWebhookId(payload.webhookId)
        ?: run {
            // fallback to fetching and parsing webhook name
            clientWithClientCredentials().fetchWebhookById(payload.webhookId)
                ?.let { getTriggerId(it) }
                ?.let { allJobs.findByTriggerId(it) }
                .also {
                    if (it != null) {
                        it.second.ensureSpaceWebhook(it.first.fullDisplayName)
                    } else {
                        LOGGER.warning("Trigger found for webhook id = ${payload.webhookId} found by fallback to webhook name")
                    }
                }
        }
        ?: run {
            LOGGER.warning("No registered trigger found for webhook id = ${payload.webhookId}")
            return SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
        }

    val triggerItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job)
    if (triggerItem == null) {
        LOGGER.info("Cannot trigger item of type ${job::class.qualifiedName}")
        return SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
    }

    val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
        ?: error("Space plugin configuration cannot be resolved")

    val spaceConnection = spacePluginConfiguration.getConnectionByClientId(payload.clientId)
        ?: error("Space connection cannot be found for client id specified in webhook payload")

    // deep check of event properties and trigger conditions to ensure that build should be triggered
    val result = getTriggeredBuildCause(trigger, payload.payload, spaceConnection)
    return when (result) {
        is WebhookEventResult.RunBuild -> {
            val causeAction = CauseAction(result.cause)
            triggerItem.scheduleBuild2(triggerItem.quietPeriod, causeAction)
            SpaceHttpResponse.RespondWithOk
        }

        is WebhookEventResult.UnexpectedEvent -> {
            trigger.ensureSpaceWebhook(job.fullDisplayName)
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
        }

        is WebhookEventResult.IgnoredEvent ->
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.Accepted)
    }
}

/**
 * Space SDK requires consuming code to provide an implementation for the [RequestAdapterImpl] interface
 * that abstracts the specific HTTP client used by the consumer code away from the SDK.
 *
 * This implementation allows Space SDK to interact with the [Stapler] library to deal with the HTTP request and response.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
class RequestAdapterImpl(private val request: StaplerRequest, private val response: StaplerResponse) : space.jetbrains.api.runtime.helpers.RequestAdapter {
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
        response.setStatus(httpStatusCode)
        response.characterEncoding = Charsets.UTF_8.name
        response.writer.print(body)
        responded = true
    }
}

/**
 * Space SDK implementation for handling webhook callbacks makes a call to Space API
 * to fetch public key in order to verify the incoming request signature,
 * therefore it needs an instance of HTTP client to access Space API.
 *
 * @see <a href="https://www.jetbrains.com/help/space/verify-space-in-application.html#verifying-requests-using-a-public-key">Verifying requests from Space using a public key</a>
 */
private val ktorClientForSpace = ktorClientForSpace();

private fun List<TriggeredItem>.findBySpaceWebhookId(webhookId: String) =
    firstNotNullOfOrNull { job ->
        job.triggers.values
            .filterIsInstance<SpaceWebhookTrigger>()
            .firstOrNull { it.spaceWebhookId == webhookId }
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

/**
 * Represents the result of checking whether an incoming webhook callback
 * matches the trigger settings configured in Jenkins.
 */
private sealed class WebhookEventResult {
    /**
     * Event received from Space matches trigger conditions, build should be started
     */
    class RunBuild(val cause: SpaceWebhookTriggerCause) : WebhookEventResult()

    /**
     * Event received from Space was not expected to arrive according to trigger settings,
     * probably webhook settings in Space are out of sync with trigger settings in Jenkins.
     * Webhook in Space will be updated with the up-to-date settings when such event arrives.
     */
    data object UnexpectedEvent : WebhookEventResult()

    /**
     * Event received from Space was expected, but it shouldn't trigger a build in Jenkins
     * because trigger conditions weren't satisfied.
     * This happens, for example, when new commits are added to the merge request
     * but the trigger requires it to be approved by all reviewers and the approvals aren't there yet.
     */
    data object IgnoredEvent : WebhookEventResult()
}

/**
 * Checks whether an event received from Space matches the trigger conditions and thus whether a build must be triggered.
 * This is a deep check of all the properties of the event and conditions of the trigger.
 * The initial matching of trigger to Space webhook by the means of comparing the ids has already been done before.
 */
private fun getTriggeredBuildCause(trigger: SpaceWebhookTrigger, event: WebhookEvent, spaceConnection: SpaceConnection): WebhookEventResult {
    return when (val triggerType = trigger.triggerType) {
        SpaceWebhookTriggerType.Branches -> {
            if (event !is SRepoPushWebhookEvent) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event but expected ${SRepoPushWebhookEvent::class.simpleName} for the build trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            if (event.projectKey.key != trigger.projectKey || event.repository != trigger.repositoryName) {
                LOGGER.warning("Event project and repository do not match those configured for the build trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            if (event.deleted) {
                LOGGER.info("Got git push event for a deleted branch")
                return WebhookEventResult.IgnoredEvent
            }

            if (event.newCommitId == null) {
                LOGGER.warning("Got git push event for created or updated branch without new commit id")
                return WebhookEventResult.UnexpectedEvent
            }

            val commitsQuery = URLEncoder.encode("head:" + event.head, Charsets.UTF_8)
            WebhookEventResult.RunBuild(
                SpaceWebhookTriggerCause(
                    spaceConnectionId = spaceConnection.id,
                    spaceUrl = spaceConnection.baseUrl,
                    projectKey = event.projectKey.key,
                    repositoryName = event.repository,
                    triggerType = triggerType,
                    cause = TriggerCause.BranchPush(
                        head = event.head,
                        commitId = event.newCommitId!!,
                        url = "${spaceConnection.baseUrl}/p/${event.projectKey.key}/repositories/${event.repository}/commits?query=$commitsQuery"
                    )
                )
            )
        }

        SpaceWebhookTriggerType.MergeRequests -> {
            val (mergeRequest, isEventExpectedForTrigger) = when (event) {
                is CodeReviewWebhookEvent ->
                    event.review to event.isExpectedFor(trigger)

                is CodeReviewUpdatedWebhookEvent ->
                    event.review to event.isExpectedFor(trigger)

                is CodeReviewParticipantWebhookEvent ->
                    event.review to event.isExpectedFor(trigger)

                is CodeReviewCommitsUpdatedWebhookEvent ->
                    event.review to true

                else -> {
                    LOGGER.warning("Got unexpected ${event.javaClass.simpleName} event type instead of code review event for the build trigger ${trigger.id}")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            if (mergeRequest == null) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event without review for the build trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            if (mergeRequest !is MergeRequestRecord) {
                LOGGER.info("Got ${event.javaClass.simpleName} event for commit set review for the build trigger ${trigger.id}")
                return WebhookEventResult.IgnoredEvent
            }

            if (mergeRequest.project.key != trigger.projectKey || mergeRequest.branchPairs.firstOrNull()?.repository != trigger.repositoryName) {
                LOGGER.warning("Event project and repository do not match those configured for trigger ${trigger.id}")
                return WebhookEventResult.UnexpectedEvent
            }

            if (!isEventExpectedForTrigger) {
                // specific message should have been logged while checking the match
                return WebhookEventResult.UnexpectedEvent
            }

            if (trigger.triggerType == SpaceWebhookTriggerType.MergeRequests) {
                if (trigger.isMergeRequestApprovalsRequired) {
                    val reviewers = mergeRequest.participants?.filter { it.role == CodeReviewParticipantRole.Reviewer }.orEmpty()
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
                    if (!regex.matches(mergeRequest.title)) {
                        LOGGER.warning("Got event for review with title that doesn't match filter for trigger ${trigger.id}")
                        return WebhookEventResult.UnexpectedEvent
                    }
                }
            }

            WebhookEventResult.RunBuild(
                SpaceWebhookTriggerCause.fromMergeRequest(mergeRequest, spaceConnection)
            )
        }

        SpaceWebhookTriggerType.OnlySafeMerge ->
            WebhookEventResult.UnexpectedEvent
    }
}

private fun CodeReviewWebhookEvent.isExpectedFor(trigger: SpaceWebhookTrigger): Boolean {
    if (meta?.method != "Created") {
        LOGGER.warning("Event meta is ${meta?.method}, expected 'Created' for trigger ${trigger.id}")
        return false
    }

    if (trigger.isMergeRequestApprovalsRequired) {
        LOGGER.warning("Got event for review creation but trigger ${trigger.id} is set up to run only after getting all approvals")
        return false
    }

    return true
}

private fun CodeReviewUpdatedWebhookEvent.isExpectedFor(trigger: SpaceWebhookTrigger): Boolean {
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

private fun CodeReviewParticipantWebhookEvent.isExpectedFor(trigger: SpaceWebhookTrigger): Boolean {
    if (reviewerState == null) {
        LOGGER.warning("Got CodeReviewParticipantWebhookEvent without reviewer state change for trigger ${trigger.id}")
        return false
    }

    if (!trigger.isMergeRequestApprovalsRequired) {
        LOGGER.warning("Got CodeReviewParticipantWebhookEvent but trigger ${trigger.id} does not require reviewer approvals to run build")
        return false
    }

    return true
}

private val LOGGER = Logger.getLogger(SpaceWebhookEndpoint::class.java.name)
