package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * List of Space instance connections configured at the global Jenkins level.
 */
@Extension
public class SpacePluginConfiguration extends GlobalConfiguration {

    private List<SpaceConnection> connections = new ArrayList<>();

    @DataBoundConstructor
    public SpacePluginConfiguration() {
        load();
    }

    public List<SpaceConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    @DataBoundSetter
    public void setConnections(List<SpaceConnection> connections) {
        this.connections = connections;
        save();
    }
}
