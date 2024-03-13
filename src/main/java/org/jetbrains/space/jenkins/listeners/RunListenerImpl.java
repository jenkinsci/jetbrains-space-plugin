package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;

/**
 * Listens for the completion of Jenkins build and posts build status to Space if needed.
 */
@Extension
public class RunListenerImpl extends RunListener<Run<?, ?>> {

    @Override
    public void onCompleted(Run<?, ?> run, @NotNull TaskListener listener) {
        SCMListenerKt.onBuildCompleted(run, listener, ExtensionList.lookupSingleton(SpacePluginConfiguration.class));
    }
}
