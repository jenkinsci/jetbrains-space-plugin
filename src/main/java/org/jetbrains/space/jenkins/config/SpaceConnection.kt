package org.jetbrains.space.jenkins.config

import hudson.model.Job
import hudson.security.ACL
import hudson.util.Secret
import io.ktor.http.*
import jenkins.branch.MultiBranchProject
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.apache.tools.ant.types.LogLevel
import org.jetbrains.space.jenkins.scm.SpaceSCMSource
import space.jetbrains.api.runtime.AuthenticationRequiredException
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.permissions
import space.jetbrains.api.runtime.types.*
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Represents a connection to a Space organization.
 *
 * @property id The unique identifier of the connection.
 * @property baseUrl The base URL of the Space organization.
 * @property clientId The client ID used for authentication.
 * @property clientSecret The client secret used for authentication.
 * @property projectConnectionsByJob A map containing child connections of this org-level connection, where the key is the job name and the value is the project connection.
 * @property projectConnectionsByMultibranchFolder A map containing child connections of this org-level connection, where the first key is the folder name and the second key is the
 *  source ID.
 */
class SpaceConnection(
    val id: String,
    val baseUrl: String,
    val clientId: String?,
    val clientSecret: Secret,

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    val projectConnectionsByJob: java.util.HashMap<String, SpaceProjectConnection>?,

    // first key is folder name, second key is source id
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    val projectConnectionsByMultibranchFolder: java.util.HashMap<String, java.util.ArrayList<SpaceProjectConnection>>?,
) {
    /**
     * Called when a job, workflow or multibranch project is renamed in Jenkins.
     * Reflects the name change in the hashmaps with child connections.
     */
    fun onItemRenamed(oldFullName: String, newFullName: String) {
        projectConnectionsByJob?.get(oldFullName)?.let { projectConnection ->
            projectConnectionsByJob.remove(oldFullName)
            projectConnectionsByJob.put(newFullName, projectConnection)

            runBlocking {
                projectConnection.getApiClient(baseUrl).use { spaceClient ->
                    spaceClient.applications.updateApplication(
                        ApplicationIdentifier.Me,
                        name = SpaceProjectConnection.spaceAppName(projectConnection.projectKey, newFullName)
                    )
                }
            }
        }

        projectConnectionsByMultibranchFolder?.get(oldFullName)?.let { entry ->
            projectConnectionsByMultibranchFolder.remove(oldFullName)
            projectConnectionsByMultibranchFolder.put(newFullName, entry)

            runBlocking {
                entry.forEach { projectConnection ->
                    projectConnection.getApiClient(baseUrl).use { spaceClient ->
                        spaceClient.applications.updateApplication(
                            ApplicationIdentifier.Me,
                            name = SpaceProjectConnection.spaceAppName(projectConnection.projectKey, newFullName)
                        )
                    }
                }
            }
        }
    }

    /**
     * Called when a job, workflow or multibranch project is removed.
     * Cleans up the child project-level connections, ssh credentials for this job/project
     * and requests SpaceCode to uninstall the corresponding applications.
     */
    fun onItemDeleted(fullName: String) {
        projectConnectionsByJob?.get(fullName)?.let { projectConnection ->
            projectConnectionsByJob.remove(fullName)
            projectConnection.deleteProjectApplication(this)
            projectConnection.deleteSshKeyCredentials()
        }
        projectConnectionsByMultibranchFolder?.get(fullName)?.let { entry ->
            projectConnectionsByMultibranchFolder.remove(fullName)
            entry.forEach { projectConnection ->
                projectConnection.deleteProjectApplication(this)
                projectConnection.deleteSshKeyCredentials()
            }
        }
    }

    /**
     * Called when SpaceCode notifies Jenkins about the uninstall of an application that supports the integration.
     * Cleans up the corresponding connection entries on Jenkins side and removes unused SSH credentials
     */
    fun onSpaceAppUninstalled(clientId: String) {
        projectConnectionsByJob?.entries?.firstOrNull { it.value.clientId == clientId }?.let { entry ->
            projectConnectionsByJob.remove(entry.key)
            ACL.as2(ACL.SYSTEM2).use {
                (Jenkins.get().getItem(entry.key) as? Job<*, *>)?.let { job ->
                    job.removeProperty(SpaceProjectConnectionJobProperty::class.java)
                    job.save()
                }
                entry.value.deleteSshKeyCredentials()
            }
        }

        projectConnectionsByMultibranchFolder?.entries?.forEach { entry ->
            entry.value.firstOrNull { it.clientId == clientId }?.let { projectConnection ->
                ACL.as2(ACL.SYSTEM2).use {
                    (Jenkins.get().getItem(entry.key) as? MultiBranchProject<*, *>)?.let { project ->
                        project.setSourcesList(project.getSources().filter {
                            val spaceScmSource = (it.source as? SpaceSCMSource) ?: return@filter true
                            projectConnection.clientId != clientId || projectConnection.projectKey != spaceScmSource.projectKey
                        })
                    }
                }
                projectConnection.deleteSshKeyCredentials()
            }
        }
    }

    companion object {
        val requiredPermissions = mapOf<PermissionIdentifier, String>(
            PermissionIdentifier.CreateApplications to "Create applications"
        )
    }
}

