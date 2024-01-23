package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import javax.inject.Inject;
import java.util.UUID;
import jenkins.triggers.SCMTriggerItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * <p>
 * Trigger that runs a job in Jenkins whenever new commits are pushed to a git branch or a merge request is updated in Space.
 * The trigger installs webhooks on Space side and relies on them for listening to the events in Space.
 * </p>
 *
 * <p>Handling of the incoming webhook event is handled by the {@link SpaceWebhookEndpoint} class.</p>
 */
public class SpaceWebhookTrigger extends Trigger<Job<?, ?>> {

    @DataBoundConstructor
    public SpaceWebhookTrigger(String id, String spaceConnectionId, String projectKey, String repositoryName) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
        this.spaceConnectionId = spaceConnectionId;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.triggerType = SpaceWebhookTriggerType.Branches;
    }

    private final String id;
    private final String spaceConnectionId;
    private final String projectKey;
    private final String repositoryName;

    private SpaceWebhookTriggerType triggerType;
    private String spaceWebhookId;
    private String branchSpec = "";

    private boolean mergeRequestApprovalsRequired;
    private String mergeRequestTitleRegex = "";
    private String mergeRequestSourceBranchSpec = "";

    public String getId() {
        return id;
    }

    public String getSpaceWebhookId() {
        return this.spaceWebhookId;
    }

    public Job<?, ?> getJob() {
        return this.job;
    }

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    @NotNull
    public SpaceWebhookTriggerType getTriggerType() {
        return triggerType;
    }

    @DataBoundSetter
    public void setTriggerType(SpaceWebhookTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getBranchSpec() {
        return branchSpec;
    }

    @DataBoundSetter
    public void setBranchSpec(String branchSpec) {
        this.branchSpec = branchSpec;
    }

    public boolean isMergeRequestApprovalsRequired() {
        return mergeRequestApprovalsRequired;
    }

    @DataBoundSetter
    public void setMergeRequestApprovalsRequired(boolean mergeRequestApprovalsRequired) {
        this.mergeRequestApprovalsRequired = mergeRequestApprovalsRequired;
    }

    public String getMergeRequestTitleRegex() {
        return mergeRequestTitleRegex;
    }

    @DataBoundSetter
    public void setMergeRequestTitleRegex(String mergeRequestTitleRegex) {
        this.mergeRequestTitleRegex = mergeRequestTitleRegex;
    }

    public String getMergeRequestSourceBranchSpec() {
        return mergeRequestSourceBranchSpec;
    }

    @DataBoundSetter
    public void setMergeRequestSourceBranchSpec(String mergeRequestSourceBranchSpec) {
        this.mergeRequestSourceBranchSpec = mergeRequestSourceBranchSpec;
    }

    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        super.start(project, newInstance);
        if (id == null || (!newInstance && spaceWebhookId != null))
            return;

        ensureSpaceWebhook();
    }

    /**
     * <p>Ensures that webhook for this trigger is installed properly on the Space side.
     * The id of the resulting Space webhook is persisted along with the trigger parameters
     * to quickly match an arrived event with the webhook that caused it.</p>
     *
     * <p>Handling of the incoming webhook event is handled by the {@link SpaceWebhookEndpoint} class.</p>
     */
    public void ensureSpaceWebhook() {
        this.spaceWebhookId = SpaceWebhookTriggerKt.ensureAndGetSpaceWebhookId(this);
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        public DescriptorImpl() {
            load();
        }

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        @Inject
        public SpacePluginConfiguration spacePluginConfiguration;

        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains Space webhook trigger";
        }

        @Override
        public boolean isApplicable(Item item) {
            return SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(item) != null;
        }

        @POST
        public ListBoxModel doFillSpaceConnectionIdItems() {
            return scmParamsProvider.doFillSpaceConnectionIdItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey);
        }
    }
}
