package org.jetbrains.space.jenkins.config;

public class SpaceRepository {

    private final SpaceConnection spaceConnection;
    private final String projectKey;
    private final String repositoryName;

    public SpaceRepository(SpaceConnection spaceConnection, String projectKey, String repositoryName) {
        this.spaceConnection = spaceConnection;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
    }


    public SpaceConnection getSpaceConnection() {
        return spaceConnection;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getRepositoryName() {
        return repositoryName;
    }
}
