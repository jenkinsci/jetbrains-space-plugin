package org.jetbrains.space.jenkins.listeners

import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import org.jetbrains.space.jenkins.SpaceApiClient
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.steps.PostBuildStatusAction
import java.util.logging.Logger
import java.util.stream.Collectors
import javax.inject.Inject

@Extension
class RunListenerImpl : RunListener<Run<*, *>>() {

    @Inject
    private lateinit var spacePluginConfiguration: SpacePluginConfiguration

    override fun onCompleted(build: Run<*, *>, listener: TaskListener) {
        val postBuildStatusActions = build.getActions(PostBuildStatusAction::class.java)
        val checkoutActions = build.getActions(SpaceGitScmCheckoutAction::class.java)
            .filter(SpaceGitScmCheckoutAction::postBuildStatusToSpace)
            .filter { action: SpaceGitScmCheckoutAction ->
                postBuildStatusActions.stream()
                    .noneMatch { a: PostBuildStatusAction -> a.spaceConnectionId == action.spaceConnectionId && a.projectKey == action.projectKey && a.repositoryName == action.repositoryName }
            }

        for (action in checkoutActions) {
            val spaceConnection = spacePluginConfiguration.getConnectionById(action.spaceConnectionId)
            if (spaceConnection.isEmpty) {
                LOGGER.info("RunListenerImpl.onCompleted - could not find Space connection")
                return
            }
            try {
                SpaceApiClient(spaceConnection.get()).use { spaceApiClient ->
                    spaceApiClient.postBuildStatus(action, build, null)
                }
            } catch (ex: Throwable) {
                val message = "Failed to post build status to JetBrains Space, details: $ex"
                LOGGER.warning(message)
                listener.logger.println(message)
            }
        }
    }
}

private val LOGGER = Logger.getLogger(RunListenerImpl::class.simpleName)
