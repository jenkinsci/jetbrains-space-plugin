package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import space.jetbrains.api.runtime.SpaceClient;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

@Extension
public class SpacePluginConfiguration extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(SpacePluginConfiguration.class.getName());

    private List<SpaceConnection> connections = new ArrayList<>();

    @DataBoundConstructor
    public SpacePluginConfiguration() {
        load();
    }

    public List<SpaceConnection> getConnections() {
        return connections;
    }

    public Optional<SpaceConnection> getConnectionById(String id) {
        return (id == null || id.isBlank())
                ? Optional.empty()
                : connections.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    public Optional<SpaceConnection> getConnectionByIdOrName(String id, String name) {
        if (id != null && !id.isBlank())
            return getConnectionById(id);

        return (name != null && !name.isBlank())
                ? connections.stream().filter(c -> c.getName().equals(name)).findFirst()
                : Optional.empty();
    }

    public Optional<SpaceRepository> getSpaceRepositoryByGitCloneUrl(String gitCloneUrl) {
        URL url;
        try {
            url = new URL(gitCloneUrl);
        } catch (MalformedURLException e) {
            LOGGER.warning("Malformed git SCM url - " + gitCloneUrl);
            return Optional.empty();
        }

        if (!url.getHost().startsWith("git.")) {
            return Optional.empty();
        }

        String baseUrlHostname;
        String projectKey;
        String repositoryName;
        if (url.getHost().endsWith(".jetbrains.space")) {
            // cloud space, path contains org name, project key and repo name
            String[] pathFragments = url.getPath().split("/");
            if (pathFragments.length < 3) {
                return Optional.empty();
            }
            baseUrlHostname = pathFragments[0] + ".jetbrains.space";
            projectKey = pathFragments[1];
            repositoryName = pathFragments[2];
        } else {
            // path contains project key and repo name
            String[] pathFragments = url.getPath().split("/");
            if (pathFragments.length < 2) {
                return Optional.empty();
            }
            baseUrlHostname = url.getHost().substring("git.".length());
            projectKey = pathFragments[0];
            repositoryName = pathFragments[1];
        }

        return connections.stream()
                .filter(c -> {
                    try {
                        return Objects.equals(new URL(c.getBaseUrl()).getHost(), baseUrlHostname);
                    } catch (MalformedURLException e) {
                        LOGGER.warning("Malformed Space connection base url - " + c.getBaseUrl());
                        return false;
                    }
                })
                .findFirst()
                .map(c -> new SpaceRepository(c, projectKey, repositoryName));
    }

    public Optional<SpaceClient> getSpaceApiClient(String connectionId) {
        return getConnectionById(connectionId).map(SpaceConnection::getApiClient);
    }

    @DataBoundSetter
    public void setConnections(List<SpaceConnection> connections) {
        this.connections = connections;
        save();
    }
}
