package org.jetbrains.space.jenkins

import hudson.security.ACL
import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceAppInstanceStorageImpl
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.trigger.*
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.StaplerResponse
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.helpers.processPayload
import space.jetbrains.api.runtime.ktorClientForSpace
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.types.*
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.servlet.http.HttpServletResponse
import kotlin.text.Charsets

/**
 * Handles HTTP requests from SpaceCode (app installation, webhook callbacks or safe merge commands)
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
@OptIn(ExperimentalSpaceSdkApi::class)
fun SpacePayloadHandler.doProcess(request: StaplerRequest, response: StaplerResponse) {
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
        val spaceAppInstanceStorage = SpaceAppInstanceStorageImpl()
        Space.processPayload(requestAdapter, ktorClientForSpace, spaceAppInstanceStorage) { payload ->
            when (payload) {
                is InitPayload -> {
                    if (spaceAppInstanceStorage.loadAppInstance(payload.clientId) == null) {
                        // installation was rejected due to invalid state key
                        return@processPayload SpaceHttpResponse.RespondWithCode(400)
                    }

                    clientWithClientCredentials().apply {
                        applications.authorizations.authorizedRights.requestRights(
                            ApplicationIdentifier.Me,
                            GlobalPermissionContextIdentifier,
                            SpaceConnection.requiredPermissions.keys.toList()
                        )
                        applications.setUiExtensions(
                            GlobalPermissionContextIdentifier,
                            listOf(
                                GettingStartedUiExtensionIn(
                                    gettingStartedUrl = "${Jenkins.get().rootUrl}/manage/spacecode/appInstalled",
                                    gettingStartedTitle = "Back to Jenkins",
                                    openInNewTab = false
                                )
                            )
                        )

                        val app = applications.getApplication(ApplicationIdentifier.Me);
                        applications.webhooks.createWebhook(
                            ApplicationIdentifier.Me,
                            SpacePermissionsApproveListener.SpaceWebhookName,
                            "Auto-generated webhook to listen for application's rights authorization",
                            subscriptions = listOf(
                                SubscriptionDefinition(
                                    "Parent app authorizations",
                                    CustomGenericSubscriptionIn(
                                        "Application",
                                        listOf(ApplicationsSubscriptionFilterIn(app.id)),
                                        listOf("Application.Authorized")
                                    )
                                )
                            )
                        )
                    }
                    SpaceHttpResponse.RespondWithOk
                }

                is ApplicationUninstalledPayload ->
                    SpaceHttpResponse.RespondWithOk

                is WebhookRequestPayload ->
                    processWebhookCallback(payload)

                is SafeMergeCommandPayload ->
                    ACL.as2(ACL.SYSTEM2).use {
                        when (val command = payload.command) {
                            is SafeMergeCommand.Start ->
                                startSafeMerge(payload.clientId, command, requestAdapter)

                            is SafeMergeCommand.Stop ->
                                stopSafeMerge(payload.clientId, command, requestAdapter)

                            is SafeMergeCommand.FetchStatus ->
                                fetchSafeMergeStatus(payload.clientId, command, requestAdapter)
                        }
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
 * @see <a href="https://www.jetbrains.com/help/space/verify-space-in-application.html#verifying-requests-using-a-public-key">Verifying requests from SpaceCode using a public key</a>
 */
private val ktorClientForSpace = ktorClientForSpace();

private val LOGGER = Logger.getLogger(SpacePayloadHandler::class.java.name)
