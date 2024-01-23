package org.jetbrains.space.jenkins.scm

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.util.ListBoxModel
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import space.jetbrains.api.runtime.resources.projects
import java.net.HttpURLConnection
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressFBWarnings("NP_NONNULL_RETURN_VIOLATION")
class SpaceSCMParamsProvider {

    @Inject
    lateinit var spacePluginConfiguration: SpacePluginConfiguration

    fun doFillSpaceConnectionIdItems() =
        ListBoxModel(spacePluginConfiguration.connections.map { ListBoxModel.Option(it.name, it.id) })

    fun doFillSpaceConnectionNameItems() =
        ListBoxModel(spacePluginConfiguration.connections.map { ListBoxModel.Option(it.name) })

    fun doFillProjectKeyItems(spaceConnectionId: String?) =
        doFillProjectKeyItems(spaceConnectionId = spaceConnectionId, spaceConnectionName = null)

    fun doFillProjectKeyItems(spaceConnectionId: String?, spaceConnectionName: String?): HttpResponse {
        if (spaceConnectionId == null && spaceConnectionName == null) {
            val firstConnectionId = doFillSpaceConnectionIdItems().firstOrNull()?.value
                ?: return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "No Space connections configured for Jenkins")
            return doFillProjectKeyItems(firstConnectionId)
        }

        if (spaceConnectionId.isNullOrBlank() && spaceConnectionName.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space connection is not selected")
        }

        val spaceApiClient = spacePluginConfiguration.getConnectionByIdOrName(spaceConnectionId, spaceConnectionName)?.getApiClient()
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

    fun doFillRepositoryNameItems(spaceConnectionId: String?, projectKey: String?): HttpResponse {
        return doFillRepositoryNameItems(
            spaceConnectionId = spaceConnectionId,
            spaceConnectionName = null,
            projectKey = projectKey
        )
    }

    fun doFillRepositoryNameItems(spaceConnectionId: String?, spaceConnectionName: String?, projectKey: String?): HttpResponse {
        if (spaceConnectionId == null && spaceConnectionName == null) {
            val firstConnectionId = doFillSpaceConnectionIdItems().firstOrNull()?.value
                ?: return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "No Space connections configured for Jenkins")
            val firstProjectKey = (doFillProjectKeyItems(firstConnectionId) as? ListBoxModel)?.firstOrNull()?.value
            return doFillRepositoryNameItems(firstConnectionId, firstProjectKey)
        }

        if (spaceConnectionId.isNullOrBlank() && spaceConnectionName.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space connection is not selected")
        }

        if (projectKey.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space project is not selected")
        }

        val spaceApiClient = spacePluginConfiguration.getConnectionByIdOrName(spaceConnectionId, spaceConnectionName)?.getApiClient()
            ?: return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space connection cannot be found")

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
