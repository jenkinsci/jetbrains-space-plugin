package org.jetbrains.space.jenkins

import io.ktor.util.collections.*
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.types.ApplicationIdentifier
import space.jetbrains.api.runtime.types.ApplicationsSubscriptionFilter
import space.jetbrains.api.runtime.types.ApplicationsSubscriptionFilterIn
import space.jetbrains.api.runtime.types.CustomGenericSubscriptionIn
import java.util.concurrent.CompletableFuture

/**
 * Singletone object that maintains the list of listeners and notifies them
 * whenever SpaceCode application permissions get updated on SpaceCode side.
 * The events are obtained from the webhook set up for the org-level SpaceCode application,
 * and the listeners match those events to the connected browser clients and notify them via server-side events stream
 * so that unapproved permissions warning can be removed in a live fashion as soon as permissions are approved in SpaceCode.
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-side events</a>
 */
object SpacePermissionsApproveListener {

    private val listeners = ConcurrentMap<String, CompletableFuture<Unit>>()

    /**
     * Returns a [CompletableFuture] that completes as soon as granted permissions set changes for the given SpaceCode application
     * (a corresponding webhook event arrives).
     */
    fun wait(appId: String): CompletableFuture<Unit> {
        return listeners.getOrPut(appId) { CompletableFuture<Unit>() }
    }

    /**
     * Is called whenever a webhook event arrives that signals the update to the granted permissions set for the SpaceCode application.
     */
    fun signal(appId: String) {
        listeners[appId]?.let {
            it.complete(Unit)
            listeners.remove(appId)
        }
    }

    /**
     * Name of the webhook that is installed into the org-level SpaceCode application.
     */
    const val SpaceWebhookName = "Authorizations"

    /**
     * Installs and configures the webhook for observing permission changes on SpaceCode side.
     * The webhook is installed into the org-level SpaceCode application, but is used to observe the updates for child project-level applications as well.
     * Each project-level app has then a separate subscription added to this webhook.
     */
    suspend fun ensureSpaceWebhook(parentSpaceClient: SpaceClient, childAppId: String, childAppName: String) {
        val webhook = parentSpaceClient.applications.webhooks
            .getAllWebhooks(ApplicationIdentifier.Me) {
                webhook {
                    id()
                    name()
                }
            }
            .data.firstOrNull { it.webhook.name == SpaceWebhookName }?.webhook
            ?: parentSpaceClient.applications.webhooks.createWebhook(ApplicationIdentifier.Me, SpaceWebhookName)

        parentSpaceClient.applications.webhooks.subscriptions.createSubscription(
            ApplicationIdentifier.Me,
            webhook.id,
            "$childAppName authorization",
            CustomGenericSubscriptionIn(
                "Application",
                listOf(ApplicationsSubscriptionFilterIn(childAppId)),
                listOf("Application.Authorized")
            )
        )
    }

    /**
     * Deletes subscription for the project-level application from the webhook observing application permission changes.
     * Called when the project-level connection is removed.
     */
    suspend fun removeSpaceWebhookSubscription(parentSpaceClient: SpaceClient, childAppId: String) {
        parentSpaceClient.applications.webhooks
            .getAllWebhooks(ApplicationIdentifier.Me) {
                webhook {
                    id()
                    name()
                }
            }
            .data.firstOrNull { it.webhook.name == SpaceWebhookName }
            ?.let { webhook ->
                parentSpaceClient.applications.webhooks.subscriptions.getAllSubscriptions(
                    ApplicationIdentifier.Me,
                    webhook.webhook.id
                ).firstOrNull {
                    it.subscription.subjectCode == "Application"
                            && it.subscription.filters
                                .filterIsInstance<ApplicationsSubscriptionFilter>()
                                .firstOrNull()?.application?.id == childAppId
                }?.let { subscription ->
                    parentSpaceClient.applications.webhooks.subscriptions.deleteSubscription(
                        ApplicationIdentifier.Me,
                        webhook.webhook.id,
                        subscription.id
                    )
                }
            }
    }
}
