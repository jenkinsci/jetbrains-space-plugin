package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;

import java.io.File;

/**
 * Listens to the source code checkout from git event and performs two actions if checkout is performed from a git repo hosted in Space.
 * <ul>
 *     <li>Reports build status as running to Space</li>
 *     <li>
 *         Appends an instance of {@link SpaceGitScmCheckoutAction} to the build containing all the checkout parameters.
 *         This action in turn also takes care of emitting Space-specific environment variables for the build.
 *     </li>
 * </ul>
 */
@Extension
public class SCMListenerImpl extends SCMListener {

    @Override
    public void onCheckout(Run<?, ?> build, SCM scm, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState pollingBaseline) throws Exception {
        SCMListenerKt.onScmCheckout(build, scm, listener, ExtensionList.lookupSingleton(SpacePluginConfiguration.class));
    }
}
