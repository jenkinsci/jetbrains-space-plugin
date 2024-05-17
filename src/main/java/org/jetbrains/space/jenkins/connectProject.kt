package org.jetbrains.space.jenkins

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import hudson.ExtensionList
import hudson.model.Job
import hudson.model.TopLevelItem
import hudson.util.Secret
import io.ktor.http.*
import jenkins.branch.BranchSource
import jenkins.branch.MultiBranchProject
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.*
import org.jetbrains.space.jenkins.scm.SpaceSCMSource
import org.kohsuke.stapler.StaplerResponse
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.types.ApplicationIdentifier
import space.jetbrains.api.runtime.types.ProjectIdentifier
import space.jetbrains.api.runtime.types.ProjectPermissionContextIdentifier
import java.util.logging.Level
import java.util.logging.Logger


/**
 * Establishes a project-level connecton between a [TopLevelItem] (Job or MultiBranchProject) and a SpaceCode project:
 * <ul>
 *     <li>creating an application in SpaceCode (owned by the org-level application);</li>
 *     <li>saving the project connection to the {@link SpacePluginConfiguration} instance;</li>
 *     <li>configuring the parent Jenkins job or branch source to reference this connection.</li>
 * </ul>
 *
 * @param spaceConnectionId The ID of the org-level SpaceCode connection.
 * @param spaceProjectKey The key of the Space project.
 * @param spaceRepository The optional repository name. Required only for MultiBranchProject.
 * @param response The response object used to send error messages.
 */
fun TopLevelItem.connectProject(
    spaceConnectionId: String,
    spaceProjectKey: String,
    spaceRepository: String?,
    response: StaplerResponse
) {
    val parentConnection = getOrgConnection(spaceConnectionId)
        ?: run {
            response.sendError(404, "SpaceCode connection not configured")
            return
        }

    when (this) {
        is Job<*, *> ->
            connectJob(parentConnection, spaceProjectKey)

        is MultiBranchProject<*, *> -> {
            spaceRepository ?: run {
                response.sendError(400, "Repository is not specified")
                return
            }
            connectMultiBranchProject(parentConnection, spaceProjectKey, spaceRepository)
        }

        else ->
            response.sendError(400, "Job not found")
    }
}

private fun Job<*, *>.connectJob(parentConnection: SpaceConnection, spaceProjectKey: String): SpaceProjectConnection {
    val jobFullName = this.getFullName()
    val configuration = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
    if (parentConnection.projectConnectionsByJob == null)
        error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it.")

    this.getProperty(SpaceProjectConnectionJobProperty::class.java)?.let { prop ->
        configuration.connections.firstOrNull { it.id == prop.spaceConnectionId }?.let { oldConnection ->
            oldConnection.projectConnectionsByJob?.get(jobFullName)?.let { oldProjectConnection ->
                if (prop.spaceConnectionId == parentConnection.id && prop.projectKey == spaceProjectKey)
                    return oldProjectConnection

                try {
                    oldProjectConnection.deleteProjectApplication(oldConnection)
                    oldProjectConnection.deleteSshKeyCredentials()
                    oldConnection.projectConnectionsByJob.remove(jobFullName)
                } catch (ex: Exception) {
                    LOGGER.log(
                        Level.WARNING,
                        "Failed to clean up previous SpaceCode connection for the job",
                        ex
                    )
                }
            }
        }
    }

    val projectConnection = configuration.connectProject(parentConnection, spaceProjectKey, jobFullName)
    parentConnection.projectConnectionsByJob.put(jobFullName, projectConnection)
    configuration.save()

    removeProperty(SpaceProjectConnectionJobProperty::class.java)
    addProperty(SpaceProjectConnectionJobProperty(parentConnection.id, spaceProjectKey))
    save()
    return projectConnection
}

private fun MultiBranchProject<*, *>.connectMultiBranchProject(
    parentConnection: SpaceConnection,
    spaceProjectKey: String,
    spaceRepository: String
): SpaceProjectConnection {
    val projectFullName = this.getFullName()
    if (parentConnection.projectConnectionsByMultibranchFolder == null)
        error("This SpaceCode connection has been configured in the previous plugin version and is incompatible with the current version. Please delete and recreate it.")

    parentConnection.projectConnectionsByMultibranchFolder.get(projectFullName)?.removeAll { it.projectKey == spaceProjectKey }

    val configuration = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
    val projectConnection = configuration.connectProject(parentConnection, spaceProjectKey, projectFullName)

    parentConnection.projectConnectionsByMultibranchFolder
        .getOrPut(projectFullName, { ArrayList() })
        .add(projectConnection)
    configuration.save()

    if (!sources.map { it.source }.filterIsInstance<SpaceSCMSource>().any { it.spaceConnectionId == parentConnection.id && it.projectKey == spaceProjectKey }) {
        getSourcesList().add(BranchSource(SpaceSCMSource(parentConnection.id, spaceProjectKey, spaceRepository)))
        save()
    }

    return projectConnection
}

private fun SpacePluginConfiguration.connectProject(
    parentConnection: SpaceConnection,
    spaceProjectKey: String,
    jenkinsItemName: String
): SpaceProjectConnection {
    val (childApp, childAppInstance) = runBlocking {
        parentConnection.getApiClient().use { parentSpaceClient ->
            val spaceUrl = parentSpaceClient.appInstance.spaceServer.serverUrl
            val childAppName = SpaceProjectConnection.spaceAppName(spaceProjectKey, jenkinsItemName)
            val childApp = parentSpaceClient.applications.createApplication(
                name = childAppName,
                endpointUri = URLBuilder(Jenkins.get().rootUrl!!).apply {
                    appendPathSegments(SpacePayloadHandler.URL, "process")
                }.buildString()
            )
            val childClientSecret =
                parentSpaceClient.applications.clientSecret.getClientSecret(ApplicationIdentifier.Id(childApp.id))

            SpacePermissionsApproveListener.ensureSpaceWebhook(parentSpaceClient, childApp.id, childAppName)

            childApp to SpaceAppInstance(childApp.clientId, childClientSecret, spaceUrl)
        }.also { (app, instance) ->
            val sshKey = SSHKeyGenerator.generateKeyPair()

            SpaceClient(instance, SpaceAuth.ClientCredentials()).use { childSpaceClient ->
                childSpaceClient.applications.authorizations.authorizedRights.requestRights(
                    ApplicationIdentifier.Me,
                    ProjectPermissionContextIdentifier(ProjectIdentifier.Key(spaceProjectKey)),
                    SpaceProjectConnection.run { requiredPermissions + optionalPermissions }.keys.toList()
                )
                childSpaceClient.applications.sshKeys.addSshKey(
                    ApplicationIdentifier.Me,
                    SSHKeyGenerator.getPublicKeyString(sshKey.public),
                    "SSH key for Jenkins project \"$jenkinsItemName\""
                )
            }

            val privateKey = SSHKeyGenerator.getPrivateKeyString(sshKey.private)
            val credentialsProvider = SystemCredentialsProvider.getInstance()
            credentialsProvider.credentials.add(
                BasicSSHUserPrivateKey(
                    CredentialsScope.GLOBAL,
                    app.clientId,
                    app.name,
                    BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey),
                    instance.clientSecret,
                    null
                )
            )
            credentialsProvider.save()
        }
    }

    return SpaceProjectConnection(
        spaceAppId = childApp.id,
        projectKey = spaceProjectKey,
        clientId = childAppInstance.clientId,
        clientSecret = Secret.fromString(childAppInstance.clientSecret)
    )
}

private val LOGGER = Logger.getLogger("connectProject")
