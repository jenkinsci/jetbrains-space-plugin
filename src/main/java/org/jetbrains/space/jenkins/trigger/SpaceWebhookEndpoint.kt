package org.jetbrains.space.jenkins.trigger

import hudson.ExtensionList
import hudson.model.CauseAction
import hudson.model.Job
import hudson.security.ACL
import io.ktor.http.*
import jenkins.model.Jenkins
import jenkins.scm.api.SCMHeadEvent
import jenkins.scm.api.SCMSourceOwner
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.TriggeredItem
import org.jetbrains.space.jenkins.SpacePayloadHandler
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.SpaceProjectConnectionJobProperty
import org.jetbrains.space.jenkins.scm.*
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.helpers.ProcessingScope
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.types.*
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Match received webhook callback with trigger in Jenkins
 * by comparing webhook id in the callback payload with the SpaceCode webhook id stored for each trigger
 * and trigger the build if conditions are satisfied
 */
@OptIn(ExperimentalSpaceSdkApi::class)
suspend fun ProcessingScope.processWebhookCallback(payload: WebhookRequestPayload): SpaceHttpResponse {
    return ACL.as2(ACL.SYSTEM2).use {
        when (payload.payload) {
            is ApplicationAuthorizedWebhookEvent ->
                handleApplicationAuthorizedEvent(payload)
            else ->
                handleBuildTriggerEvent(payload)
        }
    }
}

@OptIn(ExperimentalSpaceSdkApi::class)
private suspend fun ProcessingScope.handleApplicationAuthorizedEvent(payload: WebhookRequestPayload) : SpaceHttpResponse {
    val event = payload.payload as ApplicationAuthorizedWebhookEvent
    SpacePermissionsApproveListener.signal(event.application.id)
    Jenkins.get().getAllItems(TriggeredItem::class.java)
        .findBySpaceAppClientId(payload.clientId)
        .forEach { job ->
            if (job is TriggeredItem) {
                try {
                    job.triggers?.values?.filterIsInstance<SpaceWebhookTrigger>().orEmpty().forEach { trigger ->
                        trigger.ensureSpaceWebhook()
                    }
                } catch (ex: Exception) {
                    LOGGER.log(Level.WARNING, "Error while setting up webhook for job ${job.fullDisplayName}", ex)
                }
            }
        }
    return SpaceHttpResponse.RespondWithOk
}

@OptIn(ExperimentalSpaceSdkApi::class)
private suspend fun ProcessingScope.handleBuildTriggerEvent(payload: WebhookRequestPayload): SpaceHttpResponse {
    val allJobs = Jenkins.get().getAllItems(TriggeredItem::class.java)
    val trigger = allJobs.findBySpaceWebhookId(payload.webhookId, appInstance.clientId)

    if (trigger == null) {
        val scmSource = Jenkins.get().getAllItems(SCMSourceOwner::class.java)
            .flatMap { it.scmSources }
            .filterIsInstance<SpaceSCMSource>()
            .firstOrNull { it.spaceWebhookId == payload.webhookId }
        return if (scmSource != null) {
            when (val result = matchWebhookEvent(
                trigger = scmSource.getWebhookDefinition(),
                spaceProjectKey = scmSource.projectKey,
                spaceRepositoryName = scmSource.repository,
                event = payload.payload,
                ownerDisplayName = "branch source of the project \"${scmSource.owner?.fullDisplayName.orEmpty()}\""
            )) {
                is WebhookEventResult.RunBuild -> {
                    SCMHeadEvent.fireNow(result.event)
                    SpaceHttpResponse.RespondWithOk
                }

                WebhookEventResult.UnexpectedEvent -> {
                    scmSource.ensureSpaceWebhook()
                    SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                }

                WebhookEventResult.IgnoredEvent ->
                    SpaceHttpResponse.RespondWithCode(HttpStatusCode.Accepted)
            }
        } else {
            LOGGER.warning("No registered trigger found for webhook id = ${payload.webhookId}")
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
        }
    }

    val job = trigger.job
    val triggerItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job)
    if (triggerItem == null) {
        LOGGER.info("Cannot trigger item of type ${job::class.qualifiedName}")
        return SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
    }

    val (spaceConnection, _) = job.getProjectConnection()
        ?: error("SpaceCode connection cannot be found for the triggered job")

    // deep check of event properties and trigger conditions to ensure that build should be triggered
    val result = matchWebhookEvent(
        trigger = trigger.getDefinition(),
        spaceProjectKey = spaceConnection.projectKey,
        spaceRepositoryName = trigger.repositoryName,
        event = payload.payload,
        ownerDisplayName = "trigger of the \"${job.fullDisplayName}\""
    )
    return when (result) {
        is WebhookEventResult.RunBuild -> {
            val causeAction = CauseAction(result.cause)
            triggerItem.scheduleBuild2(triggerItem.quietPeriod, causeAction)
            SpaceHttpResponse.RespondWithOk
        }

        is WebhookEventResult.UnexpectedEvent -> {
            trigger.ensureSpaceWebhook()
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
        }

        is WebhookEventResult.IgnoredEvent ->
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.Accepted)
    }
}

