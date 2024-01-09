package org.jetbrains.space.jenkins.listeners

import hudson.Extension
import hudson.FilePath
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.SCMListener
import hudson.plugins.git.GitSCM
import hudson.scm.SCM
import hudson.scm.SCMRevisionState
import org.jetbrains.space.jenkins.SpaceApiClient
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.SpaceRepository
import org.jetbrains.space.jenkins.scm.SpaceSCM
import java.io.File
import java.util.*
import java.util.logging.Logger
import javax.inject.Inject

@Extension
class SCMListenerImpl : SCMListener() {

    @Inject
    private lateinit var spacePluginConfiguration: SpacePluginConfiguration

    private fun getUnderlyingGitSCM(scm: SCM?): GitSCM? {
        if (scm is GitSCM) {
            // Already a git SCM
            return scm
        }
        if (scm is SpaceSCM) {
            return scm.gitSCM
        }
        return null
    }

    override fun onCheckout(
        build: Run<*, *>,
        scm: SCM,
        workspace: FilePath,
        listener: TaskListener,
        changelogFile: File?,
        pollingBaseline: SCMRevisionState?
    ) {
        LOGGER.info("SpaceSCMListener.onCheckout called for " + scm.type)

        val underlyingGitScm = getUnderlyingGitSCM(scm)
        if (underlyingGitScm == null) {
            LOGGER.info("SpaceSCMListener.onCheckout - no underlying Git SCM instance")
            return
        }
        val env: Map<String, String> = HashMap()
        underlyingGitScm.buildEnvironment(build, env)

        var spaceRepository = Optional.empty<SpaceRepository>()
        if (scm is SpaceSCM) {
            spaceRepository = spacePluginConfiguration
                .getConnectionByIdOrName(scm.spaceConnectionId, scm.spaceConnection)
                .map { c: SpaceConnection? ->
                    SpaceRepository(
                        c!!, scm.projectKey, scm.repositoryName
                    )
                }
        } else {
//            spaceRepository = spacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(env.get(GitSCM.GIT_URL));
        }

        if (spaceRepository.isEmpty) {
            LOGGER.info("SpaceSCMListener.onCheckout - no Space SCM found")
            return
        }

        val spaceConnection = spaceRepository.get().spaceConnection
        val revisionAction = SpaceGitScmCheckoutAction(
            spaceConnection.id,
            spaceRepository.get().projectKey,
            spaceRepository.get().repositoryName,
            env[GitSCM.GIT_BRANCH]!!,
            env[GitSCM.GIT_COMMIT]!!,
            (scm as? SpaceSCM)?.postBuildStatusToSpace ?: false
        )
        build.addAction(revisionAction)

        if (revisionAction.postBuildStatusToSpace) {
            try {
                SpaceApiClient(spaceConnection).use { spaceApiClient ->
                    spaceApiClient.postBuildStatus(revisionAction, build, null)
                }
            } catch (ex: Throwable) {
                val message = "Failed to post build status to JetBrains Space, details: $ex"
                LOGGER.warning(message)
                listener.logger.println(message)
            }
        }
    }
}

private val LOGGER = Logger.getLogger(SCMListenerImpl::class.simpleName)
