package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import hudson.model.Run
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import jenkins.branch.MultiBranchProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.*
import space.jetbrains.api.runtime.*
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [CallSpaceApiStep], which is responsible for sending a request to SpaceCode HTTP API and getting a response.
 */
class CallSpaceApiStepExecution(
    val jenkinsItemFullName: String,
    val spaceConnectionId: String,
    val spaceProjectKey: String?,
    val httpMethod: String,
    val requestUrl: String,
    val requestBody: String?,
    context: StepContext
) : StepExecution(context) {

    companion object {
        private final val serialVersionUID = 1L

        /**
         * Obtain SpaceCode connection from the build trigger or git checkout settings if necessary and start the step execution.
         */
        fun start(step: CallSpaceApiStep, context: StepContext) : StepExecution {
            val build = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val multiProjectScmSource = build.getParent().getMultiBranchSpaceScmSource()
            val spaceConnectionId = multiProjectScmSource?.spaceConnectionId
                ?: build.getParent().getSpaceConnectionId()
                ?: return FailureStepExecution("SpaceCode connection not found", context)

            val jenkinsItem = (build.getParent().getParent() as? MultiBranchProject<*,*>) ?: build.getParent()

            return CallSpaceApiStepExecution(
                jenkinsItemFullName = jenkinsItem.fullName,
                spaceConnectionId = spaceConnectionId,
                spaceProjectKey = multiProjectScmSource?.projectKey,
                httpMethod = step.httpMethod,
                requestUrl = step.requestUrl,
                requestBody = step.requestBody,
                context = context
            )
        }
    }

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        coroutineScope.launch {
            try {
                val (spaceConnection, spaceUrl) = getProjectConnection(jenkinsItemFullName, spaceConnectionId, spaceProjectKey)

                while (true) {
                    spaceConnection.getApiClient(spaceUrl).use { spaceApiClient ->
                        val response = spaceApiClient.ktorClient.request {
                            url {
                                takeFrom(spaceUrl.removeSuffix("/") + "/" + requestUrl.removePrefix("/"))
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
