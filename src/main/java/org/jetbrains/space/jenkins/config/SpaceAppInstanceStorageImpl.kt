package org.jetbrains.space.jenkins.config

import hudson.ExtensionList
import hudson.security.ACL
import hudson.util.Secret
import io.ktor.http.*
import org.jetbrains.space.jenkins.verifyCreateSpaceConnectionRequest
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.helpers.SpaceAppInstanceStorage
import java.util.ArrayList
import java.util.logging.Logger

/**
 * Implements an interface provided by the Space SDK for managing connected Space instances in a multi-tenant application.
 * Fetches SpaceCode url and API credentials by client id from the plugin-wide configuration.
 * <br />
 * Updating and removing SpaceCode app instances in response to requests from SpaceCode is not supported,
 * those actions have to be performed manually in Jenkins configuration.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
class SpaceAppInstanceStorageImpl : SpaceAppInstanceStorage {

    override suspend fun loadAppInstance(clientId: String): SpaceAppInstance? {
        val configuration = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
        // Space app could represent a global Jenkins instance to SpaceCode organization connection
        return configuration.connections.firstOrNull { it.clientId == clientId }
            ?.let {
                SpaceAppInstance(
                    clientId = it.clientId
                        ?: error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it."),
                    clientSecret = it.clientSecret.plainText,
                    spaceServerUrl = it.baseUrl
                )
            }
            // or a Jenkins job to SpaceCode project connection
            ?: configuration.connections.firstNotNullOfOrNull { connection ->
                val projectConnection =
                    connection.projectConnectionsByJob?.values
                        ?.firstOrNull { it.clientId == clientId }
                        // or one of the branch sources (SpaceCode project+repo) in a Jenkins multibranch project
                        ?: connection.projectConnectionsByMultibranchFolder?.values?.flatten()
                            ?.firstOrNull { it.clientId == clientId }
                projectConnection?.let {
                    SpaceAppInstance(it.clientId, it.clientSecret.plainText, connection.baseUrl)
                }
            }
            ?: run {
                LOGGER.info("Loading SpaceCode app instance for client id = $clientId not found")
                return null
            }
    }

    override suspend fun removeAppInstance(clientId: String) {
        ACL.as2(ACL.SYSTEM2).use {
            ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).onSpaceAppUninstalled(clientId)
        }
    }

    override suspend fun saveAppInstance(appInstance: SpaceAppInstance, installationState: String?) {
        if (installationState == null || !verifyCreateSpaceConnectionRequest(installationState)) {
            LOGGER.warning("Request on SpaceCode application creation with invalid installation state")
            return
        }

        ACL.as2(ACL.SYSTEM2).use {
            val configuration = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
            val existing = configuration.connections.firstOrNull { it.clientId == appInstance.clientId }
            configuration.addConnection(
                SpaceConnection(
                    id = Url(appInstance.spaceServer.serverUrl).host,
                    baseUrl = appInstance.spaceServer.serverUrl,
                    clientId = appInstance.clientId,
                    clientSecret = Secret.fromString(appInstance.clientSecret),
                    projectConnectionsByJob = existing?.projectConnectionsByJob
                        ?: HashMap<String, SpaceProjectConnection>(),
                    projectConnectionsByMultibranchFolder = existing?.projectConnectionsByMultibranchFolder
                        ?: HashMap<String, ArrayList<SpaceProjectConnection>>()
                )
            )
        }
    }
}

private val LOGGER = Logger.getLogger(SpaceAppInstanceStorageImpl::class.java.name)
