package org.jetbrains.space.jenkins.config

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.UserRemoteConfig
import hudson.util.Secret
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.SpacePermissionsApproveListener
import org.jetbrains.space.jenkins.listeners.SpaceGitCheckoutParams
import org.jetbrains.space.jenkins.scm.REFS_HEADS_PREFIX
import org.jetbrains.space.jenkins.scm.SpaceSCM
import space.jetbrains.api.runtime.AuthenticationRequiredException
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.ApplicationIdentifier
import space.jetbrains.api.runtime.types.PermissionIdentifier
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Represents a project-level SpaceCode connection between a job, workflow or multibranch project's branch source in Jenkins and a project in SpaceCode.
 */
class SpaceProjectConnection(
    val spaceAppId: String,
    val projectKey: String,
    val clientId: String,
    val clientSecret: Secret
) {
    companion object {
        /**
         * Permissions that are required for the integration to function properly for both git branches and code reviews
         */
        val requiredPermissions = mapOf(
            PermissionIdentifier.ReportExternalStatusChecks to "Report external status checks",
            PermissionIdentifier.ViewCodeReviews to "View code reviews",
            PermissionIdentifier.ReadGitRepositories to "Read Git repositories"
        )

        /**
         * Permissions that are requested on integration setup but are optional, depending on what actions Jenkins workflows will perform in SpaceCode
         */
        val optionalPermissions = mapOf(
            PermissionIdentifier.PostCommentsToCodeReviews to "Post comments to code reviews",
            PermissionIdentifier.WriteGitRepositories to "Write Git repositories"
        )

        /**
         * Generates a name for the project-level SpaceCode application
         */
        fun spaceAppName(spaceProjectKey: String, jobFullName: String) =
            "$spaceProjectKey (Jenkins project \"${jobFullName}\")"
    }
}

/**
 * Constructs the SpaceCode API client instance for the given project-level connection.
 *
 * The caller is responsible for closing the client after use.
 */
fun SpaceProjectConnection.getApiClient(spaceUrl: String): SpaceClient {
    val appInstance = SpaceAppInstance(clientId, Secret.toString(clientSecret), spaceUrl)
    return SpaceClient(appInstance, SpaceAuth.ClientCredentials())
}

/**
 * Fetches git repository clone url from SpaceCode and constructs the config object required for the underlying Jenkins Git plugin
 * to fetch source code from the repository.
 */
fun SpaceGitCheckoutParams.getUserRemoteConfig(scm: SpaceSCM, branchToBuild: String?): UserRemoteConfig {
    val sshUrl = getGitCloneUrl()
    val refspec = listOfNotNull(
        scm.customSpaceRepository?.refspec,
        branchToBuild?.let {
            val local = if (it.startsWith(REFS_HEADS_PREFIX))
                "refs/remotes/$repositoryName/${it.removePrefix(REFS_HEADS_PREFIX)}"
            else
                it
            "$it:$local"
        }
    ).joinToString(" ")
    return UserRemoteConfig(sshUrl, repositoryName, refspec, connection.clientId)
}

/**
 * Fetches git repository clone url from SpaceCode
 */
fun SpaceGitCheckoutParams.getGitCloneUrl() =
    runBlocking {
        connection.getApiClient(baseUrl).use { spaceApiClient ->
            spaceApiClient.projects.repositories.url(ProjectIdentifier.Key(connection.projectKey), repositoryName) {
                sshUrl()
            }.sshUrl
        }
    }

/**
 * Uninstalls the project-level SpaceCode application.
 * Also removes the corresponding webhook subscription from the org-level application.
 */
fun SpaceProjectConnection.deleteProjectApplication(parentConnection: SpaceConnection) {
    runBlocking {
        try {
            getApiClient(parentConnection.baseUrl).use {
                it.applications.deleteApplication(ApplicationIdentifier.Me)
            }
            parentConnection.getApiClient().use {
                SpacePermissionsApproveListener.removeSpaceWebhookSubscription(it, spaceAppId)
            }
        } catch (ex: AuthenticationRequiredException) {
            LOGGER.info("Removing SpaceCode project-level connection - the SpaceCode application has already been deleted")
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, "Removing SpaceCode project-level connection - error while removing the SpaceCode application", ex)
        }
    }
}

/**
 * Deletes the SSH key credentials used by this project-level SpaceCode connection to fetch source code from the git repository.
 */
fun SpaceProjectConnection.deleteSshKeyCredentials() {
    val credentialsProvider = SystemCredentialsProvider.getInstance()
    credentialsProvider.credentials
        .filterIsInstance<BasicSSHUserPrivateKey>()
        .firstOrNull { it.scope == CredentialsScope.GLOBAL && it.id == clientId }
        ?.let {
            credentialsProvider.credentials.remove(it)
            credentialsProvider.save()
        }
}

private val LOGGER = Logger.getLogger(SpaceProjectConnection::class.java.name)
