package org.jetbrains.space.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.model.Result;
import hudson.model.Run;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.jetbrains.space.jenkins.config.SpaceApiCredentials;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction;
import space.jetbrains.api.runtime.*;
import space.jetbrains.api.runtime.resources.Applications;
import space.jetbrains.api.runtime.resources.Projects;
import space.jetbrains.api.runtime.resources.applications.Webhooks;
import space.jetbrains.api.runtime.types.*;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentialsInItemGroup;

public class SpaceApiClient implements Closeable {

    private final SpaceClient spaceApiClient;
    private final Projects projectsApi;

    public SpaceApiClient(SpaceConnection spaceConnection) {
        this.spaceApiClient = spaceConnection.getApiClient();
        this.projectsApi = new Projects(this.spaceApiClient);
    }

    public SpaceApiClient(String baseUrl, String apiCredentialId) {
        SpaceApiCredentials creds = firstOrNull(
                lookupCredentialsInItemGroup(
                        SpaceApiCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(baseUrl).build()),
                CredentialsMatchers.withId(apiCredentialId)
        );
        if (creds == null) {
            throw new IllegalStateException("No credentials found for credentialsId: " + apiCredentialId);
        }

        SpaceAppInstance appInstance = new SpaceAppInstance(creds.getClientId(), creds.getClientSecret().getPlainText(), baseUrl);
        this.spaceApiClient = new SpaceClient(appInstance, new SpaceAuth.ClientCredentials(), c -> Unit.INSTANCE);
        this.projectsApi = new Projects(this.spaceApiClient);
    }

    public void testConnection() throws InterruptedException {
        callApi(c -> new Applications(spaceApiClient).reportApplicationAsHealthy(c));
    }

    public String getProjectIdByKey(String projectKey) throws InterruptedException {
        PR_Project project = callApi(c -> projectsApi.getProject(new ProjectIdentifier.Key(projectKey), p -> Unit.INSTANCE, c));
        return project.getId();
    }

