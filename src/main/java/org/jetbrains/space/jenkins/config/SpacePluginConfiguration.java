package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Piece of Jenkins configuration containing the list of org-level SpaceCode connections.
 */
@Extension
public class SpacePluginConfiguration extends Descriptor<SpacePluginConfiguration> implements Describable<SpacePluginConfiguration> {

    private List<SpaceConnection> connections;

    public SpacePluginConfiguration() {
        super(SpacePluginConfiguration.class);
        load();
        if (connections == null)
            connections = new ArrayList<>();
    }

    public List<SpaceConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    public void addConnection(SpaceConnection connection) {
        connections = new ArrayList<>(
                connections.stream()
                        .filter(c ->
                                !Objects.equals(c.getClientId(), connection.getClientId()) &&
                                        !c.getId().equals(connection.getId()))
                        .collect(Collectors.toList())
        );
        connections.add(connection);
        save();
    }

    /**
     * Called when a job, workflow or multibranch project is renamed in Jenkins.
     * Reflects the name change in the nested collections of child connections.
     */
    public void onItemRenamed(String oldFullName, String newFullName) {
        connections.forEach(conn -> conn.onItemRenamed(oldFullName, newFullName));
        save();
    }

    /**
     * Called when a job or workflow is updated in Jenkins.
     * Cleans up unused project-level connections, SSH credentials and SpaceCode applications that the job doesn't use anymore.
     */
    public void onJobUpdated(String jobFullName, SpaceProjectConnectionJobProperty jobProperty) {
        connections.forEach(parentConnection -> {
            SpaceProjectConnection projectConnection = parentConnection.getProjectConnectionsByJob().get(jobFullName);
            if (projectConnection != null && !isTheSameProjectConnection(jobProperty, parentConnection, projectConnection)) {
                parentConnection.getProjectConnectionsByJob().remove(jobFullName);
                SpaceProjectConnectionKt.deleteProjectApplication(projectConnection, parentConnection);
                SpaceProjectConnectionKt.deleteSshKeyCredentials(projectConnection);
            }
        });
    }

    private static boolean isTheSameProjectConnection(
            SpaceProjectConnectionJobProperty jobProperty,
            SpaceConnection rootConnection,
            SpaceProjectConnection projectConnection
    ) {
        return jobProperty != null
                && rootConnection.getId().equals(jobProperty.getSpaceConnectionId())
                && projectConnection.getProjectKey().equals(jobProperty.getProjectKey());
    }

    /**
     * Called when a job, workflow or multibranch project is removed.
     * Cleans up the child project-level connections, ssh credentials for this job/project
     * and requests SpaceCode to uninstall the corresponding applications.
     */
    public void onItemDeleted(String fullName) {
        connections.forEach(conn -> conn.onItemDeleted(fullName));
        save();
    }

    /**
     * Removes the org-level SpaceCode connection by its identifier.
     * Cleans up all child connections, related SpaceCode applications and SSH keys
     *
     * @return true if the connection was successfully removed, false if it hasn't been found by id.
     */
    public boolean removeConnectionById(String id) {
        SpaceConnection connection = connections.stream().filter((conn) -> conn.getId().equals(id)).findFirst().orElse(null);
        if (connection == null)
            return false;

        SpaceConnectionKt.deleteApplication(connection);
        this.connections = connections.stream().filter((conn) -> conn != connection).collect(Collectors.toList());
        save();
        return true;
    }

    /**
     * Called when SpaceCode notifies Jenkins about the uninstall of an application that supports the integration.
     * Cleans up the corresponding connection entries on Jenkins side and removes unused SSH credentials
     */
    public void onSpaceAppUninstalled(String clientId) {
        connections = connections.stream()
                .filter((conn) -> !conn.getClientId().equals(clientId))
                .collect(Collectors.toList());
        connections.forEach(conn -> conn.onSpaceAppUninstalled(clientId));
        save();
    }

    @Override
    public Descriptor<SpacePluginConfiguration> getDescriptor() {
        return this;
    }
}
