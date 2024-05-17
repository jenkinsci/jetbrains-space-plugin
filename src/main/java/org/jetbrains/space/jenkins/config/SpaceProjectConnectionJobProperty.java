package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Jenkins job property that ties a given Jenkins job or workflow to the SpaceCode project
 */
public class SpaceProjectConnectionJobProperty extends JobProperty<Job<?, ?>> {

    private final String spaceConnectionId;
    private final String projectKey;

    @DataBoundConstructor
    public SpaceProjectConnectionJobProperty(String spaceConnectionId, String projectKey) {
        this.spaceConnectionId = spaceConnectionId;
        this.projectKey = projectKey;
    }

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains SpaceCode connections";
        }
    }
}

