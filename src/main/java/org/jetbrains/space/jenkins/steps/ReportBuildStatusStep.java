package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.types.CommitExecutionStatus;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ReportBuildStatusStep extends Step {

    private CommitExecutionStatus buildStatus;
    private String spaceConnection;
    private String spaceConnectionId;
    private String projectKey;
    private String repository;
    private String revision;
    private String branch;

    @DataBoundConstructor
    public ReportBuildStatusStep(String buildStatus) {
        this.buildStatus = (buildStatus != null) ? CommitExecutionStatus.valueOf(buildStatus) : null;
    }

    @Nullable
    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }

    public String getSpaceConnection() {
        return spaceConnection;
    }

    @DataBoundSetter
    public void setSpaceConnection(String spaceConnection) {
        this.spaceConnection = blankToNull(spaceConnection);
    }

    @CheckForNull
    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    @DataBoundSetter
    public void setSpaceConnectionId(String spaceConnectionId) {
        this.spaceConnectionId = blankToNull(spaceConnectionId);
    }

    @CheckForNull
    public String getProjectKey() {
        return projectKey;
    }

    @DataBoundSetter
    public void setProjectKey(String projectKey) {
        this.projectKey = blankToNull(projectKey);
    }

    @CheckForNull
    public String getRepository() {
        return repository;
    }

    @DataBoundSetter
    public void setRepository(String repository) {
        this.repository = blankToNull(repository);
    }

    @CheckForNull
    public String getBranch() {
        return branch;
    }

    @DataBoundSetter
    public void setBranch(String branch) {
        this.branch = blankToNull(branch);
    }

    @CheckForNull
    public String getRevision() {
        return revision;
    }

    @DataBoundSetter
    public void setRevision(String revision) {
        this.revision = blankToNull(revision);
    }

    public String getBuildStatus() {
        return (buildStatus != null) ? buildStatus.name() : null;
    }

    @DataBoundSetter
    public void setBuildStatus(String buildStatus) {
        this.buildStatus = CommitExecutionStatus.valueOf(buildStatus);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return ReportBuildStatusStepExecution.Companion.start(this, context, ((DescriptorImpl) getDescriptor()).spacePluginConfiguration);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        @Override
        public @NonNull String getDisplayName() {
            return "Post build status to JetBrains Space";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, TaskListener.class));
        }

        @Override
        public String getFunctionName() {
            return "postBuildStatusToSpace";
        }

        @POST
        public ListBoxModel doFillSpaceConnectionItems() {
            return scmParamsProvider.doFillSpaceConnectionNameItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnection) {
            if (spaceConnection.isBlank())
                return new ListBoxModel();
            return scmParamsProvider.doFillProjectKeyItems(null, spaceConnection);
        }

        @POST
        public HttpResponse doFillRepositoryItems(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (spaceConnection.isBlank())
                return new ListBoxModel();
            return scmParamsProvider.doFillRepositoryNameItems(null, spaceConnection, projectKey);
        }

        @POST
        public ListBoxModel doFillBuildStatusItems() {
            return CommitExecutionStatus.getEntries().stream()
                    .map(c -> new ListBoxModel.Option(c.name()))
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        public FormValidation doCheckProjectKey(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (!spaceConnection.isBlank() && projectKey.isBlank())
                return FormValidation.error("Choose project in Space");

            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckRepositoryName(@QueryParameter String spaceConnection, @QueryParameter String projectKey, @QueryParameter String repositoryName) {
            if (!spaceConnection.isBlank() && !projectKey.isBlank() && repositoryName.isBlank())
                return FormValidation.error("Choose repository in Space");

            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckRevision(@QueryParameter String revision, @QueryParameter String branch) {
            if (revision.isBlank() && !branch.isBlank())
                return FormValidation.error("Specify git commit");

            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckBranch(@QueryParameter String revision, @QueryParameter String branch) {
            if (branch.isBlank() && !revision.isBlank())
                return FormValidation.error("Specify git branch");

            return FormValidation.ok();
        }

        @Override
        public ReportBuildStatusStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            BuildStepUtilsKt.flattenNestedObject(formData, "customSpaceConnection");
            BuildStepUtilsKt.flattenNestedObject(formData, "customRevision");
            return (ReportBuildStatusStep)super.newInstance(req, formData);
        }
    }
}
