package org.jetbrains.space.jenkins.config

import hudson.ExtensionList
import jenkins.branch.MultiBranchProject
import org.jetbrains.space.jenkins.scm.SpaceSCMSource
import java.util.ArrayList

fun getOrgConnection(id: String): SpaceConnection? {
    return ExtensionList.lookupSingleton<SpacePluginConfiguration>(SpacePluginConfiguration::class.java)
        .connections.firstOrNull { it.id == id }
}

/**
 * Retrieves the SpaceCode project connection and SpaceCode URL for a given Jenkins job, workflow or multibranch project.
 * <br />
 * Org-level connection id and project name do not uniquely identify a project connection in the case of multibranch projects,
 * because a single project can have multiple branch sources pointing to different SpaceCode organizations and projects.
 * Therefore for multibranch projects, SpaceCode project key is provided in addition to org-level connection id.
 */
fun getProjectConnection(jenkinsItemFullName: String, spaceConnectionId: String, projectKey: String?): Pair<SpaceProjectConnection, String> {
    return (if (projectKey != null)
        getProjectConnectionForMultiBranchProject(jenkinsItemFullName, spaceConnectionId, projectKey)
    else
        getProjectConnectionForJob(jenkinsItemFullName, spaceConnectionId))
        ?: error("No SpaceCode connection found by specified id")
}

/**
 * Retrieves the SpaceCode project connection and SpaceCode URL for a given Jenkins job or workflow and org-level connection id.
 */
private fun getProjectConnectionForJob(jobFullName: String, spaceConnectionId: String): Pair<SpaceProjectConnection, String>? {
    val rootConnection = getOrgConnection(spaceConnectionId) ?: return null
    return rootConnection.projectConnectionsByJob?.get(jobFullName)?.let { it to rootConnection.baseUrl }
}

/**
 * Retrieves the SpaceCode project connection and SpaceCode URL for a given Jenkins multibranch project, org-level connection id and SpaceCode project key.
 * <br />
 * Org-level connection id and project name do not uniquely identify a project connection in the case of multibranch projects,
 * because a single project can have multiple branch sources pointing to different SpaceCode organizations and projects.
 */
private fun getProjectConnectionForMultiBranchProject(projectFullName: String, spaceConnectionId: String, projectKey: String): Pair<SpaceProjectConnection, String>? {
    val rootConnection = getOrgConnection(spaceConnectionId) ?: return null
    return rootConnection.projectConnectionsByMultibranchFolder?.get(projectFullName)
        ?.let { it.firstOrNull { it.projectKey == projectKey } }
        ?.let { it to rootConnection.baseUrl }
}

fun getSpaceApiClientForMultiBranchProject(projectFullName: String, spaceConnectionId: String, projectKey: String) =
    getProjectConnectionForMultiBranchProject(projectFullName, spaceConnectionId, projectKey)
        ?.let { (projectConnection, spaceUrl) -> projectConnection.getApiClient(spaceUrl) }

/**
 * Called when a multibranch project is updated in Jenkins.
 * Cleans up unused project-level connections, SSH credentials and SpaceCode applications that aren't used by the project's branch sources anymore.
 */
fun SpacePluginConfiguration.onMultiBranchProjectUpdated(project: MultiBranchProject<*,*>) {
    val scmSources = project.getSCMSources().filterIsInstance<SpaceSCMSource>()
    if (scmSources.isEmpty()) {
        onItemDeleted(project.getFullName())
    } else {
        connections.forEach { parentConn ->
            parentConn.projectConnectionsByMultibranchFolder?.put(
                project.fullName,
                parentConn.projectConnectionsByMultibranchFolder.get(project.fullName)
                    ?.filter { projectConn ->
                        scmSources.any { it.spaceConnectionId == parentConn.id && it.projectKey == projectConn.projectKey }
                            .also {
                                if (!it) {
                                    projectConn.deleteProjectApplication(parentConn)
                                    projectConn.deleteSshKeyCredentials()
                                }
                            }
                    }
                    .let { ArrayList(it.orEmpty()) }
            )
        }
    }
}