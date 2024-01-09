package org.jetbrains.space.jenkins.listeners

import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionById
import org.jetbrains.space.jenkins.steps.PostBuildStatusAction
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import java.util.logging.Level
import java.util.logging.Logger
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
            if (spaceConnection == null) {
                LOGGER.info("RunListenerImpl.onCompleted - could not find Space connection")
                return
            }
            try {
                runBlocking {
                    spaceConnection.getApiClient().postBuildStatus(action, build)
                }
            } catch (ex: Throwable) {
                val message = "Failed to post build status to JetBrains Space, details: $ex"
                LOGGER.warning(message)
                listener.logger.println(message)
            }
        }

        // update webhooks registered in Space according to the actual SCMs used in the last completed build
        try {
            (build.parent as? TriggeredItem)?.triggers?.values
                ?.filterIsInstance<SpaceWebhookTrigger>()
                ?.forEach { it.ensureSpaceWebhooks() }
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, "Failure while ensuring webhook settings in Space", ex)
        }
    }
}

private val LOGGER = Logger.getLogger(RunListenerImpl::class.simpleName)
