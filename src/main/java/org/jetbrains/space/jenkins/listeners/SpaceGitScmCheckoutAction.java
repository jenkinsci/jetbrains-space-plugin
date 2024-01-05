package org.jetbrains.space.jenkins.listeners;

import hudson.model.Action;

public class SpaceGitScmCheckoutAction implements Action {

    private final String spaceConnectionId;
    private final String projectKey;
    private final String repositoryName;
    private final String branch;
    private final String revision;
    private final boolean postBuildStatusToSpace;

    public SpaceGitScmCheckoutAction(String spaceConnectionId, String projectKey, String repositoryName, String branch, String revision, boolean postBuildStatusToSpace) {
        this.spaceConnectionId = spaceConnectionId;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.branch = branch;
        this.revision = revision;
        this.postBuildStatusToSpace = postBuildStatusToSpace;
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

    public String getBranch() { return branch; }

    public String getRevision() {
        return revision;
    }

    public boolean getPostBuildStatusToSpace() {
        return postBuildStatusToSpace;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Checkout sources from JetBrains Space";
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
