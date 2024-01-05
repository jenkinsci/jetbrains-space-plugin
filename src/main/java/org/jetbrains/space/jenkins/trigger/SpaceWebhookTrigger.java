package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import jenkins.triggers.SCMTriggerItem;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.space.jenkins.SpaceApiClient;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCM;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.types.*;

import javax.inject.Inject;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static jenkins.triggers.SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem;
import static org.apache.commons.lang.StringUtils.isBlank;

public class SpaceWebhookTrigger extends Trigger<Job<?,?>> {

    private static final Logger LOGGER = Logger.getLogger(SpaceWebhookTrigger.class.getName());

    private final String id;
    private SpaceRepository customSpaceRepository;
    private SpaceWebhookTriggerType triggerType;

    private String branchSpec;

    private boolean onMergeRequestCreate;
    private boolean onMergeRequestPushToSourceBranch;
    private boolean onMergeRequestPushToTargetBranch;
    private boolean onMergeRequestApprovedByAllReviewers;
    private String mergeRequestTitleRegex;
    private String mergeRequestSourceBranchSpec;


    @DataBoundConstructor
    public SpaceWebhookTrigger(String id) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.triggerType = SpaceWebhookTriggerType.Branches;
    }

    public String getId() {
        return id;
    }

    public SpaceRepository getCustomSpaceRepository() {
        return customSpaceRepository;
    }

    @DataBoundSetter
    public void setCustomSpaceRepository(SpaceRepository customSpaceRepository) {
        this.customSpaceRepository = customSpaceRepository;
    }

    public SpaceWebhookTriggerType getTriggerType() {
        return triggerType;
    }

    @DataBoundSetter
    public void setTriggerType(SpaceWebhookTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public boolean getOnMergeRequestCreate() {
        return onMergeRequestCreate;
    }

    @DataBoundSetter
    public void setOnMergeRequestCreate(boolean onMergeRequestCreate) {
        this.onMergeRequestCreate = onMergeRequestCreate;
    }

    public boolean getOnMergeRequestPushToSourceBranch() {
        return onMergeRequestPushToSourceBranch;
    }

    @DataBoundSetter
    public void setOnMergeRequestPushToSourceBranch(boolean onMergeRequestPushToSourceBranch) {
        this.onMergeRequestPushToSourceBranch = onMergeRequestPushToSourceBranch;
    }

    public boolean getOnMergeRequestPushToTargetBranch() {
        return onMergeRequestPushToTargetBranch;
    }

    @DataBoundSetter
    public void setOnMergeRequestPushToTargetBranch(boolean onMergeRequestPushToTargetBranch) {
        this.onMergeRequestPushToTargetBranch = onMergeRequestPushToTargetBranch;
    }

    public boolean getOnMergeRequestApprovedByAllReviewers() {
        return onMergeRequestApprovedByAllReviewers;
    }

    @DataBoundSetter
    public void setOnMergeRequestApprovedByAllReviewers(boolean onMergeRequestApprovedByAllReviewers) {
        this.onMergeRequestApprovedByAllReviewers = onMergeRequestApprovedByAllReviewers;
    }

    public String getMergeRequestTitleRegex() {
        return mergeRequestTitleRegex;
    }

    @DataBoundSetter
    public void setMergeRequestTitleRegex(String mergeRequestTitleRegex) {
        this.mergeRequestTitleRegex = blankToNull (mergeRequestTitleRegex);
    }

    public String getMergeRequestSourceBranchSpec() {
        return mergeRequestSourceBranchSpec;
    }

    @DataBoundSetter
    public void setMergeRequestSourceBranchSpec(String mergeRequestSourceBranchSpec) {
        this.mergeRequestSourceBranchSpec = blankToNull(mergeRequestSourceBranchSpec);
    }

    public String getBranchSpec() {
        return branchSpec;
    }

    @DataBoundSetter
    public void setBranchSpec(String branchSpec) {
        this.branchSpec = blankToNull(branchSpec);
    }



    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        super.start(project, newInstance);
        if (!newInstance)
            return;

        SCMTriggerItem triggerItem = asSCMTriggerItem(job);
        List<org.jetbrains.space.jenkins.config.SpaceRepository> allRepositories;
        if (customSpaceRepository != null) {
            Optional<SpaceConnection> spaceConnection = ((DescriptorImpl)getDescriptor()).spacePluginConfiguration.getConnectionById(customSpaceRepository.getSpaceConnectionId());
            if (spaceConnection.isEmpty()) {
                LOGGER.warning("Space connection not found by id = " + customSpaceRepository.getSpaceConnectionId());
                return;
            }
            allRepositories = List.of(
                    new org.jetbrains.space.jenkins.config.SpaceRepository(
                            spaceConnection.get(),
                            customSpaceRepository.getProjectKey(),
                            customSpaceRepository.getRepositoryName()
                    ));
        } else if (triggerItem != null) {
            allRepositories = triggerItem.getSCMs().stream()
                    .filter(scm -> scm instanceof SpaceSCM)
                    .map(scm -> (SpaceSCM)scm)
                    .map(scm -> ((DescriptorImpl) getDescriptor()).spacePluginConfiguration
                            .getConnectionById(scm.getSpaceConnectionId())
                            .map(connection -> new org.jetbrains.space.jenkins.config.SpaceRepository(
                                    connection,
                                    scm.getProjectKey(),
                                    scm.getRepositoryName()
                            ))
                            .orElseGet(() -> {
                                LOGGER.warning("Space connection not found by id = " + scm.getSpaceConnectionId());
                                return null;
                            })
                    )
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            LOGGER.warning("Space trigger has no effect - it does not define Space connection and build does not use Space SCM");
            return;
        }

        allRepositories.stream()
                .collect(groupingBy(org.jetbrains.space.jenkins.config.SpaceRepository::getSpaceConnection))
                .forEach((spaceConnection, repositories) -> {
                    try (SpaceApiClient spaceApiClient = new SpaceApiClient(spaceConnection)) {
                        List<FullWebhookDTO> existingWebhooks = spaceApiClient.getRegisteredWebhooks();
                        for (org.jetbrains.space.jenkins.config.SpaceRepository repo : repositories) {
                            CustomGenericSubscriptionIn subscription = createSubscription(repo.getProjectKey(), repo.getRepositoryName(), spaceApiClient);
                            spaceApiClient.ensureTriggerWebhook(repo.getProjectKey(), repo.getRepositoryName(), getId(), subscription, existingWebhooks);
                        }
                        Set<String> webhookNames = repositories.stream()
                                .map(r -> spaceApiClient.getWebhookName(r.getProjectKey(), r.getRepositoryName(), getId()))
                                .collect(Collectors.toSet());
                        for (FullWebhookDTO webhook: existingWebhooks) {
                            if (!webhookNames.contains(webhook.getWebhook().getName())) {
                                spaceApiClient.deleteWebhook(webhook.getWebhook().getId());
                            }
                        }
                    } catch (Throwable ex) {
                        LOGGER.log(Level.WARNING, "Error while setting up webhook in Space", ex);
                    }
                });
    }

    private CustomGenericSubscriptionIn createSubscription(String projectKey, String repositoryName, SpaceApiClient spaceApiClient) throws InterruptedException {
        String eventSubjectCode;
        List<SubscriptionFilterIn> filters = new ArrayList<>();
        List<String> eventTypeCodes = new ArrayList<>();
        String projectId = spaceApiClient.getProjectIdByKey(projectKey);
        if (triggerType == SpaceWebhookTriggerType.MergeRequests) {
            eventSubjectCode = "CodeReview";

            if (onMergeRequestCreate)
                eventTypeCodes.add("CodeReview.Created");
            if (onMergeRequestPushToSourceBranch)
                eventTypeCodes.add("CodeReview.CommitsUpdated");
            if (onMergeRequestPushToTargetBranch)
                eventTypeCodes.add("CodeReview.TargetBranchUpdated");
            if (onMergeRequestApprovedByAllReviewers)
                eventTypeCodes.add("CodeReview.Participant.ChangesAccepted");

            filters.add(new CodeReviewSubscriptionFilterIn(
                    projectId,
                    repositoryName,
                    List.of(),
                    List.of(),
                    (mergeRequestSourceBranchSpec != null) ? List.of(mergeRequestSourceBranchSpec) : List.of(),
                    List.of(),
                    mergeRequestTitleRegex
            ));
        } else {
            eventSubjectCode = "Repository.Heads";
            eventTypeCodes.add("Repository.Heads");
            // TODO - pass branch specs
            filters.add(new RepoHeadsSubscriptionFilterIn(projectId, repositoryName));
        }
        return new CustomGenericSubscriptionIn(eventSubjectCode, filters, eventTypeCodes);
    }


    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        public DescriptorImpl() {
            load();
        }

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

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


    public static class SpaceRepository {

        private String spaceConnectionId;
        private String projectKey;
        private String repositoryName;

        @DataBoundConstructor
        public SpaceRepository() {}


        public String getSpaceConnectionId() {
            return spaceConnectionId;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public String getRepositoryName() {
            return repositoryName;
        }

        @DataBoundSetter
        public void setSpaceConnectionId(String spaceConnectionId) {
            this.spaceConnectionId = blankToNull(spaceConnectionId);
        }

        @DataBoundSetter
        public void setProjectKey(String projectKey) {
            this.projectKey = blankToNull(projectKey);
        }

        @DataBoundSetter
        public void setRepositoryName(String repositoryName) {
            this.repositoryName = blankToNull(repositoryName);
        }
    }

    public static class MergeRequestTitlePrefixFilter {
        private String include;
        private String exclude;

        @DataBoundConstructor
        public MergeRequestTitlePrefixFilter() {}

        public String getInclude() {
            return include;
        }

        public String getExclude() {
            return exclude;
        }

        public boolean isEmpty() {
            return include == null && exclude == null;
        }

        @DataBoundSetter
        public void setInclude(String include) {
            this.include = blankToNull(include);
        }

        @DataBoundSetter
        public void setExclude(String exclude) {
            this.exclude = blankToNull(exclude);
        }
    }

    @Nullable
    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }
}
