package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.jenkins.ui.icon.IconType;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

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

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        connections = Collections.emptyList(); // form binding might omit empty lists
        req.bindJSON(this, json);
        save();
        return true;
    }

    static {
        IconSet.icons.addIcon(
                new Icon(
                        UtilsKt.SPACE_LOGO_ICON + " icon-md",
                        "/plugin/jetbrains-space/space-logo.png",
                        Icon.ICON_MEDIUM_STYLE,
                        IconType.PLUGIN));
    }
}
