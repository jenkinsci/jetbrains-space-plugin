package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;

/**
 * Pipeline step for posting a message to the merge request timeline in JetBrains Space on behalf of Jenkins integration.
 *
 * <p>
 *     Merge request number, if not specified explicitly, is taken from the build trigger settings.
 *     The build must use JetBrains Space trigger configured for merge request changes in this case.
 * </p>
 *
 * <p>
 *     Space connection and project, if not specified explicitly, are taken from the JetBrains Space trigger settings
 *     or from the Space git checkout settings.
 * </p>
 *
 */
public class PostReviewTimelineMessageStep extends Step {

    private final String messageText;
    private String spaceConnection;
    private String spaceConnectionId;
    // lgtm[jenkins/plaintext-storage]
    private String projectKey;
    private Integer mergeRequestNumber;

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

    public Integer getMergeRequestNumber() {
        return mergeRequestNumber;
    }

    @DataBoundSetter
    public void setMergeRequestNumber(Integer mergeRequestNumber) {
        this.mergeRequestNumber = mergeRequestNumber;
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        @Override
        public @NotNull String getDisplayName() {
            return "Post message to the review timeline in JetBrains Space";
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
        public ListBoxModel doFillSpaceConnectionItems(@AncestorInPath Item context) {
            return scmParamsProvider.doFillSpaceConnectionNameItems(context);
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@AncestorInPath Item context, @QueryParameter String spaceConnection) {
            if (spaceConnection.isBlank())
                return new ListBoxModel();
            return scmParamsProvider.doFillProjectKeyItems(context,null, spaceConnection);
        }

        @POST
        // lgtm[jenkins/no-permission-check]
        public FormValidation doCheckProjectKey(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            if (spaceConnection != null && !spaceConnection.isBlank() && projectKey.isBlank())
                return FormValidation.error("Choose project in Space");

            return FormValidation.ok();
        }

        /**
         * Creates a new instance of {@link PostReviewTimelineMessageStep} from the POST request coming from a submitted Jelly form.
         * Flattens overriden Space connection section before passing on the form data to the default Stapler binding logic.
         * This is needed because those parameters are part of the nested optional section in the Jelly form,
         * but are listed as plain parameters in the scripted form of a pipeline step call for the ease of pipeline script authoring.
         */
        @Override
        public PostReviewTimelineMessageStep newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            BuildStepUtilsKt.flattenNestedObject(formData, "customSpaceConnection");
            return (PostReviewTimelineMessageStep)super.newInstance(req, formData);
        }
    }
}
