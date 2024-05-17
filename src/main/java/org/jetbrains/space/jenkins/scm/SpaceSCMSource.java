package org.jetbrains.space.jenkins.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Action;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.scm.api.*;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.impl.form.NamedArrayList;
import org.jenkinsci.Symbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.*;
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerKt;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.types.ProjectIdentifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements JetBrains SpaceCode branch source for multibranch projects.
 * An instance of a branch source is added to a multibranch pipeline project, configured and used to discover the branches and merge requests
 * to automatically create child jobs for.
 *
 * @see <a href="https://www.jenkins.io/doc/book/pipeline/multibranch/">Multibranch pipelines in Jenkins</a>
 * @see <a href="https://github.com/jenkinsci/scm-api-plugin/blob/master/docs/implementation.adoc">SCM API implementation guide</a>
 */
public class SpaceSCMSource extends SCMSource {

    @DataBoundConstructor
    public SpaceSCMSource(String spaceConnectionId, String projectKey, String repository) {
        this.spaceConnectionId = spaceConnectionId;
        this.projectKey = projectKey;
        this.repository = repository;
        this.traits = new ArrayList<>();
    }

    private final String spaceConnectionId;
    private final String projectKey;
    private final String repository;

    private SpaceSCMSourceType type;

    private String branchSpec = "";

    private String mergeRequestTitleRegex = "";
    private String mergeRequestSourceBranchSpec = "";
    private String mergeRequestTargetBranchSpec = "";

    private String spaceWebhookId;

    @NonNull
    private List<SCMSourceTrait> traits;


    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getRepository() {
        return repository;
    }

    public String getSpaceWebhookId() {
        return this.spaceWebhookId;
    }

    @DataBoundSetter
    public void setSpaceWebhookId(String spaceWebhookId) {
        this.spaceWebhookId = spaceWebhookId;
    }

    @NonNull
    public SpaceSCMSourceType getType() {
        return type;
    }

    @DataBoundSetter
    public void setType(SpaceSCMSourceType type) {
        this.type = type;
    }

    public String getBranchSpec() {
        return branchSpec;
    }

    @DataBoundSetter
    public void setBranchSpec(String branchSpec) {
        this.branchSpec = branchSpec;
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

    public String getMergeRequestTargetBranchSpec() {
        return mergeRequestTargetBranchSpec;
    }

    @DataBoundSetter
    public void setMergeRequestTargetBranchSpec(String mergeRequestTargetBranchSpec) {
        this.mergeRequestTargetBranchSpec = mergeRequestTargetBranchSpec;
    }

    @Override
    public @NotNull List<SCMSourceTrait> getTraits() {
        return traits;
    }

    /**
     * Creates a probe object that should check whether Jenkins job should be created for a given revision by the multibranch project branch source
     */
    @Override
    protected @NotNull SCMProbe createProbe(@NotNull SCMHead head, @CheckForNull SCMRevision revision) {
        SCMSourceOwner owner = getOwner();
        if (owner == null)
            throw new RuntimeException("No owner multibranch project found for the branch source");

        SpaceClient spaceClient = SpacePluginConfigurationKt.getSpaceApiClientForMultiBranchProject(
                owner.getFullName(),
                spaceConnectionId,
                projectKey
        );
        if (spaceClient == null)
            throw new RuntimeException("Space connection is not configured");

        return new SpaceSCMProbe(head, spaceClient, new ProjectIdentifier.Key(projectKey), repository, true);
    }

    /**
     * Returns the list of actions to be applied to the job created by this branch source for a given head.
     */
    @NotNull
    @Override
    protected List<Action> retrieveActions(@NotNull SCMHead head, SCMHeadEvent event, @NotNull TaskListener listener) {
        return SpaceSCMSourceKt.retrieveActions(this, head);
    }

    /**
     * Performs the discovery of heads (branches and merge requests) to create jobs for
     */
    @Override
    protected void retrieve(SCMSourceCriteria criteria, @NotNull SCMHeadObserver observer, SCMHeadEvent<?> event, @NotNull TaskListener listener) throws IOException, InterruptedException {
        SpaceSCMSourceKt.retrieve(this, criteria, observer, event, listener);
        ensureSpaceWebhook();
    }

    /**
     * Ensures that a webhook is present and properly configured on the SpaceCode application for this branch source
     * to listen to the events that potentiall affect the list of discovered heads
     */
    public void ensureSpaceWebhook() throws IOException {
        SCMSourceOwner owner = getOwner();
        if (owner == null)
            throw new RuntimeException("No owner multibranch project found for the branch source");

        this.spaceWebhookId = SpaceWebhookTriggerKt.ensureAndGetSpaceWebhookId(this);
        owner.save();
    }

    @NotNull
    @Override
    public SCM build(@NotNull SCMHead head, SCMRevision revision) {
        return new SpaceSCM(null, null, null);
    }

    @DataBoundSetter
    public void setTraits(@CheckForNull List<SCMSourceTrait> traits) {
        this.traits = new ArrayList<>(Util.fixNull(traits));
    }

    @Symbol("jetbrains-space-code")
    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {

        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains SpaceCode";
        }

        /**
         * Available traits for additional SCM behaviours
         */
        public List<NamedArrayList<? extends SCMSourceTraitDescriptor>> getTraitsDescriptorLists() {
            List<NamedArrayList<? extends SCMSourceTraitDescriptor>> result = new ArrayList<>();
            List<SCMSourceTraitDescriptor> descriptors = SCMSourceTrait._for(this, SCMSourceContext.class, GitSCMBuilder.class);
            NamedArrayList.select(descriptors, "Git", null, true, result);
            return result;
        }
    }
}
