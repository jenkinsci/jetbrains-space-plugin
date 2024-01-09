package org.jetbrains.space.jenkins.config

import io.ktor.http.*
import java.util.logging.Logger

fun SpacePluginConfiguration.getConnectionById(id: String): SpaceConnection? {
    return if (id.isNotBlank()) connections.firstOrNull { it.id == id } else null
}

fun SpacePluginConfiguration.getConnectionByIdOrName(id: String?, name: String?): SpaceConnection? {
    return when {
        !id.isNullOrBlank() ->
            connections.firstOrNull { it.id == id }
        !name.isNullOrBlank() ->
            connections.firstOrNull { it.name == name }
        else ->
            null
    }
}

fun SpacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(gitCloneUrl: String): SpaceRepository? {
    val url = try {
        Url(gitCloneUrl)
    } catch (e: URLParserException) {
        LOGGER.warning("Malformed git SCM url - $gitCloneUrl");
        return null;
    }

    if (!url.host.startsWith("git.")) {
        return null;
    }

    val (baseUrlHostname, projectKey, repositoryName) =
        if (url.host.endsWith(".jetbrains.space")) {
            // cloud space, path contains org name, project key and repo name
            if (url.pathSegments.size < 3) {
                return null
            }
            Triple(url.pathSegments[0] + ".jetbrains.space", url.pathSegments[1], url.pathSegments[2])
        } else {
            // path contains project key and repo name
            if (url.pathSegments.size < 2) {
                return null
            }
            Triple(url.host.substring("git.".length), url.pathSegments[0], url.pathSegments[1])
        }

    return connections
        .firstOrNull {
            try {
                baseUrlHostname == Url(it.baseUrl).host
            } catch (e: URLParserException) {
                LOGGER.warning("Malformed Space connection base url - ${it.baseUrl}")
                false
            }
        }
        ?.let { SpaceRepository(it, projectKey, repositoryName) }
}

private val LOGGER = Logger.getLogger(SpacePluginConfiguration::class.java.name)