fun SpaceConnection.getApiClient(): SpaceClient {
    if (clientId == null)
        error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it.")

    val appInstance = SpaceAppInstance(clientId, Secret.toString(clientSecret), baseUrl)
    return SpaceClient(appInstance, SpaceAuth.ClientCredentials())
}

/**
 * Uninstalls the org-level SpaceCode application and cleans up all the child project-level connections and SSH credentials
 */
fun SpaceConnection.deleteApplication() {
    runBlocking {
        try {
            getApiClient().use { it.applications.deleteApplication(ApplicationIdentifier.Me) }
        } catch (ex: AuthenticationRequiredException) {
            LOGGER.info("Removing SpaceCode connection - the SpaceCode application has already been deleted")
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, "Removing SpaceCode connection - error while removing the SpaceCode application", ex)
        }
    }

    ACL.as2(ACL.SYSTEM2).use {
        projectConnectionsByJob?.entries?.forEach { (jobName, projectConnection) ->
            (Jenkins.get().getItem(jobName) as? Job<*, *>)?.let { job ->
                job.removeProperty(SpaceProjectConnectionJobProperty::class.java)
                job.save()
            }
            projectConnection.deleteSshKeyCredentials()
        }
        projectConnectionsByMultibranchFolder?.entries?.forEach { (projectName, projectConnections) ->
            (Jenkins.get().getItem(projectName) as? MultiBranchProject<*, *>)?.let { project ->
                project.setSourcesList(project.getSources().filter {
                    val spaceScmSource = (it.source as? SpaceSCMSource) ?: return@filter true
                    spaceScmSource.spaceConnectionId != id
                })
            }
            projectConnections.forEach {
                it.deleteSshKeyCredentials()
            }
        }
    }
}

/**
 * Fetches the current state of the SpaceCode application with its permissions from SpaceCode.
 */
fun SpaceConnection.fetchSpaceApp(): SpaceAppRequestResult {
    return runBlocking {
        try {
            getApiClient().use { spaceClient ->
                spaceClient.fetchSpaceAppInfo(
                    permissionContextIdentifier = GlobalPermissionContextIdentifier,
                    requiredPermissions = SpaceConnection.requiredPermissions,
                    managePermissionsUrl = { app ->
                        URLBuilder(baseUrl).apply {
                            appendPathSegments("manage", "integrations", "jenkins")
                            parameters.apply {
                                append("name", app.name)
                                append("app", app.id)
                            }
                        }.buildString()
                    }
                )
            }
        } catch (ex: Exception) {
            LOGGER.log(Level.WARNING, "Error while fetching SpaceCode application info", ex)
            SpaceAppRequestError(statusCode = 500, message = ex.message ?: ex.javaClass.simpleName, clientId = clientId)
        }
    }
}

/**
 * Fetches the current state of the SpaceCode application with its permissions from SpaceCode.
 */
suspend fun SpaceClient.fetchSpaceAppInfo(
    permissionContextIdentifier: PermissionContextIdentifier,
    requiredPermissions: Map<PermissionIdentifier, String>,
    managePermissionsUrl: (ES_App) -> String
) =
    kotlin.runCatching {
        val app = applications.getApplication(ApplicationIdentifier.Me) {
            id()
            name()
            clientId()
            ownerApp {
                id()
                name()
            }
        }
        val permissions = applications.authorizations.authorizedRights
            .getAllAuthorizedRights(ApplicationIdentifier.Me, permissionContextIdentifier) {
                rightCode()
                status()
                name()
            }

        val grantedPermissions = permissions.filter { it.status == RightStatus.GRANTED }
        val missingPermissions = requiredPermissions.filterNot { p ->
            grantedPermissions.any { it.rightCode == p.key }
        }

        SpaceAppInfo(
            appId = app.id,
            appName = app.name,
            appClientId = app.clientId,
            managePermissionsUrl = managePermissionsUrl(app),
            permissions = grantedPermissions.map { it.name },
            missingPermissions = missingPermissions.entries.map { it.value }
        )
    }.getOrElse { ex ->
        LOGGER.log(Level.WARNING, "Cannot fetch SpaceCode application or its permissions", ex)
        SpaceAppRequestError(
            statusCode = 500,
            message = ex.message ?: ex.javaClass.name,
            clientId = appInstance.clientId
        )
    }

private val LOGGER = Logger.getLogger(SpaceConnection::class.java.name)
