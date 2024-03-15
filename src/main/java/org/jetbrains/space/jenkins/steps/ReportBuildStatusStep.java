package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Pipeline step for reporting build status for a git commit to Space.</p>
 *
 * <p>
 *     Space connection, project key and repository, if not specified explicitly, are taken from the build trigger settings
 *     or from the git checkout settings.
 * </p>
 *
 * <p>
 *     Git revision and branch, if not specified explicitly,
 *     are taken from the build trigger parameters (if Space webhook trigger is enabled for the build)
 *     or from the git checkout settings (if source code is checked out from a Space repository).
 * </p>
 */
public class ReportBuildStatusStep extends Step {

    private CommitExecutionStatus buildStatus;
    private String spaceConnection;
    // lgtm[jenkins/plaintext-storage]
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

    @CheckForNull
    public String getSpaceConnection() {
        return spaceConnection;
    }

    @DataBoundSetter
    public void setSpaceConnection(String spaceConnection) {
        this.spaceConnection = blankToNull(spaceConnection);
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
    public StepExecution start(StepContext context) {
        return ReportBuildStatusStepExecution.Companion.start(
                this,
                context,
                ExtensionList.lookupSingleton(SpacePluginConfiguration.class)
        );
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

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
        public ListBoxModel doFillSpaceConnectionItems(@AncestorInPath Item context) {
            return SpaceSCMParamsProvider.INSTANCE.doFillSpaceConnectionItems(context);
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@AncestorInPath Item context, @QueryParameter String spaceConnection) {
            if (spaceConnection.isBlank())
                return new ListBoxModel();
            return SpaceSCMParamsProvider.INSTANCE.doFillProjectKeyItems(context, spaceConnection);
        }

        @POST
        public HttpResponse doFillRepositoryItems(@AncestorInPath Item context, @QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (spaceConnection.isBlank())
                return new ListBoxModel();
            return SpaceSCMParamsProvider.INSTANCE.doFillRepositoryNameItems(context, spaceConnection, projectKey);
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public ListBoxModel doFillBuildStatusItems() {
            return CommitExecutionStatus.getEntries().stream()
                    .map(c -> new ListBoxModel.Option(c.name()))
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckProjectKey(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (!spaceConnection.isBlank() && projectKey.isBlank())
                return FormValidation.error("Choose project in Space");

            return FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckRepositoryName(@QueryParameter String spaceConnection, @QueryParameter String projectKey, @QueryParameter String repositoryName) {
            if (!spaceConnection.isBlank() && !projectKey.isBlank() && repositoryName.isBlank())
                return FormValidation.error("Choose repository in Space");

            return FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckRevision(@QueryParameter String revision, @QueryParameter String branch) {
            if (revision.isBlank() && !branch.isBlank())
                return FormValidation.error("Specify git commit");

            return FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckBranch(@QueryParameter String revision, @QueryParameter String branch) {
            if (branch.isBlank() && !revision.isBlank())
                return FormValidation.error("Specify git branch");

            return FormValidation.ok();
        }

        /**
         * Creates a new instance of {@link ReportBuildStatusStep} from the POST request coming from a submitted Jelly form.
         * Flattens overriden Space connection and revision sections before passing on the form data to the default Stapler binding logic.
         * This is needed because those parameters are part of nested optional sections in the Jelly form,
         * but are listed as plain parameters in the scripted form of a pipeline step call for the ease of pipeline script authoring.
         */
        @Override
        public ReportBuildStatusStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            BuildStepUtilsKt.flattenNestedObject(formData, "customSpaceConnection");
            BuildStepUtilsKt.flattenNestedObject(formData, "customRevision");
            return (ReportBuildStatusStep)super.newInstance(req, formData);
        }
    }
}
