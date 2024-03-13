package org.jetbrains.space.jenkins.config

import com.cloudbees.plugins.credentials.CredentialsProvider
import hudson.ExtensionList
import hudson.security.ACL
import jenkins.model.Jenkins
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.helpers.SpaceAppInstanceStorage
import java.util.logging.Logger

/**
 * Implements an interface provided by the Space SDK for managing connected Space instances in a multi-tenant application.
 * Fetches Space url and API credentials by client id from the plugin-wide configuration.
 *
 * Updating and removing Space app instances in response to requests from Space is not supported,
 * those actions have to be performed manually in Jenkins configuration.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
class SpaceAppInstanceStorageImpl : SpaceAppInstanceStorage {

    override suspend fun loadAppInstance(clientId: String): SpaceAppInstance? {
        val creds = CredentialsProvider
            .lookupCredentialsInItemGroup(SpaceApiCredentials::class.java, Jenkins.get(), ACL.SYSTEM2)
            .firstOrNull { it.clientId == clientId }
            ?: run {
                LOGGER.info("Loading Space app instance for client id = $clientId, not found")
                return null
            }

        val connection = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.firstOrNull { it.apiCredentialId == creds.id }
            ?: run {
                LOGGER.info("Loading Space app instance for client id = $clientId not found")
                return null
            }

        return SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, connection.baseUrl);
    }

    override suspend fun removeAppInstance(clientId: String) {
        LOGGER.warning("Space connection in Jenkins has to be removed manually. Got remove request for Space app instance with client id = $clientId")
    }

    override suspend fun saveAppInstance(appInstance: SpaceAppInstance, installationState: String?) {
        LOGGER.warning("Space connection in Jenkins has to be updated manually. Got update request for Space app instance with client id = ${appInstance.clientId}")
    }
}

private val LOGGER = Logger.getLogger(SpaceAppInstanceStorageImpl::class.java.name)
