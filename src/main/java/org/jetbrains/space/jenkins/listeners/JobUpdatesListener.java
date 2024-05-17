package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import jenkins.branch.MultiBranchProject;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.SpacePluginConfigurationKt;
import org.jetbrains.space.jenkins.config.SpaceProjectConnectionJobProperty;

/**
 * Listens to updates in Jenkins jobs and projects and notifies SpacePluginConfiguration so that it can perform corresponding updates
 * to its data structures storing SpaceCode connections and clean up not used connections.
 */
@Extension
public class JobUpdatesListener extends ItemListener {

    /**
     * Called when a job, workflow or multibranch project is renamed in Jenkins.
     */
    @Override
    public void onRenamed(Item item, String oldName, String newName) {
        ExtensionList.lookupSingleton(SpacePluginConfiguration.class).onItemRenamed(oldName, newName);
    }

    /**
     * Called when a job, workflow or multibranch project is moved between folders in Jenkins.
     * It results in the Jenkins item's full name change, so effectively the same as renaming an item.
     */
    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        ExtensionList.lookupSingleton(SpacePluginConfiguration.class).onItemRenamed(oldFullName, newFullName);
    }

    /**
     * Called when a job, workflow or multibranch project configuration is updated in Jenkins.
     * Cleans up unused project-level connections, SSH credentials and SpaceCode applications that the job doesn't use anymore.
     */
    @Override
    public void onUpdated(Item item) {
        if (item instanceof hudson.model.Job) {
            String jobName = item.getFullName();
            SpaceProjectConnectionJobProperty connection = ((Job<?, ?>) item).getProperty(SpaceProjectConnectionJobProperty.class);
            ExtensionList.lookupSingleton(SpacePluginConfiguration.class).onJobUpdated(jobName, connection);
        }
        if (item instanceof MultiBranchProject) {
            SpacePluginConfigurationKt.onMultiBranchProjectUpdated(
                    ExtensionList.lookupSingleton(SpacePluginConfiguration.class),
                    (MultiBranchProject<?,?>)item
            );
        }
    }

    /**
     * Called when a job, workflow or multibranch project is removed.
     * Cleans up the child project-level connections, ssh credentials for this job/project
     * and requests SpaceCode to uninstall the corresponding applications.
     */
    @Override
    public void onDeleted(Item item) {
        String jobName = item.getFullName();
        ExtensionList.lookupSingleton(SpacePluginConfiguration.class).onItemDeleted(jobName);
    }
}
