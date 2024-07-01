package org.jetbrains.space.jenkins.trigger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.Env;

import java.util.Objects;

import static org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutActionKt.putMergeRequestProperties;

/**
 * Contributes environment variables to a Jenkins build triggered by a SpaceCode webhook.
 * It retrieves information from the webhook cause and sets the corresponding environment variables
 * providing information about the SpaceCode instance, project, repository and merge request (if build was triggered by the changes to a merge request).
 */
@Extension
public class SpaceTriggerEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NotNull Run build, @NotNull EnvVars envs, @NotNull TaskListener listener) {
        build.getAllActions().stream()
                .filter(CauseAction.class::isInstance)
                .map(a -> ((CauseAction) a).findCause(SpaceWebhookTriggerCause.class))
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(cause -> {
                    envs.put(Env.SPACE_URL, cause.getSpaceUrl());
                    envs.put(Env.PROJECT_KEY, cause.getProjectKey());
                    envs.put(Env.REPOSITORY_NAME, cause.getRepositoryName());

                    TriggerCause.MergeRequest mergeRequest = cause.getMergeRequest();
                    if (mergeRequest != null) {
                        envs.put(Env.PROJECT_KEY, mergeRequest.getProjectKey());
                        putMergeRequestProperties(envs, mergeRequest);
                    }
                });
    }
}
