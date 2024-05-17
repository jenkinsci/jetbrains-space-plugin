package org.jetbrains.space.jenkins.steps

import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Run
import jenkins.branch.MultiBranchProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getProjectConnection
import org.jetbrains.space.jenkins.scm.SpaceSCMHead
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import org.jetbrains.space.jenkins.trigger.TriggerCause
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives the execution of the [PostReviewTimelineMessageStep], which is responsible for posting a message
 * to the merge request timeline in SpaceCode on behalf of Jenkins integration.
 */
class PostReviewTimelineMessageStepExecution(
    val jenkinsItemFullName: String,
    val spaceConnectionId: String,
    val spaceProjectKey: String?,
    val mergeRequestNumber: Int,
    val messageText: String,
    context: StepContext
) : StepExecution(context) {

    companion object {
        private final val serialVersionUID = 1L

        /**
         * Obtain all the necessary parameters from the build trigger or git checkout settings if necessary and start the step execution.
         */
        fun start(step: PostReviewTimelineMessageStep, context: StepContext) :  StepExecution {
            val build: Run<*, *> = context.get(Run::class.java)
                ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

            val multiProjectScmSource = build.getParent().getMultiBranchSpaceScmSource()
            val spaceConnectionId = multiProjectScmSource?.spaceConnectionId
                ?: build.getParent().getSpaceConnectionId()
                ?: return FailureStepExecution("SpaceCode connection not found", context)

            val triggerCause = build.getAction(CauseAction::class.java)?.findCause(SpaceWebhookTriggerCause::class.java)?.cause
                ?: (build.getParent().getProperty(BranchJobProperty::class.java)?.branch?.head as? SpaceSCMHead)
                    ?.triggerCause
            val mergeRequestNumber = (step.mergeRequestNumber ?: (triggerCause as? TriggerCause.MergeRequest)?.number)
                ?: return FailureStepExecution(
                    "Merge request cannot be inferred from the build trigger settings and is not specified explicitly in the workflow step",
                    context
                )

            val jenkinsItem = (build.getParent().getParent() as? MultiBranchProject<*, *>) ?: build.getParent()

            return PostReviewTimelineMessageStepExecution(
                jenkinsItemFullName = jenkinsItem.fullName,
                spaceConnectionId = spaceConnectionId,
                spaceProjectKey = multiProjectScmSource?.projectKey,
                mergeRequestNumber = mergeRequestNumber,
                messageText = step.messageText,
                context = context
            )
        }
    }

    @Transient
    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

    override fun start(): Boolean {
        val (spaceConnection, spaceUrl) = getProjectConnection(jenkinsItemFullName, spaceConnectionId, spaceProjectKey)

        val projectKey = spaceConnection.projectKey
        coroutineScope.launch {
            try {
                spaceConnection.getApiClient(spaceUrl).use { spaceApiClient ->
                    val review = spaceApiClient.projects.codeReviews.getCodeReview(
                        ProjectIdentifier.Key(projectKey),
                        ReviewIdentifier.Number(mergeRequestNumber)
                    ) {
                        feedChannelId()
                    } as? MergeRequestRecord ?: error("Cannot find merge request $mergeRequestNumber in SpaceCode project $projectKey")

                    review.feedChannelId?.let { channelId ->
                        spaceApiClient.chats.messages.sendMessage(
                            ChannelIdentifier.Id(channelId),
                            ChatMessage.Text(messageText)
                        )
                    } ?: error("Merge request $mergeRequestNumber in SpaceCode project $projectKey does not have associated feed channel")
                }
                context.onSuccess(null)
            } catch (ex: Throwable) {
                context.onFailure(ex)
            }
        }
        return false
    }
}
