package org.jetbrains.space.jenkins.trigger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.Env;

/**
 * Contributes environment variables to a Jenkins build triggered by a Space webhook.
 * It retrieves information from the webhook cause and sets the corresponding environment variables
 * providing information about the Space instance, project, repository and merge request (if build was triggered by the changes to a merge rquest).
 */
@Extension
public class SpaceTriggerEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NotNull Run build, @NotNull EnvVars envs, @NotNull TaskListener listener) {
        build.getAllActions().stream()
                .filter(CauseAction.class::isInstance)
                .map(a -> (CauseAction) a)
                .map(a -> a.findCause(SpaceWebhookTriggerCause.class))
                .findFirst()
                .ifPresent(cause -> {
                    envs.put(Env.SPACE_URL, cause.getSpaceUrl());
                    envs.put(Env.PROJECT_KEY, cause.getProjectKey());
                    envs.put(Env.REPOSITORY_NAME, cause.getRepositoryName());

                    MergeRequest mergeRequest = cause.getMergeRequest();
                    if (mergeRequest != null) {
                        envs.put(Env.PROJECT_KEY, mergeRequest.getProjectKey());
                        envs.put(Env.MERGE_REQUEST_ID, mergeRequest.getId());
                        envs.put(Env.MERGE_REQUEST_NUMBER, Integer.toString(mergeRequest.getNumber()));
                        envs.put(Env.MERGE_REQUEST_URL, mergeRequest.getUrl());
                        envs.put(Env.MERGE_REQUEST_TITLE, mergeRequest.getTitle());
                        if (mergeRequest.getSourceBranch() != null) {
                            envs.put(Env.MERGE_REQUEST_SOURCE_BRANCH, mergeRequest.getSourceBranch());
                        }
                        if (mergeRequest.getTargetBranch() != null) {
                            envs.put(Env.MERGE_REQUEST_TARGET_BRANCH, mergeRequest.getTargetBranch());
                        }
                    }
                });
    }
}
