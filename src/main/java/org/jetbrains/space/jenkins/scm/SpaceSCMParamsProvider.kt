package org.jetbrains.space.jenkins.scm

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.ExtensionList
import hudson.model.Item
import hudson.util.ListBoxModel
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import space.jetbrains.api.runtime.resources.projects
import java.net.HttpURLConnection
import java.util.logging.Level
import java.util.logging.Logger

@SuppressFBWarnings("NP_NONNULL_RETURN_VIOLATION")
// methods in this class are non-routable
@SuppressWarnings("lgtm[jenkins/csrf]")
object SpaceSCMParamsProvider {

    fun doFillSpaceConnectionItems(context: Item?): ListBoxModel {
        if (context != null) {
            context.checkPermission(Item.EXTENDED_READ)
        } else {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER)
        }
        return ListBoxModel(ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java).connections.map { ListBoxModel.Option(it.id) })
    }

    fun doFillProjectKeyItems(context: Item?, spaceConnectionId: String?): HttpResponse {
        if (context != null) {
            context.checkPermission(Item.EXTENDED_READ)
        } else {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER)
        }

        if (spaceConnectionId == null) {
            val firstConnectionId = doFillSpaceConnectionItems(context).firstOrNull()?.value
                ?: return HttpResponses.errorWithoutStack(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "No Space connections configured for Jenkins"
                )
            return doFillProjectKeyItems(context, firstConnectionId)
        }

        if (spaceConnectionId.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection is not selected"
            )
        }

        val spaceApiClient = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
            .getConnectionById(spaceConnectionId)
            ?.getApiClient()
            ?: return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection is not selected"
            )

        try {
            spaceApiClient.use {
                val projects = runBlocking {
                    spaceApiClient.projects.getAllProjects {
                        id()
                        name()
                        key {
                            key()
                        }
                    }
                }
                return ListBoxModel(projects.data.map { ListBoxModel.Option(it.name, it.key.key) })
            }
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, "Error performing request to JetBrains Space", ex)
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "Error performing request to JetBrains Space: $ex"
            )
        }
    }

    fun doFillRepositoryNameItems(
        context: Item?,
        spaceConnectionId: String?,
        projectKey: String?
    ): HttpResponse {
        if (spaceConnectionId == null) {
            val firstConnectionId = doFillSpaceConnectionItems(context).firstOrNull()?.value
                ?: return HttpResponses.errorWithoutStack(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "No Space connections configured for Jenkins"
                )
            val firstProjectKey =
                (doFillProjectKeyItems(context, firstConnectionId) as? ListBoxModel)?.firstOrNull()?.value
            return doFillRepositoryNameItems(context, firstConnectionId, firstProjectKey)
        }

        if (spaceConnectionId.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection is not selected"
            )
        }

        if (projectKey.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space project is not selected")
        }

        val spaceApiClient = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
            .getConnectionById(spaceConnectionId)
            ?.getApiClient()
            ?: return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection cannot be found"
            )

        try {
            spaceApiClient.use {
                val repos = runBlocking {
                    spaceApiClient.projects.repositories.find.findRepositories("") {
                        projectKey()
                        repository()
                    }
                }
                return ListBoxModel(repos.data.filter { it.projectKey == projectKey }
                    .map { ListBoxModel.Option(it.repository, it.repository) })
            }
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, ex.message)
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "Error performing request to JetBrains Space: " + ex.message
            )
        }
    }
}

private val LOGGER = Logger.getLogger(SpaceSCMParamsProvider::class.java.name)
