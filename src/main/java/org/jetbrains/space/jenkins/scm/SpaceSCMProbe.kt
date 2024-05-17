package org.jetbrains.space.jenkins.scm

import jenkins.scm.api.SCMFile
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMProbe
import jenkins.scm.api.SCMProbeStat
import kotlinx.coroutines.runBlocking
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.GitEntryType
import space.jetbrains.api.runtime.types.ProjectIdentifier

/**
 * Performs a file path check for a given head (branch or merge request) within a SpaceCode git repository
 * to determine whether this head should result in a Jenkins job creation in the multibranch project.
 *
 * @see <a href="https://github.com/jenkinsci/scm-api-plugin/blob/master/docs/implementation.adoc">SCM API implementation guide</a>
 */
class SpaceSCMProbe(
    val head: SCMHead,
    val spaceApiClient: SpaceClient,
    val spaceProjectIdentifier: ProjectIdentifier,
    val spaceRepository: String,
    val ownsSpaceApiClient: Boolean
) : SCMProbe() {
    override fun name() = head.name

    override fun lastModified() =
        if (head is SpaceSCMHead) head.lastUpdated else -1L

    override fun stat(path: String): SCMProbeStat {
        return runBlocking {
            val files = spaceApiClient.projects.repositories.files(
                spaceProjectIdentifier,
                spaceRepository,
                (head as? SpaceSCMHead)?.latestCommit ?: head.name,
                path
            )
            when {
                files.isEmpty() ->
                    SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT)
                files.size == 1 && files[0].path == path ->
                    SCMProbeStat.fromType(when (files[0].type) {
                        GitEntryType.EXE_FILE, GitEntryType.FILE ->
                            SCMFile.Type.REGULAR_FILE
                        GitEntryType.DIR ->
                            SCMFile.Type.DIRECTORY
                        GitEntryType.GIT_LINK, GitEntryType.SYM_LINK ->
                            SCMFile.Type.LINK
                    })
                else ->
                    SCMProbeStat.fromType(SCMFile.Type.DIRECTORY)
            }
        }
    }

    override fun close() {
        if (ownsSpaceApiClient) {
            spaceApiClient.close()
        }
    }
}