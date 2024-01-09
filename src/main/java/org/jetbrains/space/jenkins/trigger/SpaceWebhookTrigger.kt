package org.jetbrains.space.jenkins.trigger

import hudson.Extension
import hudson.model.Item
import hudson.model.Job
import hudson.plugins.git.GitSCM
import hudson.triggers.Trigger
import hudson.triggers.TriggerDescriptor
import hudson.util.ListBoxModel
import jenkins.model.*
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.SCMTriggerItem.SCMTriggerItems
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.*
import org.jetbrains.space.jenkins.scm.SpaceSCM
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider
import org.kohsuke.stapler.*
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.runtime.Option
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class SpaceWebhookTrigger @DataBoundConstructor constructor(id: String?, var spaceWebhookIds: List<String>?) : Trigger<Job<*, *>?>() {

    val id: String? = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id

    @set:DataBoundSetter
    var customSpaceRepository: SpaceRepository? = null

    @set:DataBoundSetter
    var triggerType: SpaceWebhookTriggerType = SpaceWebhookTriggerType.Branches

    @set:DataBoundSetter
    var branchSpec: String = ""

    @set:DataBoundSetter
    var onMergeRequestCreate: Boolean = false

    @set:DataBoundSetter
    var onMergeRequestPushToSourceBranch: Boolean = false

    @set:DataBoundSetter
    var onMergeRequestPushToTargetBranch: Boolean = false

    @set:DataBoundSetter
    var onMergeRequestApprovedByAllReviewers: Boolean = false

    @set:DataBoundSetter
    var mergeRequestTitleRegex: String = ""

    @set:DataBoundSetter
    var mergeRequestSourceBranchSpec: String = ""


    override fun start(project: Job<*, *>?, newInstance: Boolean) {
        super.start(project, newInstance)
        if (id == null || (!newInstance && !spaceWebhookIds.isNullOrEmpty()))
            return

        ensureSpaceWebhooks()
    }

    fun ensureSpaceWebhooks() {
        if (id == null)
            return

        val triggerItem: SCMTriggerItem? = SCMTriggerItems.asSCMTriggerItem(job)
        val allRepositories = when {
            customSpaceRepository != null -> {
                val repo = customSpaceRepository!!
                val spaceConnection = (descriptor as DescriptorImpl).spacePluginConfiguration.getConnectionById(repo.spaceConnectionId)
                if (spaceConnection == null) {
                    LOGGER.warning("Space connection not found by id = ${repo.spaceConnectionId}")
                    return
                }
                listOf(SpaceRepository(spaceConnection, repo.projectKey, repo.repositoryName))
            }

            // triggerItem.scMs contains SCMs that have been actually used during the last build run
            // if checkout step has been reconfigured to another repository, webhook settings in Space won't get updated accordingly
            // and thus webhook won't work until at least a single build is triggered manually
            // (at that point in time RunListenerImpl will take care of updating the webhook)
            triggerItem != null -> {
                val spacePluginConfiguration = (descriptor as DescriptorImpl).spacePluginConfiguration
                triggerItem.scMs.mapNotNull { scm ->
                    when (scm) {
                        is SpaceSCM ->
                            spacePluginConfiguration.getConnectionByIdOrName(scm.spaceConnectionId, scm.spaceConnection)
                                ?.let { SpaceRepository(it, scm.projectKey, scm.repositoryName) }
                                ?: run {
                                    LOGGER.warning("Space connection not found by id = ${scm.spaceConnectionId}")
                                    null
                                }

                        is GitSCM ->
                            scm.userRemoteConfigs
                                .mapNotNull { it.url }
                                .firstNotNullOfOrNull { spacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(it) }

                        else ->
                            null
                    }
                }
            }

            else ->
                emptyList()
        }
        if (allRepositories.isEmpty()) {
            LOGGER.warning("Space trigger has no effect - it does not define Space connection and build does not use Space SCM")
            return
        }

        this.spaceWebhookIds = allRepositories.groupBy { it.spaceConnection }.flatMap { (spaceConnection, repositories) ->
            try {
                runBlocking {
                    spaceConnection.getApiClient().use { spaceApiClient ->
                        val existingWebhooks = spaceApiClient.getRegisteredWebhooks()
                        val webhookNames = repositories.map { getSpaceWebhookName(it.projectKey, it.repositoryName, id) }.toSet()
                        existingWebhooks
                            .filter { !webhookNames.contains(it.webhook.name) }
                            .forEach { spaceApiClient.applications.webhooks.deleteWebhook(ApplicationIdentifier.Me, it.webhook.id) }
                        repositories.map { repo ->
                            val subscription =
                                createSubscription(repo.projectKey, repo.repositoryName, spaceApiClient)
                            val spaceWebhookId = spaceApiClient.ensureTriggerWebhook(
                                projectKey = repo.projectKey,
                                repositoryName = repo.repositoryName,
                                triggerId = id,
                                subscription = subscription,
                                existingWebhooks = existingWebhooks
                            )
                            spaceWebhookId
                        }
                    }
                }
            } catch (ex: Throwable) {
                LOGGER.log(Level.WARNING, "Error while setting up webhook in Space", ex)
                emptyList()
            }
        }
    }

    private suspend fun createSubscription(projectKey: String, repositoryName: String, spaceApiClient: SpaceClient): CustomGenericSubscriptionIn {
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
                        branchSpec = listOfNotNull(mergeRequestSourceBranchSpec.takeUnless { it.isBlank() }),
                        pathSpec = listOf(),
                        titleRegex = mergeRequestTitleRegex
                    )),
                    eventTypeCodes = listOfNotNull(
                        "CodeReview.Created".takeIf { onMergeRequestCreate },
                        "CodeReview.CommitsUpdated".takeIf { onMergeRequestPushToSourceBranch },
                        "CodeReview.TargetBranchUpdated".takeIf { onMergeRequestPushToTargetBranch },
                        "CodeReview.Participant.ChangesAccepted".takeIf { onMergeRequestApprovedByAllReviewers }
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

    @Extension
    class DescriptorImpl : TriggerDescriptor() {
        @Inject
        lateinit var spacePluginConfiguration: SpacePluginConfiguration

        @Inject
        private lateinit var scmParamsProvider: SpaceSCMParamsProvider

        init {
            load()
        }

        override fun getDisplayName(): String {
            return "JetBrains Space webhook trigger"
        }

        override fun isApplicable(item: Item): Boolean {
            return SCMTriggerItems.asSCMTriggerItem(item) != null
        }

        @POST
        fun doFillSpaceConnectionIdItems(): ListBoxModel {
            return scmParamsProvider.doFillSpaceConnectionIdItems()
        }

        @POST
        fun doFillProjectKeyItems(@QueryParameter spaceConnectionId: String): HttpResponse {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId)
        }

        @POST
        fun doFillRepositoryNameItems(
            @QueryParameter spaceConnectionId: String,
            @QueryParameter projectKey: String
        ): HttpResponse {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey)
        }
    }

    class SpaceRepository @DataBoundConstructor constructor(var spaceConnectionId: String, var projectKey: String, var repositoryName: String)
}

fun getSpaceWebhookName(projectKey: String, repositoryName: String, triggerId: String): String {
    return "$triggerId|$projectKey|$repositoryName"
}

fun getTriggerId(webhook: WebhookRecord): String? {
    return webhook.name.split('|').takeIf { it.size == 3 }?.let { it[0] }
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
                ExternalEndpointUpdateDTO(
                    Option.Value(triggerUrl),
                    Jenkins.get().isRootUrlSecure
                )
            )
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

suspend fun SpaceClient.getRegisteredWebhooks(): List<FullWebhookDTO> {
    return applications.webhooks.getAllWebhooks(ApplicationIdentifier.Me) {
        webhook {
            id()
            name()
        }
    }.data
}

private val LOGGER = Logger.getLogger(SpaceWebhookTrigger::class.java.name)
