package org.jetbrains.space.jenkins.trigger

import hudson.Extension
import hudson.model.CauseAction
import hudson.model.UnprotectedRootAction
import io.ktor.http.*
import jenkins.model.Jenkins
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceAppInstanceStorage
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.StaplerResponse
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.helpers.processPayload
import space.jetbrains.api.runtime.ktorClientForSpace
import space.jetbrains.api.runtime.types.WebhookRequestPayload
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.inject.Inject
import javax.servlet.http.HttpServletResponse

@Extension
class SpaceWebhookEndpoint : UnprotectedRootAction {

    @Inject
    private lateinit var spaceAppInstanceStorage: SpaceAppInstanceStorage

    override fun getUrlName() = URL

    @OptIn(ExperimentalSpaceSdkApi::class)
    @POST
    fun doTrigger(request: StaplerRequest, response: StaplerResponse) {
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

                val causeAction = CauseAction(SpaceWebhookTriggerCause(trigger.triggerType))
                NoIdeaFreeze.triggerBuild(triggerItem, causeAction)
                SpaceHttpResponse.RespondWithOk
            }
        }

        if (!requestAdapter.responded) {
            LOGGER.warning("Webhook processing resulted in no response, responding OK by default")
            response.status = HttpStatusCode.OK.value
        }
    }

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

    override fun getIconFileName() = null

    override fun getDisplayName() = null

    companion object {
        const val URL: String = "jb-space-webhook"
    }
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
