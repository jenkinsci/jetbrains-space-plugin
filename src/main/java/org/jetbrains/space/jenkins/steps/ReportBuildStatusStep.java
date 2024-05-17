package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Job;
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
import org.jetbrains.space.jenkins.scm.SpaceSCMKt;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.types.CommitExecutionStatus;

import javax.annotation.CheckForNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Pipeline step for reporting build status for a git commit to SpaceCode.</p>
 *
 * <p>
 *     Git repository, revision and branch, if not specified explicitly, are taken from the build trigger settings
 *     or from the git checkout settings.
 * </p>
 */
public class ReportBuildStatusStep extends Step {

    private CommitExecutionStatus buildStatus;
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
                context
        );
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public @NonNull String getDisplayName() {
            return "Post build status to JetBrains SpaceCode";
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
        public HttpResponse doFillRepositoryItems(@AncestorInPath Job<?,?> context) {
            return SpaceSCMKt.doFillRepositoryNameItems(context);
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
        public FormValidation doCheckRepository(@QueryParameter String repository, @QueryParameter String revision, @QueryParameter String branch) {
            if (repository.isBlank() && (!branch.isBlank() || !revision.isBlank()))
                return FormValidation.error("Choose repository in SpaceCode");

            return FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckRevision(@QueryParameter String repository, @QueryParameter String revision, @QueryParameter String branch) {
            if (revision.isBlank() && (!branch.isBlank() || !repository.isBlank()))
                return FormValidation.error("Specify git commit");

            return FormValidation.ok();
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckBranch(@QueryParameter String repository, @QueryParameter String revision, @QueryParameter String branch) {
            if (branch.isBlank() && (!repository.isBlank() || !revision.isBlank()))
                return FormValidation.error("Specify git branch");

            return FormValidation.ok();
        }

        /**
         * Creates a new instance of {@link ReportBuildStatusStep} from the POST request coming from a submitted Jelly form.
         * Flattens overriden SpaceCode connection and revision sections before passing on the form data to the default Stapler binding logic.
         * This is needed because those parameters are part of nested optional sections in the Jelly form,
         * but are listed as plain parameters in the scripted form of a pipeline step call for the ease of pipeline script authoring.
         */
        @Override
        public ReportBuildStatusStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            BuildStepUtilsKt.flattenNestedObject(formData, "customRevision");
            return (ReportBuildStatusStep)super.newInstance(req, formData);
        }
    }
}
