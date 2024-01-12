package org.jetbrains.space.jenkins.config

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import hudson.plugins.git.UserRemoteConfig
import hudson.security.ACL
import io.ktor.http.*
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.util.logging.Logger


fun testSpaceApiConnection(baseUrl: String, apiCredentialId: String) {
    val creds = CredentialsMatchers.firstOrNull(
        CredentialsProvider.lookupCredentialsInItemGroup(
            SpaceApiCredentials::class.java,
            Jenkins.get(),
            ACL.SYSTEM2,
            URIRequirementBuilder.fromUri(baseUrl).build()
        ),
        CredentialsMatchers.withId(apiCredentialId)
    ) ?: error("No credentials found for credentialsId: $apiCredentialId")

    val appInstance = SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, baseUrl)
    runBlocking {
        SpaceClient(appInstance, SpaceAuth.ClientCredentials()).use { spaceClient ->
            spaceClient.applications.reportApplicationAsHealthy()
        }
    }
}

fun SpaceConnection.getApiClient(): SpaceClient {
    val creds = CredentialsMatchers
        .firstOrNull(
            CredentialsProvider.lookupCredentialsInItemGroup(
                SpaceApiCredentials::class.java,
                Jenkins.get(),
                ACL.SYSTEM2,
                URIRequirementBuilder.fromUri(baseUrl).build()
            ),
            CredentialsMatchers.withId(apiCredentialId)
        )
        ?: throw IllegalStateException("No credentials found for credentialsId: $apiCredentialId")

    val appInstance = SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, baseUrl)
    return SpaceClient(appInstance, SpaceAuth.ClientCredentials())
}

fun SpaceConnection.getUserRemoteConfig(projectKey: String, repositoryName: String): UserRemoteConfig {
    getApiClient().use { spaceApiClient ->
        val repoUrls = runBlocking {
            spaceApiClient.projects.repositories.url(ProjectIdentifier.Key(projectKey), repositoryName) {
                httpUrl()
                sshUrl()
            }
        }
        return UserRemoteConfig(
            if (sshCredentialId.isBlank()) repoUrls.httpUrl else repoUrls.sshUrl,
            repositoryName,
            null,
            sshCredentialId.ifBlank { apiCredentialId }
        )
    }
}

fun SpacePluginConfiguration.getConnectionById(id: String): SpaceConnection? {
    return if (id.isNotBlank()) connections.firstOrNull { it.id == id } else null
}

fun SpacePluginConfiguration.getConnectionByIdOrName(id: String?, name: String?): SpaceConnection? {
    return when {
        !id.isNullOrBlank() ->
            connections.firstOrNull { it.id == id }
        !name.isNullOrBlank() ->
            connections.firstOrNull { it.name == name }
        else ->
            null
    }
}

fun SpacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(gitCloneUrl: String): SpaceRepository? {
    val url = try {
        Url(gitCloneUrl)
    } catch (e: URLParserException) {
        LOGGER.warning("Malformed git SCM url - $gitCloneUrl");
        return null;
    }

    if (!url.host.startsWith("git.")) {
        return null;
    }

    val (baseUrlHostname, projectKey, repositoryName) =
        if (url.host.endsWith(".jetbrains.space")) {
            // cloud space, path contains org name, project key and repo name
            if (url.pathSegments.size < 3) {
                return null
            }
            Triple(url.pathSegments[0] + ".jetbrains.space", url.pathSegments[1], url.pathSegments[2])
        } else {
            // path contains project key and repo name
            if (url.pathSegments.size < 2) {
                return null
            }
            Triple(url.host.substring("git.".length), url.pathSegments[0], url.pathSegments[1])
        }

    return connections
        .firstOrNull {
            try {
                baseUrlHostname == Url(it.baseUrl).host
            } catch (e: URLParserException) {
                LOGGER.warning("Malformed Space connection base url - ${it.baseUrl}")
                false
            }
        }
        ?.let { SpaceRepository(it, projectKey, repositoryName) }
}

private val LOGGER = Logger.getLogger(SpacePluginConfiguration::class.java.name)
