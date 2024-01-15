package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.UtilsKt;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause;
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
        Run<?, ?> run = context.get(Run.class);
        if (run == null)
            return new FailureStepExecution("StepContext does not contain the Run instance", context);

        SpaceConnection connection = null;
        String projectKey = null;
        Integer reviewNumber = null;
        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        if (spaceConnectionId != null || spaceConnection != null) {
            if (this.projectKey == null)
                return new FailureStepExecution("Project key must be specified together with the Space connection", context);

            projectKey = this.projectKey;
            reviewNumber = this.reviewNumber;
            connection = UtilsKt.getConnectionByIdOrName(descriptor.spacePluginConfiguration, spaceConnectionId, spaceConnection);
            if (connection == null)
                return new FailureStepExecution("No Space connection found by specified id or name", context);
        } else {
            CauseAction causeAction = run.getAction(CauseAction.class);
            if (causeAction != null) {
                SpaceWebhookTriggerCause cause = causeAction.findCause(SpaceWebhookTriggerCause.class);
                if (cause != null && cause.getMergeRequest() != null) {
                    connection = UtilsKt.getConnectionById(descriptor.spacePluginConfiguration, cause.getSpaceConnectionId());
                    projectKey = cause.getProjectKey();
                    if (connection == null)
                        return new FailureStepExecution("No Space connection found for the Space webhook call that triggered the build", context);
                    if (cause.getMergeRequest() != null) {
                        reviewNumber = cause.getMergeRequest().getNumber();
                    }
                }
            }
        }

        if (connection == null) {
            return new FailureStepExecution("Space connection cannot be inferred from the build trigger or checkout setting and is not specified explicitly in the workflow step", context);
        }

        if (reviewNumber == null) {
            return new FailureStepExecution("Merge request cannot be inferred from the build trigger settings and is not specified explicitly in the workflow step", context);
        }

        return new PostReviewTimelineMessageStepExecution(connection.getId(), projectKey, reviewNumber, messageText, context);
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
    public static class DescriptorImpl extends StepDescriptor implements CustomDescribableModel {

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

        @POST
        public FormValidation doCheckReviewNumber(@QueryParameter String spaceConnection, @QueryParameter Integer reviewNumber) {
            if (spaceConnection != null && !spaceConnection.isBlank() && reviewNumber == null)
                return FormValidation.error("Specify merge request number");

            return FormValidation.ok();
        }

        @Override
        public @NotNull Map<String, Object> customInstantiate(@NonNull Map<String, Object> arguments) {
            HashMap<String, Object> args = new HashMap<>(arguments);
            Object customSpaceConnection = args.get(customSpaceConnectionJsonKey);
            if (customSpaceConnection instanceof UninstantiatedDescribable) {
                args.putAll(((UninstantiatedDescribable)customSpaceConnection).getArguments());
            }
            args.remove(customSpaceConnectionJsonKey);
            return args;
        }

        @Override
        public @NotNull UninstantiatedDescribable customUninstantiate(@NonNull UninstantiatedDescribable ud) {
            Object customSpaceConnection = ud.getArguments().get(customSpaceConnectionJsonKey);
            if (customSpaceConnection instanceof UninstantiatedDescribable) {
                Map<String, Object> arguments = new TreeMap<>(ud.getArguments());
                arguments.remove(customSpaceConnectionJsonKey);
                arguments.putAll(((UninstantiatedDescribable) customSpaceConnection).getArguments());
                return ud.withArguments(arguments);
            }
            return ud;
        }

        @Override
        public PostReviewTimelineMessageStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            if (formData.has(customSpaceConnectionJsonKey)) {
                JSONObject customSpaceConnection = formData.getJSONObject(customSpaceConnectionJsonKey);
                formData.putAll(customSpaceConnection);
                formData.remove(customSpaceConnectionJsonKey);
            }
            return (PostReviewTimelineMessageStep)super.newInstance(req, formData);
        }
    }

    private static final String customSpaceConnectionJsonKey = "customSpaceConnection";
}

