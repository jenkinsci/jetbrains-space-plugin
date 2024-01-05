package org.jetbrains.space.jenkins.steps;

import hudson.model.Action;

import java.io.Serializable;
import java.util.Objects;

public class PostBuildStatusAction implements Action, Serializable {
    private final String spaceConnectionId;
    private final String projectKey;
    private final String repositoryName;
    private final String branch;
    private final String revision;

    public PostBuildStatusAction(String spaceConnectionId, String projectKey, String repositoryName, String branch, String revision) {
        this.spaceConnectionId = spaceConnectionId;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.branch = branch;
        this.revision = revision;
    }

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public String getBranch() {
        return branch;
    }

    public String getRevision() {
        return revision;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Post build status to JetBrains Space";
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
