package org.jetbrains.space.jenkins.trigger

import hudson.Extension
import hudson.model.CauseAction
import hudson.model.Item
import hudson.model.Job
import hudson.triggers.Trigger
import hudson.triggers.TriggerDescriptor
import hudson.util.ListBoxModel
import io.ktor.http.*
import jenkins.model.*
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.SCMTriggerItem.SCMTriggerItems
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.SpaceApiClient
import org.jetbrains.space.jenkins.SpaceAppInstanceStorage
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.SpaceRepository
import org.jetbrains.space.jenkins.scm.SpaceSCM
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider
import org.kohsuke.stapler.*
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.helpers.processPayload
import space.jetbrains.api.runtime.ktorClientForSpace
import space.jetbrains.api.runtime.types.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class SpaceWebhookTrigger @DataBoundConstructor constructor(id: String?) : Trigger<Job<*, *>?>() {

    val id: String = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id

    var spaceWebhookIds: List<String>? = emptyList()

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
        if (spaceWebhookIds == null || (!newInstance && !spaceWebhookIds.isNullOrEmpty()))
            return

        val triggerItem: SCMTriggerItem? = SCMTriggerItems.asSCMTriggerItem(job)
        val allRepositories = when {
            customSpaceRepository != null -> {
                val repo = customSpaceRepository!!
                val spaceConnection: Optional<SpaceConnection> =
                    (descriptor as DescriptorImpl).spacePluginConfiguration.getConnectionById(repo.spaceConnectionId)
                if (spaceConnection.isEmpty) {
                    LOGGER.warning("Space connection not found by id = ${repo.spaceConnectionId}")
                    return
                }
                listOf(SpaceRepository(spaceConnection.get(), repo.projectKey, repo.repositoryName))
            }
            triggerItem != null -> {
                triggerItem.scMs
                    .filterIsInstance<SpaceSCM>()
                    .mapNotNull { scm ->
                        (descriptor as DescriptorImpl).spacePluginConfiguration
                            .getConnectionById(scm.spaceConnectionId)
                            .map { SpaceRepository(it, scm.projectKey, scm.repositoryName) }
                            .orElseGet {
                                LOGGER.warning("Space connection not found by id = " + scm.spaceConnectionId)
                                null
                            }
                    }
            }
            else -> {
                LOGGER.warning("Space trigger has no effect - it does not define Space connection and build does not use Space SCM")
                return
            }
        }

        this.spaceWebhookIds = allRepositories.groupBy { it.spaceConnection }.flatMap { (spaceConnection, repositories) ->
            try {
                SpaceApiClient(spaceConnection).use { spaceApiClient ->
                    val existingWebhooks = spaceApiClient.getRegisteredWebhooks()
                    val webhookNames = repositories.map { spaceApiClient.getWebhookName(it.projectKey, it.repositoryName, id) }.toSet()
                    existingWebhooks
                        .filter { !webhookNames.contains(it.webhook.name) }
                        .forEach { spaceApiClient.deleteWebhook(it.webhook.id) }
                    repositories.map { repo ->
                        val subscription = createSubscription(repo.projectKey, repo.repositoryName, spaceApiClient)
                        val spaceWebhookId = spaceApiClient.ensureTriggerWebhook(repo.projectKey, repo.repositoryName, id, subscription, existingWebhooks)
                        spaceWebhookId
                    }
                }
            } catch (ex: Throwable) {
                LOGGER.log(Level.WARNING, "Error while setting up webhook in Space", ex)
                emptyList()
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun createSubscription(projectKey: String, repositoryName: String, spaceApiClient: SpaceApiClient): CustomGenericSubscriptionIn {
        val projectId = spaceApiClient.getProjectIdByKey(projectKey)
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
        fun doFillProjectKeyItems(@QueryParameter spaceConnectionId: String?): HttpResponse {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId)
        }

        @POST
        fun doFillRepositoryNameItems(
            @QueryParameter spaceConnectionId: String?,
            @QueryParameter projectKey: String?
        ): HttpResponse {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey)
        }
    }

    class SpaceRepository @DataBoundConstructor constructor(var spaceConnectionId: String, var projectKey: String, var repositoryName: String)

    companion object {
        @OptIn(ExperimentalSpaceSdkApi::class)
        fun processWebhookPayload(
            request: StaplerRequest,
            spaceAppInstanceStorage: SpaceAppInstanceStorage,
            getJobTriggers: (ParameterizedJobMixIn.ParameterizedJob<*, *>) -> List<Trigger<*>>
        ): Pair<Int, String?>? {
            val requestAdapter = RequestAdapter(request)
            runBlocking {
                Space.processPayload(requestAdapter, ktorClientForSpace, spaceAppInstanceStorage) { payload ->
                    if (payload !is WebhookRequestPayload) {
                        LOGGER.warning("Got payload of type " + payload.javaClass.getSimpleName())
                        return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                    }

                    val (job, trigger) = Jenkins.get().getAllItems(ParameterizedJobMixIn.ParameterizedJob::class.java)
                        .firstNotNullOfOrNull { job ->
                            getJobTriggers(job)
                                .filterIsInstance<SpaceWebhookTrigger>()
                                .firstOrNull { it.spaceWebhookIds?.contains(payload.webhookId) ?: false }
                                ?.let { job to it }
                        }
                        ?: run {
                            LOGGER.info("No registered trigger found for webhook id = ${payload.webhookId}")
                            return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                        }

                    val triggerItem = SCMTriggerItems.asSCMTriggerItem(job)
                    if (triggerItem == null) {
                        LOGGER.info("Cannot trigger item of type ${job::class.qualifiedName}")
                        return@processPayload SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
                    }

                    val causeAction = CauseAction(SpaceWebhookTriggerCause(trigger.triggerType))
                    triggerItem.scheduleBuild2(job.quietPeriod, causeAction)

                    SpaceHttpResponse.RespondWithOk
                }
            }
            return requestAdapter.httpStatusCode?.let { it to requestAdapter.body }
        }
    }
}

private val ktorClientForSpace = ktorClientForSpace();

@OptIn(ExperimentalSpaceSdkApi::class)
private class RequestAdapter(private val request: StaplerRequest) : space.jetbrains.api.runtime.helpers.RequestAdapter {
    var httpStatusCode: Int? = null
    var body: String? = null;

    override fun getHeader(headerName: String): String? {
        return request.getHeader(headerName)
    }

    override suspend fun receiveText(): String {
        @Suppress("BlockingMethodInNonBlockingContext")
        return request.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
    }

    override suspend fun respond(httpStatusCode: Int, body: String) {
        this.httpStatusCode = httpStatusCode
        this.body = body
    }
}

private val LOGGER = Logger.getLogger(SpaceWebhookTrigger::class.java.name)