private fun List<TriggeredItem>.findBySpaceAppClientId(spaceClientId: String) =
    filterIsInstance<Job<*, *>>()
        .mapNotNull { job ->
            val spaceProjectProperty = job.getProperty(SpaceProjectConnectionJobProperty::class.java)
                ?: return@mapNotNull null
            val rootConnection = ExtensionList.lookupSingleton(SpacePluginConfiguration::class.java)
                .connections.firstOrNull { it.id == spaceProjectProperty.spaceConnectionId }
                ?: return@mapNotNull null

            val jobFullName = job.fullName
            job.takeIf {
                rootConnection.clientId == spaceClientId
                        || rootConnection.projectConnectionsByJob?.get(jobFullName)?.clientId == spaceClientId
            }
        }

private fun List<TriggeredItem>.findBySpaceWebhookId(webhookId: String, spaceClientId: String) =
    firstNotNullOfOrNull { job ->
        job.takeIf { (it as? Job<*, *>)?.getSpaceClientId() == spaceClientId }
            ?.triggers?.values
            ?.filterIsInstance<SpaceWebhookTrigger>()
            ?.firstOrNull { it.spaceWebhookId == webhookId }
    }

/**
 * Represents the result of checking whether an incoming webhook callback
 * matches the trigger or multibranch project branch source settings configured in Jenkins.
 */
private sealed class WebhookEventResult {
    /**
     * Event received from SpaceCode matches trigger conditions, build should be started
     */
    class RunBuild(val cause: SpaceWebhookTriggerCause, val event: SCMHeadEvent<*>) : WebhookEventResult()

    /**
     * Event received from SpaceCode was not expected to arrive according to trigger settings,
     * probably webhook settings in SpaceCode are out of sync with trigger settings in Jenkins.
     * Webhook in SpaceCode will be updated with the up-to-date settings when such event arrives.
     */
    data object UnexpectedEvent : WebhookEventResult()

    /**
     * Event received from SpaceCode was expected, but it shouldn't trigger a build in Jenkins
     * because trigger conditions weren't satisfied.
     * This happens, for example, when new commits are added to the merge request
     * but the trigger requires it to be approved by all reviewers and the approvals aren't there yet.
     */
    data object IgnoredEvent : WebhookEventResult()
}

