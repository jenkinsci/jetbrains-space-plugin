package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Run
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import space.jetbrains.api.runtime.*
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [CallSpaceApiStep], which is responsible for sending a request to Space HTTP API and getting a response.
 */
class CallSpaceApiStepExecution(
    val httpMethod: String,
    val requestUrl: String,
    val requestBody: String?,
    val spaceConnectionId: String,
    context: StepContext
) : StepExecution(context) {

    companion object {
        private final val serialVersionUID = 1L

        /**
         * Obtain Space connection from the build trigger or git checkout settings if necessary and start the step execution.
         */
        fun start(step: CallSpaceApiStep, context: StepContext, spacePluginConfiguration: SpacePluginConfiguration) : StepExecution {
            val build = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val triggerCause = build.getAction(CauseAction::class.java)?.findCause(SpaceWebhookTriggerCause::class.java)
            val checkoutAction = build.getAction(SpaceGitScmCheckoutAction::class.java)

            val connection = when {
                step.spaceConnectionId != null || step.spaceConnection != null -> {
                    spacePluginConfiguration.getConnectionByIdOrName(step.spaceConnectionId, step.spaceConnection)
                        ?: return FailureStepExecution(
                            "No Space connection found by specified id or name",
                            context
                        )
                }

                triggerCause != null -> {
                    spacePluginConfiguration.getConnectionById(triggerCause.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for the Space webhook call that triggered the build",
                            context
                        )
                }

                checkoutAction != null -> {
                    spacePluginConfiguration.getConnectionById(checkoutAction.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for checkout action",
                            context
                        )
                }

                else ->
                    null
            }

            if (connection == null) {
                return FailureStepExecution(
                    "Space connection cannot be inferred from the build trigger or git checkout settings and is not specified explicitly in the workflow step",
                    context
                )
            }

            return CallSpaceApiStepExecution(
                httpMethod = step.httpMethod,
                requestUrl = step.requestUrl,
                requestBody = step.requestBody,
                spaceConnectionId = connection.id,
                context = context
            )
        }
    }

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
            ?: error("Space plugin configuration cannot be resolved")

        coroutineScope.launch {
            try {
                val spaceConnection = spacePluginConfiguration.getConnectionById(spaceConnectionId)
                    ?: error("No Space connection found by specified id")

                while (true) {
                    spaceConnection.getApiClient().use { spaceApiClient ->
                        val response = spaceApiClient.ktorClient.request {
                            url {
                                takeFrom(spaceConnection.baseUrl.removeSuffix("/") + "/" + requestUrl.removePrefix("/"))
                            }
                            method = HttpMethod.parse(httpMethod)
                            accept(ContentType.Application.Json)

                            spaceApiClient.auth.token(spaceApiClient.ktorClient, spaceApiClient.appInstance).accessToken.takeIf { it.isNotEmpty() }?.let {
                                header(HttpHeaders.Authorization, "Bearer $it")
                            }

                            requestBody?.let {
                                setBody(TextContent(it, ContentType.Application.Json))
                            }
                        }
                        val responseText = response.bodyAsText()
                        val content = responseText.let(::parseJson)
                        val retry = throwErrorOrReturnWhetherToRetry(response, content, stepName = "callSpaceApiStep")
                        if (!retry) {
                            context.onSuccess(content)
                            return@launch
                        }
                    }
                }
            } catch (ex: Throwable) {
                context.onFailure(ex)
            }
        }

        return false
    }
}

/**
 *
 */
private fun throwErrorOrReturnWhetherToRetry(
    response: HttpResponse,
    responseContent: JsonValue?,
    stepName: String,
): Boolean {
    if (!response.status.isSuccess()) {
        val errorDescription = responseContent?.getFieldOrNull("error_description")?.asStringOrNull()
        throw when (responseContent?.getFieldOrNull("error")?.asStringOrNull()) {
            ErrorCodes.VALIDATION_ERROR -> ValidationException(errorDescription, response, stepName)
            ErrorCodes.AUTHENTICATION_REQUIRED -> when (errorDescription) {
                "Access token has expired" -> return true
                "Refresh token associated with the access token is revoked" ->
                    RefreshTokenRevokedException(errorDescription, response, stepName)

                else -> AuthenticationRequiredException(errorDescription, response, stepName)
            }

            ErrorCodes.PERMISSION_DENIED -> PermissionDeniedException(errorDescription, response, stepName)
            ErrorCodes.DUPLICATED_ENTITY -> DuplicatedEntityException(errorDescription, response, stepName)
            ErrorCodes.REQUEST_ERROR -> RequestException(errorDescription, response, stepName)
            ErrorCodes.NOT_FOUND -> NotFoundException(errorDescription, response, stepName)
            ErrorCodes.RATE_LIMITED -> RateLimitedException(errorDescription, response, stepName)
            ErrorCodes.PAYLOAD_TOO_LARGE -> PayloadTooLargeException(errorDescription, response, stepName)
            ErrorCodes.INTERNAL_SERVER_ERROR -> InternalServerErrorException(errorDescription, response, stepName)
            else -> when (response.status) {
                HttpStatusCode.BadRequest -> RequestException(
                    HttpStatusCode.BadRequest.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.Unauthorized -> AuthenticationRequiredException(
                    HttpStatusCode.BadRequest.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.Forbidden -> PermissionDeniedException(
                    HttpStatusCode.Forbidden.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.NotFound -> NotFoundException(
                    HttpStatusCode.NotFound.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.TooManyRequests -> RateLimitedException(
                    HttpStatusCode.TooManyRequests.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.PayloadTooLarge -> PayloadTooLargeException(
                    HttpStatusCode.PayloadTooLarge.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                HttpStatusCode.InternalServerError -> InternalServerErrorException(
                    HttpStatusCode.InternalServerError.description + errorDescription?.let { ": $it" }.orEmpty(),
                    response,
                    stepName
                )

                else -> IOException("${response.request.method.value} request to ${response.request.url.encodedPath} failed (calling $stepName)")
            }
        }
    }
    return false
}

private fun JsonValue.getFieldOrNull(key: String): JsonValue? {
    return this[key]
}
