package org.jetbrains.space.jenkins.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Run;
import hudson.model.TaskListener;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.kohsuke.stapler.*;

import java.io.IOException;
import java.util.*;

/**
 * Pipeline step for posting a message to the merge request timeline in JetBrains SpaceCode on behalf of Jenkins integration.
 *
 * <p>
 *     Merge request number, if not specified explicitly, is taken from the build trigger settings.
 *     The build must use JetBrains SpaceCode trigger configured for merge request changes in this case.
 * </p>
 *
 * <p>
 *     SpaceCode connection and project are taken from the JetBrains SpaceCode trigger settings
 *     or from the SpaceCode git checkout settings.
 * </p>
 *
 */
public class PostReviewTimelineMessageStep extends Step {

    private final String messageText;
    private Integer mergeRequestNumber;

    @DataBoundConstructor
    public PostReviewTimelineMessageStep(String messageText) {
        this.messageText = messageText;
    }

    @Override
    public StepExecution start(StepContext context) throws IOException, InterruptedException {
        return PostReviewTimelineMessageStepExecution.Companion.start(
                this, context
        );
    }

    public String getMessageText() {
        return messageText;
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

        @Override
        public @NotNull String getDisplayName() {
            return "Post message to the review timeline in JetBrains SpaceCode";
        }

        @Override
        public String getFunctionName() {
            return "postReviewTimelineMessageToSpace";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, TaskListener.class));
        }
    }
}
