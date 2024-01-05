package org.jetbrains.space.jenkins.steps;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.types.CommitExecutionStatus;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

public class ReportBuildStatusStep extends Step {

    private CommitExecutionStatus buildStatus;
    private String spaceConnection;
    private String spaceConnectionId;
    private String projectKey;
    private String repositoryName;
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

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    @DataBoundSetter
    public void setSpaceConnectionId(String spaceConnectionId) {
        this.spaceConnectionId = blankToNull(spaceConnectionId);
    }

    public String getProjectKey() {
        return projectKey;
    }

    @DataBoundSetter
    public void setProjectKey(String projectKey) {
        this.projectKey = blankToNull(projectKey);
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    @DataBoundSetter
    public void setRepositoryName(String repositoryName) {
        this.repositoryName = blankToNull(repositoryName);
    }

    public String getBranch() {
        return branch;
    }

    @DataBoundSetter
    public void setBranch(String branch) {
        this.branch = blankToNull(branch);
    }

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
        Run<?, ?> run = context.get(Run.class);
        if (run == null)
            return new FailureStepExecution("StepContext does not contain the Run instance", context);

        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        List<PostBuildStatusAction> actions = new ArrayList<>();
        if (spaceConnectionId != null || spaceConnection != null) {
            if (projectKey == null || repositoryName == null || branch == null || revision == null)
                return new FailureStepExecution("Project, repositoryName, branch and revision parameters should be explicitly specified when Space connection is provided", context);

            Optional<SpaceConnection> connection = descriptor.spacePluginConfiguration.getConnectionByIdOrName(spaceConnectionId, spaceConnection);
            if (connection.isEmpty())
                return new FailureStepExecution("No Space connection found by specified id or name", context);

            actions.add(new PostBuildStatusAction(connection.get().getId(), projectKey, repositoryName, branch, revision));
        } else {
            if (projectKey != null || repositoryName != null || branch != null || revision != null)
                return new FailureStepExecution("Space connection should be also explicitly specified when project, repositoryName, branch or revision parameters are provided", context);

            for (SpaceGitScmCheckoutAction a : run.getActions(SpaceGitScmCheckoutAction.class)) {
                actions.add(new PostBuildStatusAction(a.getSpaceConnectionId(), a.getProjectKey(), a.getRepositoryName(), a.getBranch(), a.getRevision()));
            }
        }

        if (actions.isEmpty()) {
            return new FailureStepExecution("Space connection is not specified neither in the workflow step nor as the workflow source control (SCM)", context);
        }

        for (PostBuildStatusAction a : actions) {
            run.addAction(a);
        }

        return new ReportBuildStatusStepExecution(
                actions,
                buildStatus,
                run.getAbsoluteUrl(),
                run.getParent().getFullName(),
                Integer.toString(run.getNumber()),
                run.getStartTimeInMillis(),
                run.getDescription(),
                context
        );
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        @Override
        public @NotNull String getDisplayName() {
            return "Post build status to JetBrains Space";
        }

        @POST
        public ListBoxModel doFillSpaceConnectionIdItems() {
            ListBoxModel result = scmParamsProvider.doFillSpaceConnectionIdItems();
            result.add(0, new ListBoxModel.Option("(from SCM source)", ""));
            return result;
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            if (spaceConnectionId.isBlank())
                return new ListBoxModel();
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            if (spaceConnectionId.isBlank())
                return new ListBoxModel();
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey);
        }

        /**
         * Give user a hint that repository and revision should be explicitly passed into the step when Space connection is not inherited from SCM checkout.
         * Use build status field validation for this because it is always visible on screen and not hidden under the "Advanced" section.
         */
        @POST
        public FormValidation doCheckBuildStatus(
                @QueryParameter String spaceConnectionId,
                @QueryParameter String projectKey,
                @QueryParameter String repositoryName,
                @QueryParameter String revision,
                @QueryParameter String branch
        ) {
            if (spaceConnectionId != null && !spaceConnectionId.isBlank()) {
                if (projectKey == null || projectKey.isBlank())
                    return FormValidation.error("Project should be specified when custom Space connection is selected");
                if (repositoryName == null || repositoryName.isBlank())
                    return FormValidation.error("Repository should be specified when custom Space connection is selected");
                if (revision == null || revision.isBlank())
                    return FormValidation.error("Revision parameter should be specified when custom Space connection is selected");
                if (branch == null || branch.isBlank())
                    return FormValidation.error("Branch parameter should be specified when custom Space connection is selected");
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillBuildStatusItems() {
            return Arrays.stream(CommitExecutionStatus.values())
                    .map(c -> new ListBoxModel.Option(c.name()))
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, Launcher.class, TaskListener.class));
        }

        @Override
        public String getFunctionName() {
            return "postBuildStatusToSpace";
        }
    }

    public static class FailureStepExecution extends StepExecution {

        private final String message;

        public FailureStepExecution(String message, StepContext context) {
            super(context);
            this.message = message;
        }

        @Override
        public boolean start() throws Exception {
            Throwable ex = new IllegalArgumentException(message);
            ex.setStackTrace(new StackTraceElement[0]);
            getContext().onFailure(ex);
            return true;
        }
    }
}

