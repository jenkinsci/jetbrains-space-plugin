package org.jetbrains.space.jenkins

import com.cloudbees.plugins.credentials.CredentialsProvider
import hudson.security.ACL
import jenkins.model.Jenkins
import org.jetbrains.space.jenkins.config.SpaceApiCredentials
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.helpers.SpaceAppInstanceStorage
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSpaceSdkApi::class)
@Singleton
class SpaceAppInstanceStorage : SpaceAppInstanceStorage {

    @Inject
    private lateinit var spacePluginConfiguration: SpacePluginConfiguration;

    override suspend fun loadAppInstance(clientId: String): SpaceAppInstance? {
        val creds = CredentialsProvider
            .lookupCredentialsInItemGroup(SpaceApiCredentials::class.java, Jenkins.get(), ACL.SYSTEM2)
            .firstOrNull { it.clientId == clientId }
            ?: return null

        val connection = spacePluginConfiguration.getConnections().firstOrNull { it.apiCredentialId == creds.id }
            ?: return null

        return SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, connection.baseUrl);
    }

    override suspend fun removeAppInstance(clientId: String) {
        TODO("Not yet implemented")
    }

    override suspend fun saveAppInstance(appInstance: SpaceAppInstance, installationState: String?) {
        TODO("Not yet implemented")
    }
}