    public void postBuildStatus(SpaceGitScmCheckoutAction action, Run<?, ?> build, CommitExecutionStatus status) throws InterruptedException {
        BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> projectsApi.getRepositories().getRevisions().getExternalChecks().reportExternalCheckStatus(
                        new ProjectIdentifier.Key(action.getProjectKey()),
                        action.getRepositoryName(),
                        action.getRevision(),
                        action.getBranch(),
                        Collections.emptyList(),
                        (status != null) ? status : getStatusFromBuild(build),
                        build.getAbsoluteUrl(),
                        "Jenkins",
                        build.getParent().getFullName(),
                        build.getParent().getFullName(),
                        Integer.toString(build.getNumber()),
                        build.getStartTimeInMillis(),
                        build.getDescription(),
                        continuation
                )
        );
    }

    public List<FullWebhookDTO> getRegisteredWebhooks() throws InterruptedException {
        Batch<FullWebhookDTO> webhooks = callApi(c ->
                new Applications(spaceApiClient).getWebhooks().getAllWebhooks(
                        ApplicationIdentifier.Me.INSTANCE, false, "", null,
                        h -> {
                            h.webhook_WebhookRecordPartial(r -> {
                                r.id();
                                r.name();
                                return Unit.INSTANCE;
                            });
                            return Unit.INSTANCE;
                        }, c));
        return webhooks.getData();
    }

    public String getWebhookName(String projectKey, String repositoryName, String triggerId) {
        return triggerId + "|" + projectKey + "|" + repositoryName;
    }

    public void ensureTriggerWebhook(String projectKey, String repositoryName, String triggerId, CustomGenericSubscriptionIn subscription, List<FullWebhookDTO> existingWebhooks) throws InterruptedException {
        String webhookName = getWebhookName(projectKey, repositoryName, triggerId);
        String description = "Auto-generated webhook for triggering builds in Jenkins";
        String rootUrl = Jenkins.get().getRootUrl();
        if (rootUrl == null) {
            throw new IllegalStateException("Jenkins instance has no root url specified");
        }

        String triggerUrl = rootUrl.replaceAll("/$", "") + "/jbspace/trigger";

        Webhooks webhooksApi = new Applications(spaceApiClient).getWebhooks();
        Optional<FullWebhookDTO> existing = existingWebhooks.stream().filter(webhook -> webhook.getWebhook().getName().equals(webhookName)).findFirst();
        if (existing.isEmpty()) {
            callApi(continuation ->
                    webhooksApi.createWebhook(
                            ApplicationIdentifier.Me.INSTANCE,
                            webhookName,
                            description,
                            new EndpointCreateDTO(triggerUrl, Jenkins.get().isRootUrlSecure()),
                            null,
                            true,
                            Arrays.asList(200, 201),
                            true,
                            null,
                            null,
                            List.of(new SubscriptionDefinition(webhookName, subscription)),
                            webhook -> Unit.INSTANCE,
                            continuation
                    ));
        } else {
            String webhookId = existing.get().getWebhook().getId();
            callApi(continuation ->
                    webhooksApi.updateWebhook(
                            ApplicationIdentifier.Me.INSTANCE,
                            webhookId,
                            webhookName,
                            new Option.Value<>(description),
                            true,
                            new Option.Value<>(new ExternalEndpointUpdateDTO(new Option.Value<>(triggerUrl), Jenkins.get().isRootUrlSecure())),
                            new Option.Value<>(null),
                            Arrays.asList(200, 201),
                            true,
                            new Option.Value<>(null),
                            new Option.Value<>(null),
                            continuation
                    ));

            List<SubscriptionDTO> existingSubscriptions = callApi(c ->
                    webhooksApi.getSubscriptions().getAllSubscriptions(ApplicationIdentifier.Me.INSTANCE, webhookId, s -> Unit.INSTANCE, c)
            );
            String subscriptionId;
            if (existingSubscriptions.size() == 1) {
                subscriptionId = existingSubscriptions.get(0).getId();
                callApi(continuation ->
                        webhooksApi.getSubscriptions().updateSubscription(
                                ApplicationIdentifier.Me.INSTANCE,
                                webhookId,
                                subscriptionId,
                                webhookName,
                                true,
                                subscription,
                                s -> Unit.INSTANCE,
                                continuation
                        ));
            } else {
                for (SubscriptionDTO s : existingSubscriptions) {
                    callApi(c ->
                            webhooksApi.getSubscriptions().deleteSubscription(
                                    ApplicationIdentifier.Me.INSTANCE, webhookId, s.getId(), c));
                }
                SubscriptionDTO newSubscription = callApi(continuation ->
                        webhooksApi.getSubscriptions().createSubscription(
                                ApplicationIdentifier.Me.INSTANCE,
                                webhookId,
                                webhookName,
                                subscription,
                                s -> Unit.INSTANCE,
                                continuation
                        ));
                subscriptionId = newSubscription.getId();
            }

            callApi(c ->
                    webhooksApi.getSubscriptions().requestMissingRights(
                            ApplicationIdentifier.Me.INSTANCE, webhookId, subscriptionId, c));
        }
    }

    public void deleteWebhook(String id) throws InterruptedException {
        callApi(c -> new Applications(spaceApiClient).getWebhooks().deleteWebhook(ApplicationIdentifier.Me.INSTANCE, id, c));
    }

    public static CommitExecutionStatus getStatusFromBuild(Run<?, ?> build) {
        CommitExecutionStatus status;
        if (build.isBuilding()) {
            status = CommitExecutionStatus.RUNNING;
        } else if (successfulResults.contains(build.getResult())) {
            status = CommitExecutionStatus.SUCCEEDED;
        } else if (cancelledResults.contains(build.getResult())) {
            status = CommitExecutionStatus.TERMINATED;
        } else {
            status = CommitExecutionStatus.FAILED;
        }
        return status;
    }

    private static final Collection<Result> successfulResults = Arrays.asList(Result.SUCCESS, Result.UNSTABLE);
    private static final Collection<Result> cancelledResults = Arrays.asList(Result.ABORTED, Result.NOT_BUILT);

    private <T> T callApi(CallApiFunction<T> block) throws InterruptedException {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> block.apply(continuation));
    }

    @Override
    public void close() throws IOException {
        this.spaceApiClient.close();
    }

    @FunctionalInterface
    private interface CallApiFunction<T> {
        Object apply(Continuation<? super T> continuation);
    }
}
