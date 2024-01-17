package org.jetbrains.space.jenkins.trigger

import jenkins.model.*
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.SCMTriggerItem.SCMTriggerItems
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.*
import org.kohsuke.stapler.*
import space.jetbrains.api.runtime.Option
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import space.jetbrains.api.runtime.types.partials.WebhookEventPartial
import java.util.logging.Level
import java.util.logging.Logger

fun SpaceWebhookTrigger.ensureAndGetSpaceWebhookId(): String? {
    if (id == null)
        return null

    val spaceConnection =
        (descriptor as SpaceWebhookTrigger.DescriptorImpl).spacePluginConfiguration.getConnectionById(spaceConnectionId)
    if (spaceConnection == null) {
        LOGGER.warning("Space connection not found by id = $spaceConnectionId")
        return null
    }

    return try {
        runBlocking {
            spaceConnection.getApiClient().use { spaceApiClient ->
                val existingWebhooks =
                    spaceApiClient.getRegisteredWebhooks().filter { it.webhook.name.startsWith("GEN|") }
                val webhookName = getSpaceWebhookName(projectKey, repositoryName, id)
                existingWebhooks
                    .filter { it.webhook.name != webhookName }
                    .forEach {
                        spaceApiClient.applications.webhooks.deleteWebhook(
                            ApplicationIdentifier.Me,
                            it.webhook.id
                        )
                    }
                val subscription = createSubscription(projectKey, repositoryName, spaceApiClient)
                spaceApiClient.ensureTriggerWebhook(
                    projectKey = projectKey,
                    repositoryName = repositoryName,
                    triggerId = id,
                    subscription = subscription,
                    existingWebhooks = existingWebhooks
                )
            }
        }
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, "Error while setting up webhook in Space", ex)
        null
    }
}

private suspend fun SpaceWebhookTrigger.createSubscription(projectKey: String, repositoryName: String, spaceApiClient: SpaceClient): CustomGenericSubscriptionIn {
    val projectId = spaceApiClient.projects.getProject(ProjectIdentifier.Key(projectKey)).id
    return when (triggerType) {
        SpaceWebhookTriggerType.MergeRequests ->
            CustomGenericSubscriptionIn(
                subjectCode = "CodeReview",
                filters = listOf(CodeReviewSubscriptionFilterIn(
                    project = projectId,
                    repository = repositoryName,
                    authors = listOf(),
                    participants = listOf(),
                    branchSpec = mergeRequestBranchesSpec?.split(';')?.filterNot { it.isBlank() }?.takeUnless { it.isEmpty() }.orEmpty(),
                    pathSpec = listOf(),
                    titleRegex = mergeRequestTitleRegex
                )),
                eventTypeCodes = listOfNotNull(
                    "CodeReview.Created".takeUnless { isMergeRequestApprovalsRequired },
                    "CodeReview.TitleUpdated".takeUnless { mergeRequestTitleRegex.isNullOrBlank() },
                    "CodeReview.CommitsUpdated",
                    "CodeReview.Participant.ChangesAccepted".takeIf { isMergeRequestApprovalsRequired }
                )
            )
        SpaceWebhookTriggerType.Branches ->
            CustomGenericSubscriptionIn(
                subjectCode = "Repository.Heads",
                // TODO - pass branch specs
                filters = listOf(RepoHeadsSubscriptionFilterIn(projectId, repositoryName)),
                eventTypeCodes = listOf("Repository.Heads")
            )
    }
}

fun getSpaceWebhookName(projectKey: String, repositoryName: String, triggerId: String): String {
    return "GEN|$triggerId|$projectKey|$repositoryName"
}

fun getTriggerId(webhook: WebhookRecord): String? {
    return webhook.name.split('|').takeIf { it.size == 4 && it[0] == "GEN" }?.let { it[1] }
}

private suspend fun SpaceClient.ensureTriggerWebhook(
    projectKey: String,
    repositoryName: String,
    triggerId: String,
    subscription: CustomGenericSubscriptionIn,
    existingWebhooks: List<FullWebhookDTO>
): String {
    val webhookName = getSpaceWebhookName(projectKey, repositoryName, triggerId)
    val description = "Auto-generated webhook for triggering builds in Jenkins"
    val rootUrl = Jenkins.get().rootUrl
        ?: throw IllegalStateException("Jenkins instance has no root url specified")

    val triggerUrl = "${rootUrl.trimEnd('/')}/${SpaceWebhookEndpoint.URL}/trigger"

    val existing = existingWebhooks.firstOrNull { it.webhook.name == webhookName }
    return if (existing == null) {
        val webhookRecord = applications.webhooks.createWebhook(
            application = ApplicationIdentifier.Me,
            name = webhookName,
            description = description,
            endpoint = EndpointCreateDTO(triggerUrl, Jenkins.get().isRootUrlSecure),
            payloadFields = payloadFields,
            subscriptions = listOf(SubscriptionDefinition(webhookName, subscription))
        )
        webhookRecord.id
    } else {
        val webhookId = existing.webhook.id
        applications.webhooks.updateWebhook(
            application = ApplicationIdentifier.Me,
            webhookId = webhookId,
            name = webhookName,
            description = Option.Value(description),
            enabled = true,
            endpoint = Option.Value(
                ExternalEndpointUpdateDTO(Option.Value(triggerUrl), Jenkins.get().isRootUrlSecure)
            ),
            payloadFields = Option.Value(payloadFields)
        )

        val existingSubscriptions = applications.webhooks.subscriptions.getAllSubscriptions(
            ApplicationIdentifier.Me,
            webhookId
        )
        val subscriptionId: String
        if (existingSubscriptions.size == 1) {
            subscriptionId = existingSubscriptions[0].id
            applications.webhooks.subscriptions.updateSubscription(
                application = ApplicationIdentifier.Me,
                webhookId = webhookId,
                subscriptionId = subscriptionId,
                name = webhookName,
                enabled = true,
                subscription = subscription
            )
        } else {
            for (s in existingSubscriptions) {
                applications.webhooks.subscriptions.deleteSubscription(
                    ApplicationIdentifier.Me,
                    webhookId,
                    s.id
                )
            }
            val newSubscription = applications.webhooks.subscriptions.createSubscription(
                ApplicationIdentifier.Me,
                webhookId,
                webhookName,
                subscription
            )
            subscriptionId = newSubscription.id
        }

        applications.webhooks.subscriptions.requestMissingRights(
            ApplicationIdentifier.Me, webhookId, subscriptionId
        )
        webhookId
    }
}

private val payloadFields: WebhookEventPartial.() -> Unit = {
    meta()
    titleMod {
        old()
        new()
    }
    projectKey { key() }
    repository()
    reviewerState()
    changes {
        heads {
            name()
            newId()
        }
    }
    review {
        id()
        projectId()
        project { key() }
        number()
        title()
        branchPairs {
            repository()
            sourceBranchInfo()
            targetBranchInfo()
        }
        participants {
            role()
            state()
        }
    }
}

suspend fun SpaceClient.getRegisteredWebhooks(): List<FullWebhookDTO> {
    return applications.webhooks.getAllWebhooks(ApplicationIdentifier.Me) {
        webhook {
            id()
            name()
        }
    }.data
}

private val LOGGER = Logger.getLogger(SpaceWebhookTrigger::class.java.name)
