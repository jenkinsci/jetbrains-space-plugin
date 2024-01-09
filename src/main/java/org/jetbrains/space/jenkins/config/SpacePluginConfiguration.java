package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.List;

@Extension
public class SpacePluginConfiguration extends GlobalConfiguration {

    private List<SpaceConnection> connections = new ArrayList<>();

    @DataBoundConstructor
    public SpacePluginConfiguration() {
        load();
    }

    public List<SpaceConnection> getConnections() {
        return connections;
    }

    @DataBoundSetter
    public void setConnections(List<SpaceConnection> connections) {
        this.connections = connections;
        save();
    }
}
