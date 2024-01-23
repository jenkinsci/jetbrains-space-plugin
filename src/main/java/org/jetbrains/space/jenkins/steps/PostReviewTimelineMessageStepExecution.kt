package org.jetbrains.space.jenkins.steps

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Run
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.*
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [PostReviewTimelineMessageStep], which is responsible for posting a message
 * to the merge request timeline in Space on behalf of Jenkins integration.
 */
@SuppressFBWarnings("SE_NO_SERIALVERSIONID")
class PostReviewTimelineMessageStepExecution(
    private val spaceConnectionId: String,
    private val projectKey: String,
    private val reviewNumber: Int,
    private val messageText: String,
    context: StepContext
) : StepExecution(context) {

    companion object {
        /**
         * Obtain all the necessary parameters from the build trigger or git checkout settings if necessary and start the step execution.
         */
        fun start(step: PostReviewTimelineMessageStep, context: StepContext, spacePluginConfiguration: SpacePluginConfiguration) :  StepExecution {
            val build: Run<*, *> = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val triggerCause = build.getAction(CauseAction::class.java)?.findCause(SpaceWebhookTriggerCause::class.java)
            val checkoutAction = build.getAction(SpaceGitScmCheckoutAction::class.java)

            val (connection, projectKey) = when {
                step.spaceConnectionId != null || step.spaceConnection != null -> {
                    val connection = spacePluginConfiguration.getConnectionByIdOrName(step.spaceConnectionId, step.spaceConnection)
                        ?: return FailureStepExecution(
                            "No Space connection found by specified id or name",
                            context
                        )
                    val projectKey = step.projectKey
                        ?: return FailureStepExecution(
                            "Project key must be specified together with the Space connection",
                            context
                        )
                    connection to projectKey
                }

                triggerCause != null -> {
                    val connection = spacePluginConfiguration.getConnectionById(triggerCause.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for the Space webhook call that triggered the build",
                            context
                        )
                    connection to triggerCause.projectKey
                }

                checkoutAction != null -> {
                    val connection = spacePluginConfiguration.getConnectionById(checkoutAction.spaceConnectionId)
                        ?: return FailureStepExecution(
                            "No Space connection found for checkout action",
                            context
                        )
                    connection to checkoutAction.projectKey
                }

                else ->
                    null to null
            }

            if (connection == null) {
                return FailureStepExecution(
                    "Space connection cannot be inferred from the build trigger or checkout setting and is not specified explicitly in the workflow step",
                    context
                )
            }

            if (projectKey == null) {
                return FailureStepExecution(
                    "Space project cannot be inferred from the build trigger or checkout setting and is not specified explicitly in the workflow step",
                    context
                )
            }

            val reviewNumber = (step.reviewNumber ?: triggerCause?.mergeRequest?.number)
                ?: return FailureStepExecution(
                    "Merge request cannot be inferred from the build trigger settings and is not specified explicitly in the workflow step",
                    context
                )

            return PostReviewTimelineMessageStepExecution(
                spaceConnectionId = connection.id,
                projectKey = projectKey,
                reviewNumber = reviewNumber,
                messageText = step.messageText,
                context = context
            )
        }
    }

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

