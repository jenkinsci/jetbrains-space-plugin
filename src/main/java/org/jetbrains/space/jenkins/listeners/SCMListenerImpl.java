package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;

import javax.inject.Inject;
import java.io.File;

@Extension
public class SCMListenerImpl extends SCMListener {

    @Inject
    private SpacePluginConfiguration spacePluginConfiguration;

    @Override
    public void onCheckout(Run<?, ?> build, SCM scm, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState pollingBaseline) throws Exception {
        SCMListenerKt.onScmCheckout(build, scm, listener, spacePluginConfiguration);
    }
}
