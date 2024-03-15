package org.jetbrains.space.jenkins.config

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import hudson.model.Item
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.UserRemoteConfig
import hudson.security.ACL
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

fun SpaceConnection.getUserRemoteConfig(projectKey: String, repositoryName: String, branches: List<BranchSpec>): UserRemoteConfig {
    getApiClient().use { spaceApiClient ->
        val repoUrls = runBlocking {
            spaceApiClient.projects.repositories.url(ProjectIdentifier.Key(projectKey), repositoryName) {
                httpUrl()
                sshUrl()
            }
        }
        val refspec = branches.map {
            val remote = it.name
            val local = if (remote.startsWith("refs/heads/"))
                "refs/remotes/$repositoryName/${remote.removePrefix("refs/heads/")}"
            else
                it
            "+$remote:$local"
        }.joinToString(" ")

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

fun SpacePluginConfiguration.getConnectionById(id: String?): SpaceConnection? {
    return if (id != null && id.isNotBlank()) connections.firstOrNull { it.id == id } else null
}

fun checkPermissions(context: Item?) {
    if (context != null) {
        context.checkPermission(Item.EXTENDED_READ)
    } else {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER)
    }
}

private val LOGGER = Logger.getLogger(SpacePluginConfiguration::class.java.name)