/**
 * Checks whether an event received from SpaceCode matches the trigger conditions and thus whether a build must be triggered,
 * or whether it matches any branch source in a multibranch sources and thus a child job must be triggered or list of heads should be updated.
 * This is a deep check of all the properties of the event and conditions of the trigger or branch source.
 * The initial matching of trigger or branch source to SpaceCode webhook by the means of comparing the ids has already been done before.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
private fun ProcessingScope.matchWebhookEvent(
    trigger: SpaceWebhookTriggerDefinition?,
    spaceProjectKey: String,
    spaceRepositoryName: String,
    event: WebhookEvent,
    ownerDisplayName: String
): WebhookEventResult {
    return when (trigger) {
        is SpaceWebhookTriggerDefinition.Branches -> {
            if (event !is SRepoPushWebhookEvent) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event but expected ${SRepoPushWebhookEvent::class.simpleName} for the $ownerDisplayName")
                return WebhookEventResult.UnexpectedEvent
            }

            if (event.projectKey.key != spaceProjectKey || event.repository != spaceRepositoryName) {
                LOGGER.warning("Event project and repository do not match those configured for the $ownerDisplayName")
                return WebhookEventResult.UnexpectedEvent
            }

            if (event.deleted) {
                LOGGER.info("Got git push event for a deleted branch")
                return WebhookEventResult.IgnoredEvent
            }

            if (event.newCommitId == null) {
                LOGGER.warning("Got git push event for created or updated branch without new commit id")
                return WebhookEventResult.UnexpectedEvent
            }

            val cause = SpaceWebhookTriggerCause(
                spaceUrl = appInstance.spaceServer.serverUrl,
                projectKey = spaceProjectKey,
                repositoryName = event.repository,
                triggerType = SpaceWebhookTriggerType.MergeRequests,
                cause = TriggerCause.BranchPush(
                    head = event.head,
                    commitId = event.newCommitId!!,
                    url = buildSpaceCommitUrl(
                        appInstance.spaceServer.serverUrl,
                        event.projectKey.key,
                        event.repository,
                        event.head
                    )
                )
            )
            val scmHeadEvent = SpaceBranchSCMHeadEvent(event, appInstance.spaceServer.serverUrl)

            WebhookEventResult.RunBuild(cause, scmHeadEvent)
        }

        is SpaceWebhookTriggerDefinition.MergeRequests -> {
            val (mergeRequest, scmHeadEvent) = when (event) {
                is CodeReviewWebhookEvent ->
                    event.review to event.review?.createScmHeadEvent(appInstance, event.meta)
                        .takeIf { event.isExpectedFor(trigger, ownerDisplayName) }

                is CodeReviewUpdatedWebhookEvent ->
                    event.review to event.review?.createScmHeadEvent(appInstance, event.meta)
                        .takeIf { event.isExpectedFor(trigger, ownerDisplayName) }

                is CodeReviewParticipantWebhookEvent ->
                    event.review to event.review.createScmHeadEvent(appInstance, event.meta)
                        .takeIf { event.isExpectedFor(trigger, ownerDisplayName) }

                is CodeReviewCommitsUpdatedWebhookEvent ->
                    event.review to event.review.createScmHeadEvent(appInstance, event.meta)

                else -> {
                    LOGGER.warning("Got unexpected ${event.javaClass.simpleName} event type instead of code review event for the $ownerDisplayName")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            if (mergeRequest == null) {
                LOGGER.warning("Got ${event.javaClass.simpleName} event without review for the $ownerDisplayName")
                return WebhookEventResult.UnexpectedEvent
            }

            if (mergeRequest !is MergeRequestRecord) {
                LOGGER.info("Got ${event.javaClass.simpleName} event for commit set review for the $ownerDisplayName")
                return WebhookEventResult.IgnoredEvent
            }

            if (mergeRequest.project.key != spaceProjectKey || mergeRequest.branchPairs.firstOrNull()?.repository != spaceRepositoryName) {
                LOGGER.warning("Event project and repository do not match those configured for the $ownerDisplayName")
                return WebhookEventResult.UnexpectedEvent
            }


            if (scmHeadEvent == null) {
                // specific message should have been logged while checking the match
                return WebhookEventResult.UnexpectedEvent
            }

            if (trigger.isMergeRequestApprovalsRequired) {
                val reviewers =
                    mergeRequest.participants?.filter { it.role == CodeReviewParticipantRole.Reviewer }.orEmpty()
                if (reviewers.isEmpty() || !reviewers.all { it.state == ReviewerState.Accepted }) {
                    LOGGER.info("Ignoring webhook for the $ownerDisplayName because merge request hasn't been accepted by all reviewers")
                    return WebhookEventResult.IgnoredEvent
                }
            }

            if (!trigger.titleRegex.isNullOrBlank()) {
                val regex = Regex(trigger.titleRegex)
                if (event is CodeReviewUpdatedWebhookEvent) {
                    if (!regex.matches(event.titleMod?.new.orEmpty())) {
                        LOGGER.info("Ignoring webhook for the $ownerDisplayName because new title does not match filter")
                        return WebhookEventResult.IgnoredEvent
                    }

                    if (regex.matches(event.titleMod?.old.orEmpty())) {
                        LOGGER.info("Ignoring webhook for the $ownerDisplayName because old title matched filter as well")
                        return WebhookEventResult.IgnoredEvent
                    }
                }
                if (!regex.matches(mergeRequest.title)) {
                    LOGGER.warning("Got event for review with title that doesn't match filter for the $ownerDisplayName")
                    return WebhookEventResult.UnexpectedEvent
                }
            }

            WebhookEventResult.RunBuild(
                SpaceWebhookTriggerCause.fromMergeRequest(mergeRequest, appInstance.spaceServer.serverUrl),
                scmHeadEvent
            )
        }

        null ->
            WebhookEventResult.UnexpectedEvent
    }
}

private fun CodeReviewWebhookEvent.isExpectedFor(trigger: SpaceWebhookTriggerDefinition.MergeRequests, ownerDisplayName: String): Boolean {
    if (meta?.method != "Created") {
        LOGGER.warning("Event meta is ${meta?.method}, expected 'Created' for the $ownerDisplayName")
        return false
    }

    if (trigger.isMergeRequestApprovalsRequired) {
        LOGGER.warning("Got event for review creation but the $ownerDisplayName is set up to run only after getting all approvals")
        return false
    }

    return true
}

private fun CodeReviewUpdatedWebhookEvent.isExpectedFor(trigger: SpaceWebhookTriggerDefinition.MergeRequests, ownerDisplayName: String): Boolean {
    if (titleMod == null) {
        LOGGER.warning("Got review updated event without title change for the $ownerDisplayName")
        return false
    }

    if (trigger.titleRegex.isNullOrBlank()) {
        LOGGER.warning("Got event for review title change but the $ownerDisplayName does not has filter by review title applied")
        return false
    }

    return true
}

private fun CodeReviewParticipantWebhookEvent.isExpectedFor(trigger: SpaceWebhookTriggerDefinition.MergeRequests, ownerDisplayName: String): Boolean {
    if (reviewerState == null) {
        LOGGER.warning("Got CodeReviewParticipantWebhookEvent without reviewer state change for the $ownerDisplayName")
        return false
    }

    if (!trigger.isMergeRequestApprovalsRequired) {
        LOGGER.warning("Got CodeReviewParticipantWebhookEvent but the $ownerDisplayName does not require reviewer approvals to run build")
        return false
    }

    return true
}

private val LOGGER = Logger.getLogger(SpacePayloadHandler::class.java.name)
