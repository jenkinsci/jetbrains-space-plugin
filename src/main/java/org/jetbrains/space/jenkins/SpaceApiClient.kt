package org.jetbrains.space.jenkins

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import hudson.model.Result
import hudson.model.Run
import hudson.security.ACL
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.jetbrains.space.jenkins.config.SpaceApiCredentials
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.trigger.SpaceWebhookEndpoint
import space.jetbrains.api.runtime.Option
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import java.io.Closeable
import java.util.*

fun spaceApiClient(baseUrl: String, apiCredentialId: String): SpaceClient {
    val creds = CredentialsMatchers.firstOrNull(
        CredentialsProvider.lookupCredentialsInItemGroup(
            SpaceApiCredentials::class.java,
            Jenkins.get(),
            ACL.SYSTEM2,
            URIRequirementBuilder.fromUri(baseUrl).build()
        ),
        CredentialsMatchers.withId(apiCredentialId)
    ) ?: error("No credentials found for credentialsId: $apiCredentialId")

    val appInstance = SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, baseUrl)
    return SpaceClient(appInstance, SpaceAuth.ClientCredentials())
}

suspend fun SpaceClient.postBuildStatus(action: SpaceGitScmCheckoutAction, build: Run<*, *>, status: CommitExecutionStatus?) {
    projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
        project = ProjectIdentifier.Key(action.projectKey),
        repository = action.repositoryName,
        revision = action.revision,
        branch = action.branch,
        changes = emptyList(),
        executionStatus = status ?: SpaceApiClient.getStatusFromBuild(build),
        url = build.absoluteUrl,
        externalServiceName = "Jenkins",
        taskName = build.parent.fullName,
        taskId = build.parent.fullName,
        taskBuildId = build.getNumber().toString(),
        timestamp = build.startTimeInMillis,
        description = build.description
    )
}

class SpaceApiClient : Closeable {
    private val spaceApiClient: SpaceClient

    constructor(spaceConnection: SpaceConnection) {
        this.spaceApiClient = spaceConnection.getApiClient()
    }

    fun getProjectIdByKey(projectKey: String): String {
        return runBlocking {
            spaceApiClient.projects.getProject(ProjectIdentifier.Key(projectKey)).id
        }
    }

    fun postBuildStatus(action: SpaceGitScmCheckoutAction, build: Run<*, *>, status: CommitExecutionStatus?) {
        runBlocking {
            spaceApiClient.projects.repositories.revisions.externalChecks.reportExternalCheckStatus(
                project = ProjectIdentifier.Key(action.projectKey),
                repository = action.repositoryName,
                revision = action.revision,
                branch = action.branch,
                changes = emptyList(),
                executionStatus = status ?: getStatusFromBuild(build),
                url = build.absoluteUrl,
                externalServiceName = "Jenkins",
                taskName = build.parent.fullName,
                taskId = build.parent.fullName,
                taskBuildId = build.getNumber().toString(),
                timestamp = build.startTimeInMillis,
                description = build.description
            )
        }
    }

    fun getRegisteredWebhooks(): List<FullWebhookDTO> {
        return runBlocking {
            spaceApiClient.applications.webhooks.getAllWebhooks(ApplicationIdentifier.Me) {
                webhook {
                    id()
                    name()
                }
            }.data
        }
    }

    fun getWebhookName(projectKey: String, repositoryName: String, triggerId: String): String {
        return "$triggerId|$projectKey|$repositoryName"
    }

    fun ensureTriggerWebhook(
        projectKey: String,
        repositoryName: String,
        triggerId: String,
        subscription: CustomGenericSubscriptionIn,
        existingWebhooks: List<FullWebhookDTO>
    ): String {
        val webhookName = getWebhookName(projectKey, repositoryName, triggerId)
        val description = "Auto-generated webhook for triggering builds in Jenkins"
        val rootUrl = Jenkins.get().rootUrl
            ?: throw IllegalStateException("Jenkins instance has no root url specified")

        val triggerUrl = "${rootUrl.trimEnd('/')}/${SpaceWebhookEndpoint.URL}/trigger"

        val existing = existingWebhooks.firstOrNull { it.webhook.name == webhookName }
        return runBlocking {
            if (existing == null) {
                val webhookRecord = spaceApiClient.applications.webhooks.createWebhook(
                    application = ApplicationIdentifier.Me,
                    name = webhookName,
                    description = description,
                    endpoint = EndpointCreateDTO(triggerUrl, Jenkins.get().isRootUrlSecure),
                    subscriptions = listOf(SubscriptionDefinition(webhookName, subscription))
                )
                webhookRecord.id
            } else {
                val webhookId = existing.webhook.id
                spaceApiClient.applications.webhooks.updateWebhook(
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

                val existingSubscriptions = spaceApiClient.applications.webhooks.subscriptions.getAllSubscriptions(
                    ApplicationIdentifier.Me,
                    webhookId
                )
                val subscriptionId: String
                if (existingSubscriptions.size == 1) {
                    subscriptionId = existingSubscriptions[0].id
                    spaceApiClient.applications.webhooks.subscriptions.updateSubscription(
                        application = ApplicationIdentifier.Me,
                        webhookId = webhookId,
                        subscriptionId = subscriptionId,
                        name = webhookName,
                        enabled = true,
                        subscription = subscription
                    )
                } else {
                    for (s in existingSubscriptions) {
                        spaceApiClient.applications.webhooks.subscriptions.deleteSubscription(
                            ApplicationIdentifier.Me,
                            webhookId,
                            s.id
                        )
                    }
                    val newSubscription = spaceApiClient.applications.webhooks.subscriptions.createSubscription(
                        ApplicationIdentifier.Me,
                        webhookId,
                        webhookName,
                        subscription
                    )
                    subscriptionId = newSubscription.id
                }

                spaceApiClient.applications.webhooks.subscriptions.requestMissingRights(
                    ApplicationIdentifier.Me, webhookId, subscriptionId
                )
                webhookId
            }
        }
    }

    fun deleteWebhook(id: String) {
        runBlocking {
            spaceApiClient.applications.webhooks.deleteWebhook(ApplicationIdentifier.Me, id)
        }
    }

    override fun close() {
        spaceApiClient.close()
    }

    companion object {
        fun getStatusFromBuild(build: Run<*, *>): CommitExecutionStatus {
            val status = if (build.isBuilding) {
                CommitExecutionStatus.RUNNING
            } else if (successfulResults.contains(build.result)) {
                CommitExecutionStatus.SUCCEEDED
            } else if (cancelledResults.contains(build.result)) {
                CommitExecutionStatus.TERMINATED
            } else {
                CommitExecutionStatus.FAILED
            }
            return status
        }

        private val successfulResults: Collection<Result?> = Arrays.asList(Result.SUCCESS, Result.UNSTABLE)
        private val cancelledResults: Collection<Result?> = Arrays.asList(Result.ABORTED, Result.NOT_BUILT)
    }
}