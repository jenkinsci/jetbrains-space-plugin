package org.jetbrains.space.jenkins.trigger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jetbrains.annotations.NotNull;

@Extension
public class SpaceTriggerEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NotNull Run build, @NotNull EnvVars envs, @NotNull TaskListener listener) {
        build.getAllActions().stream()
                .filter((a) -> a instanceof CauseAction)
                .map((a) -> (CauseAction) a)
                .map((a) -> a.findCause(SpaceWebhookTriggerCause.class))
                .findFirst()
                .map(SpaceWebhookTriggerCause::getMergeRequest)
                .ifPresent(mergeRequest -> {
                    envs.put(Env.MERGE_REQUEST_ID, mergeRequest.getId());
                    envs.put(Env.MERGE_REQUEST_NUMBER, Integer.toString(mergeRequest.getNumber()));
                    envs.put(Env.MERGE_REQUEST_URL, mergeRequest.getUrl());
                    envs.put(Env.MERGE_REQUEST_TITLE, mergeRequest.getTitle());
                    if (mergeRequest.getSourceBranch() != null) {
                        envs.put(Env.MERGE_REQUEST_SOURCE_BRANCH, mergeRequest.getSourceBranch());
                    }
                });
    }
}
