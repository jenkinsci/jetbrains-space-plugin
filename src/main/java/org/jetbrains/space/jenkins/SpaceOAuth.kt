package org.jetbrains.space.jenkins

import hudson.ExtensionList
import hudson.util.Secret
import io.ktor.http.*
import io.ktor.util.collections.*
import jenkins.model.Jenkins
import kotlinx.coroutines.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.runtime.*
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.GlobalPermissionContextIdentifier
import space.jetbrains.api.runtime.types.PermissionIdentifier
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.io.Serializable
import java.util.*
import java.util.logging.Logger
import kotlin.time.Duration.Companion.minutes

/**
 * Represents OAuth flow session with SpaceCode to obtain the token to fetch list of projects from SpaceCode on behalf of the current user.
 */
public class SpaceOAuthSession(val connectionId: String, val token: Secret?) : Serializable

/**
 * Builds the SpaceCode URL to start OAuth flow
 */
public fun getSpaceOAuthUrl(oAuthSessionId: String, connectionId: String, withVcsReadScope: Boolean): String? {
    val connection = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.firstOrNull { it.id == connectionId }
        ?: run {
            LOGGER.info("SpaceCode app instance for id = $connectionId not found")
            return null;
        }

    val scope = PermissionScope.build(
        *listOfNotNull(
            PermissionIdentifier.ViewProjectDetails,
            PermissionIdentifier.ReadGitRepositories.takeIf { withVcsReadScope }
        ).map { PermissionScopeElement(GlobalPermissionContextIdentifier, it) }.toTypedArray()
    )

    return URLBuilder(connection.baseUrl).apply {
        path("/oauth/auth")
        parameters.apply {
            append("response_type", "code")
            append("state", oAuthSessionId)
            append("redirect_uri", Jenkins.get().spaceOAuthCompleteUrl())
            append("request_credentials", "default")
            append("client_id",
                connection.clientId
                ?: error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it."))
            append("scope", scope.toString())
            append("access_type", "online")
        }
    }.buildString()
}

/**
 * Calls into SpaceCode API to exchange the code for the access token once OAuth flow is complete.
 */
public fun exchangeCodeForToken(connectionId: String, code: String): String? {
    val connection = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.firstOrNull { it.id == connectionId }
        ?: error("SpaceCode app instance for id = $connectionId not found")

    val tokenInfo = runBlocking {
        connection.getApiClient().use { spaceClient ->
            Space.exchangeAuthCodeForToken(spaceClient.ktorClient, spaceClient.appInstance, code, Jenkins.get().spaceOAuthCompleteUrl())
        }
    }
    return tokenInfo.accessToken
}

/**
 * Fetches the list of projects from SpaceCode on behalf of the current user
 * to populate the dropdown list of projects for establishing project-level connection in Jenkins job or multibranch project's branch source.
 * <br />
 * SpaceCode access token should be already present in the OAuth session (the OAuth flow should complete by the moment of this call).
 */
public fun fetchSpaceProjects(session: SpaceOAuthSession): List<SpaceProject> {
    val connection =
        ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.firstOrNull { it.id == session.connectionId }
            ?: error("SpaceCode app instance for id = ${session.connectionId} not found")

    session.token ?: error("No token for SpaceCode app instance for id = ${session.connectionId}")

    val appInstance = SpaceAppInstance(
        clientId = connection.clientId
            ?: error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it."),
        clientSecret = Secret.toString(connection.clientSecret), spaceServerUrl = connection.baseUrl
    )
    val projects = runBlocking {
        SpaceClient(appInstance, SpaceAuth.Token(Secret.toString(session.token))).use { spaceClient ->
            spaceClient.projects.getAllProjects {
                key { key() }
                name()
                icon()
            }
        }
    }
    return projects.data.map {
        SpaceProject(
            key = it.key.key,
            name = it.name,
            iconUrl = it.icon?.let {
                URLBuilder(connection.baseUrl).apply {
                    pathSegments = listOf("d", it)
                    parameters.append("f", "0")
                }.buildString()
            }
        )
    }
}

/**
 * Fetches the list of repositories for a given project from SpaceCode on behalf of the current user
 * to populate the dropdown list of repositories for configuring Jenkins multibranch project's branch source.
 * <br />
 * SpaceCode access token should be already present in the OAuth session (the OAuth flow should complete by the moment of this call).
 */
public fun fetchSpaceRepositories(session: SpaceOAuthSession, projectKey: String): List<String> {
    val connection =
        ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.firstOrNull { it.id == session.connectionId }
            ?: error("SpaceCode app instance for id = ${session.connectionId} not found")

    session.token ?: error("No token for SpaceCode app instance for id = ${session.connectionId}")

    val appInstance = SpaceAppInstance(
        clientId = connection.clientId
            ?: error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it."),
        clientSecret = Secret.toString(connection.clientSecret),
        spaceServerUrl = connection.baseUrl
    )
    return runBlocking {
        SpaceClient(appInstance, SpaceAuth.Token(Secret.toString(session.token))).use { spaceClient ->
            spaceClient.projects.getProject(ProjectIdentifier.Key(projectKey)) {
                repos {
                    name()
                }
            }
        }.repos.map { it.name }
    }
}

/**
 * URL pointing to the static HTML resource on Jenkins side that is used to complete the OAuth flow happening in the dialog window
 * and notify the parent window that it can proceed with exchanging code for access token and fetching projects list from SpaceCode.
 */
public fun Jenkins.spaceOAuthCompleteUrl() =
    Jenkins.get().rootUrl
        ?.let { "${it.trimEnd('/')}/jb-spacecode-oauth/oauthComplete" }
        ?: throw IllegalStateException("Jenkins instance has no root url specified")

/**
 * Builds SpaceCode entrypoint URL for creating a new org-level SpaceCode application.
 */
@OptIn(DelicateCoroutinesApi::class)
fun buildCreateRootSpaceAppUrl() =
    URLBuilder("https://www.jetbrains.com/space/app/install-app").apply {
        parameters.apply {
            append("name", "Jenkins")
            append("endpoint",
                URLBuilder(Jenkins.get().rootUrl!!).apply {
                    appendPathSegments(SpacePayloadHandler.URL, "process")
                }.buildString()
            )
            append("state", UUID.randomUUID().toString().also {
                // give 5 minutes window between opening SpaceCode entrypoint and receiving install payload from SpaceCode
                // there might be more than one call in case something goes wrong during installation and user performs a retry
                createSpaceConnectionRequests.add(it)
                GlobalScope.launch {
                    delay(5.minutes)
                    createSpaceConnectionRequests.remove(it)
                }
            })
            append("code-flow-enabled", "true")
            append("code-flow-redirect-uris",
                URLBuilder(Jenkins.get().rootUrl!!).apply {
                    appendPathSegments(SpaceOAuthEndpointsHandler.URL, "oauthComplete")
                }.buildString()
            )
        }
    }.buildString()

/**
 * Verifies that the [InitPayload] received from SpaceCode corresponds to the org-level connection creation process that is currently in progress.
 */
fun verifyCreateSpaceConnectionRequest(state: String) =
    createSpaceConnectionRequests.contains(state)

private val createSpaceConnectionRequests = ConcurrentSet<String>()

/**
 * SpaceCode project model for the projects list passed to the React app in the browser.
 */
public data class SpaceProject(val key: String, val name: String, val iconUrl: String?)


private val LOGGER = Logger.getLogger("SpaceOAuth")
