package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;

import javax.inject.Inject;

@Extension
public class RunListenerImpl extends RunListener<Run<?, ?>> {

    @Inject
    private SpacePluginConfiguration spacePluginConfiguration;

    @Override
    public void onCompleted(Run<?, ?> run, @NotNull TaskListener listener) {
        SCMListenerKt.onBuildCompleted(run, listener, spacePluginConfiguration);
    }
}
