package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;

import static jenkins.triggers.SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem;

public class SpaceWebhookTrigger extends Trigger<Job<?, ?>> {

    private final boolean triggerOnBranches;
    private TriggerOnMergeRequests triggerOnMergeRequests;
    private SpaceRepository customSpaceRepository;

    @DataBoundConstructor
    public SpaceWebhookTrigger(boolean triggerOnBranches) {
        this.triggerOnBranches = triggerOnBranches;
    }

    public boolean getTriggerOnBranches() {
        return triggerOnBranches;
    }

    public TriggerOnMergeRequests getTriggerOnMergeRequests() {
        return triggerOnMergeRequests;
    }

    @DataBoundSetter
    public void setTriggerOnMergeRequests(TriggerOnMergeRequests triggerOnMergeRequests) {
        this.triggerOnMergeRequests = (triggerOnMergeRequests != null && !triggerOnMergeRequests.isDisabled()) ? triggerOnMergeRequests : null;
    }

    public SpaceRepository getCustomSpaceRepository() {
        return customSpaceRepository;
    }

    public void setCustomSpaceRepository(SpaceRepository customSpaceRepository) {
        this.customSpaceRepository = customSpaceRepository;
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsValidator;

        @Override
        public String getDisplayName() {
            return "JetBrains Space webhook trigger";
        }

        @Override
        public boolean isApplicable(Item item) {
            return asSCMTriggerItem(item) != null;
        }

        @POST
        public ListBoxModel doFillSpaceConnectionIdItems() {
            return scmParamsValidator.doFillSpaceConnectionIdItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            return scmParamsValidator.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            return scmParamsValidator.doFillRepositoryNameItems(spaceConnectionId, projectKey);
        }
    }

    public static class SpaceRepository {

        private final String spaceConnectionId;
        private final String projectKey;
        private final String repositoryName;

        @DataBoundConstructor
        public SpaceRepository(String spaceConnectionId, String projectKey, String repositoryName) {
            this.spaceConnectionId = spaceConnectionId;
            this.projectKey = projectKey;
            this.repositoryName = repositoryName;
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
    }

    public static class TriggerOnMergeRequests {
        private final boolean onCreate;
        private final boolean onPushToSourceBranch;
        private final boolean onPushToTargetBranch;
        private final boolean onApprovedByAllReviewers;
        private MergeRequestTitlePrefixFilter titlePrefixFilter;

        @DataBoundConstructor
        public TriggerOnMergeRequests(
                boolean onCreate,
                boolean onPushToSourceBranch,
                boolean onPushToTargetBranch,
                boolean onApprovedByAllReviewers
        ) {
            this.onCreate = onCreate;
            this.onPushToSourceBranch = onPushToSourceBranch;
            this.onPushToTargetBranch = onPushToTargetBranch;
            this.onApprovedByAllReviewers = onApprovedByAllReviewers;
        }

        public boolean getOnCreate() {
            return onCreate;
        }

        public boolean getOnPushToSourceBranch() {
            return onPushToSourceBranch;
        }

        public boolean getOnPushToTargetBranch() {
            return onPushToTargetBranch;
        }

        public boolean isOnApprovedByAllReviewers() {
            return onApprovedByAllReviewers;
        }

        public MergeRequestTitlePrefixFilter getTitlePrefixFilter() {
            return titlePrefixFilter;
        }

        @DataBoundSetter
        public void setTitlePrefixFilter(MergeRequestTitlePrefixFilter titlePrefixFilter) {
            this.titlePrefixFilter = (titlePrefixFilter != null && !titlePrefixFilter.isEmpty()) ? titlePrefixFilter : null;
        }

        public boolean isDisabled() {
            return !onCreate && !onPushToSourceBranch && !onPushToTargetBranch && !onApprovedByAllReviewers;
        }
    }

    public static class MergeRequestTitlePrefixFilter {
        private final String include;
        private final String exclude;

        @DataBoundConstructor
        public MergeRequestTitlePrefixFilter(String include, String exclude) {
            this.include = blankToNull(include);
            this.exclude = blankToNull(exclude);
        }

        public String getInclude() {
            return include;
        }

        public String getExclude() {
            return exclude;
        }

        public boolean isEmpty() {
            return include == null && exclude == null;
        }
    }

    @Nullable
    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }
}
