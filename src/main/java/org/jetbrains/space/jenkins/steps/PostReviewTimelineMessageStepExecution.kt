package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionById
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import kotlin.coroutines.EmptyCoroutineContext

class PostReviewTimelineMessageStepExecution(
    private val spaceConnectionId: String,
    private val projectKey: String,
    private val reviewNumber: Int,
    private val messageText: String,
    context: StepContext
) : StepExecution(context) {

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
            ?: error("Space plugin configuration cannot be resolved")

        val spaceConnection = spacePluginConfiguration.getConnectionById(spaceConnectionId)
            ?: error("No Space connection found by specified id")

        coroutineScope.launch {
            try {
                spaceConnection.getApiClient().use { spaceApiClient ->
                    val review = spaceApiClient.projects.codeReviews.getCodeReview(
                        ProjectIdentifier.Key(projectKey),
                        ReviewIdentifier.Number(reviewNumber)
                    ) {
                        feedChannelId()
                    } as? MergeRequestRecord ?: error("Cannot find merge request $reviewNumber in Space project $projectKey")

                    review.feedChannelId?.let { channelId ->
                        spaceApiClient.chats.messages.sendMessage(
                            ChannelIdentifier.Id(channelId),
                            ChatMessage.Text(messageText)
                        )
                    } ?: error("Merge request $reviewNumber in Space project $projectKey does not have associated feed channel")
                }
                context.onSuccess(Unit)
            } catch (ex: Throwable) {
                context.onFailure(ex)
            }
        }
        return false
    }
}
