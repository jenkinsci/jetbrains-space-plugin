package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;
import java.util.List;
import java.util.UUID;

import static jenkins.triggers.SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem;

public class SpaceWebhookTrigger extends Trigger<Job<?,?>> {

    @DataBoundConstructor
    public SpaceWebhookTrigger(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
        this.triggerType = SpaceWebhookTriggerType.Branches;
    }

    private String id;
    private CustomSpaceRepository customSpaceRepository = null;
    private SpaceWebhookTriggerType triggerType;
    private List<String> spaceWebhookIds;
    private String branchSpec = "";

    private boolean mergeRequestApprovalsRequired;
    private String mergeRequestTitleRegex = "";
    private String mergeRequestSourceBranchSpec = "";

    public String getId() {
        return id;
    }

    public List<String> getSpaceWebhookIds() {
        return this.spaceWebhookIds;
    }

    public void setSpaceWebhookIds(List<String> spaceWebhookIds) {
        this.spaceWebhookIds = spaceWebhookIds;
    }

    public Job<?, ?> getJob() {
        return this.job;
    }

    public CustomSpaceRepository getCustomSpaceRepository() {
        return customSpaceRepository;
    }

    @DataBoundSetter
    public void setCustomSpaceRepository(CustomSpaceRepository customSpaceRepository) {
        this.customSpaceRepository = customSpaceRepository;
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

    public String getMergeRequestBranchesSpec() {
        return mergeRequestSourceBranchSpec;
    }

    @DataBoundSetter
    public void setMergeRequestBranchesSpec(String mergeRequestSourceBranchSpec) {
        this.mergeRequestSourceBranchSpec = mergeRequestSourceBranchSpec;
    }

    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        super.start(project, newInstance);
        if (id == null || (!newInstance && spaceWebhookIds != null && !spaceWebhookIds.isEmpty()))
            return;

        ensureSpaceWebhooks();
    }

    public void ensureSpaceWebhooks() {
        this.spaceWebhookIds = SpaceWebhookTriggerKt.ensureAndGetSpaceWebhooks(this);
    }

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
            return asSCMTriggerItem(item) != null;
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
