package org.jetbrains.space.jenkins.config

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import hudson.plugins.git.UserRemoteConfig
import hudson.security.ACL
import io.ktor.http.*
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
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

fun SpaceConnection.getUserRemoteConfig(projectKey: String, repositoryName: String, triggerCause: SpaceWebhookTriggerCause?): UserRemoteConfig {
    getApiClient().use { spaceApiClient ->
        val repoUrls = runBlocking {
            spaceApiClient.projects.repositories.url(ProjectIdentifier.Key(projectKey), repositoryName) {
                httpUrl()
                sshUrl()
            }
        }
        val refspec = triggerCause?.cause?.branchForCheckout?.let {
            val remote = it
            val local = if (it.startsWith("refs/heads/"))
                "refs/remotes/$repositoryName/${it.removePrefix("refs/heads/")}"
            else
                it
            "+$remote:$local"
        }

        return UserRemoteConfig(
            if (sshCredentialId.isBlank()) repoUrls.httpUrl else repoUrls.sshUrl,
            repositoryName,
            refspec,
            sshCredentialId.ifBlank { apiCredentialId }
        )
    }
}

fun SpacePluginConfiguration.getConnectionByClientId(clientId: String): SpaceConnection? {
    val creds = CredentialsProvider
        .lookupCredentialsInItemGroup(SpaceApiCredentials::class.java, Jenkins.get(), ACL.SYSTEM2)
        .firstOrNull { it.clientId == clientId }
        ?: return null

    return connections.firstOrNull { it.apiCredentialId == creds.id }
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

const val SPACE_LOGO_ICON = "icon-space-logo"

private val LOGGER = Logger.getLogger(SpacePluginConfiguration::class.java.name)
