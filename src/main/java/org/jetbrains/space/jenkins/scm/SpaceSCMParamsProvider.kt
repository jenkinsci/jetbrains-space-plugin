package org.jetbrains.space.jenkins.scm

import hudson.util.ListBoxModel
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import space.jetbrains.api.runtime.resources.projects
import java.net.HttpURLConnection
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceSCMParamsProvider {

    @Inject
    lateinit var spacePluginConfiguration: SpacePluginConfiguration

    fun doFillSpaceConnectionIdItems() =
        ListBoxModel(spacePluginConfiguration.connections.map { ListBoxModel.Option(it.name, it.id) })

    fun doFillProjectKeyItems(spaceConnectionId: String?): HttpResponse {
        val optSpaceApiClient = spacePluginConfiguration.getSpaceApiClient(spaceConnectionId)
        if (optSpaceApiClient.isEmpty) {
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection is not selected"
            )
        }

        try {
            optSpaceApiClient.get().use { spaceApiClient ->
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
        val optSpaceApiClient = spacePluginConfiguration.getSpaceApiClient(spaceConnectionId)
        if (optSpaceApiClient.isEmpty) {
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Space connection is not selected"
            )
        }

        if (projectKey.isNullOrBlank()) {
            return HttpResponses.errorWithoutStack(HttpURLConnection.HTTP_BAD_REQUEST, "Space project is not selected")
        }

        try {
            optSpaceApiClient.get().use { spaceApiClient ->
                val repos = runBlocking {
                    spaceApiClient.projects.repositories.find.findRepositories("") {
                        projectKey()
                        repository()
                    }
                }
                return ListBoxModel(repos.data.filter { it.projectKey == projectKey }.map { ListBoxModel.Option(it.repository, it.repository) })
            }
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, ex.message)
            return HttpResponses.errorWithoutStack(
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "Error performing request to JetBrains Space: " + ex.message
            )
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(SpaceSCMParamsProvider::class.java.name)
    }
}
