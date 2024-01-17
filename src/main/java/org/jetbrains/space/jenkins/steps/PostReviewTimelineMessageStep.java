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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;

public class PostReviewTimelineMessageStep extends Step {

    private final String messageText;
    private String spaceConnection;
    private String spaceConnectionId;
    private String projectKey;
    private Integer reviewNumber;

    @DataBoundConstructor
    public PostReviewTimelineMessageStep(String messageText) {
        this.messageText = messageText;
    }

    @Override
    public StepExecution start(StepContext context) throws IOException, InterruptedException {
        return PostReviewTimelineMessageStepExecution.Companion.start(
                this, context, ((DescriptorImpl) getDescriptor()).spacePluginConfiguration
        );
    }

    public String getMessageText() {
        return messageText;
    }

    public String getSpaceConnection() {
        return spaceConnection;
    }

    @DataBoundSetter
    public void setSpaceConnection(String spaceConnection) {
        this.spaceConnection = spaceConnection;
    }

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    @DataBoundSetter
    public void setSpaceConnectionId(String spaceConnectionId) {
        this.spaceConnectionId = spaceConnectionId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    @DataBoundSetter
    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public Integer getReviewNumber() {
        return reviewNumber;
    }

    @DataBoundSetter
    public void setReviewNumber(Integer reviewNumber) {
        this.reviewNumber = reviewNumber;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        @Override
        public @NotNull String getDisplayName() {
            return "Post message to review timeline in JetBrains Space";
        }

        @Override
        public String getFunctionName() {
            return "postReviewTimelineMessageToSpace";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, TaskListener.class));
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
        public FormValidation doCheckProjectKey(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (spaceConnection != null && !spaceConnection.isBlank() && projectKey.isBlank())
                return FormValidation.error("Choose project in Space");

            return FormValidation.ok();
        }

        @Override
        public PostReviewTimelineMessageStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            BuildStepUtilsKt.flattenNestedObject(formData, "customSpaceConnection");
            return (PostReviewTimelineMessageStep)super.newInstance(req, formData);
        }
    }
}
