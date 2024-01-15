package org.jetbrains.space.jenkins.scm;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.*;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.UtilsKt;
import org.jetbrains.space.jenkins.trigger.Env;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Collections.singletonList;

public class SpaceSCM extends SCM {

    private GitSCM gitSCM;
    private final String spaceConnectionId;
    private final String spaceConnection;
    private final String projectKey;
    private final String repositoryName;
    private boolean postBuildStatusToSpace;
    private final List<BranchSpec> branches;
    private final String gitTool;
    private final List<GitSCMExtension> extensions;

    @DataBoundConstructor
    public SpaceSCM(
            String spaceConnectionId,
            String spaceConnection,
            String projectKey,
            String repositoryName,
            List<BranchSpec> branches,
            String gitTool,
            List<GitSCMExtension> extensions
    ) {
        this.spaceConnectionId = spaceConnectionId;
        this.spaceConnection = spaceConnection;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.branches = branches;
        this.gitTool = gitTool;
        this.extensions = extensions;
        this.postBuildStatusToSpace = true;
    }

    public GitSCM getGitSCM() {
        return gitSCM;
    }

    public String getSpaceConnectionId() {
        return spaceConnectionId;
    }

    public String getSpaceConnection() { return spaceConnection; }

    public String getProjectKey() { return projectKey; }

    public String getRepositoryName() { return repositoryName; }

    public List<BranchSpec> getBranches() {
        if (gitSCM == null) {
            return branches;
        }
        return gitSCM.getBranches();
    }

    public boolean getPostBuildStatusToSpace() {
        return postBuildStatusToSpace;
    }

    @DataBoundSetter
    public void setPostBuildStatusToSpace(boolean value) {
        this.postBuildStatusToSpace = value;
    }

    public String getGitTool() {
        return gitTool;
    }

    public List<GitSCMExtension> getExtensions() {
        return extensions;
    }

    @Override
    public RepositoryBrowser<?> getBrowser() {
        if (projectKey.isBlank() || repositoryName.isBlank())
            return null;

        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        SpaceConnection spaceConnection = UtilsKt.getConnectionByIdOrName(
                descriptor.spacePluginConfiguration, this.spaceConnectionId, this.spaceConnection);
        return (spaceConnection != null)
                ? new SpaceRepositoryBrowser(spaceConnection, projectKey, repositoryName)
                : null;
    }

    @CheckForNull
    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NotNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NotNull TaskListener listener)
            throws IOException, InterruptedException {
        LOGGER.info("SpaceSCM.calcRevisionsFromBuild");
        return getAndInitializeGitScmIfNull()
                .calcRevisionsFromBuild(build, workspace, launcher, listener);
    }

    @Override
    public void checkout(
            @NotNull Run<?, ?> build,
            @NotNull Launcher launcher,
            @NotNull FilePath workspace,
            @NotNull TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        getAndInitializeGitScmIfNull()
                .checkout(build, launcher, workspace, listener, changelogFile, baseline);
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            @NotNull Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            @NotNull TaskListener listener,
            @NotNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        LOGGER.info("SpaceSCM.compareRemoteRevisionWith");
        return getAndInitializeGitScmIfNull()
                .compareRemoteRevisionWith(project, launcher, workspace, listener, baseline);
    }

    @Override
    public void buildEnvironment(@NotNull Run<?, ?> build, java.util.@NotNull Map<String, String> env) {
        env.put(Env.PROJECT_KEY, projectKey);
        env.put(Env.REPOSITORY_NAME, repositoryName);
        getAndInitializeGitScmIfNull().buildEnvironment(build, env);
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    private GitSCM getAndInitializeGitScmIfNull() {
        if (gitSCM == null) {
            initializeGitScm();
        }
        return gitSCM;
    }

    private void initializeGitScm() {
        LOGGER.info("SpaceSCM.initializeGitScm");
        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        SpaceConnection spaceConnection = UtilsKt.getConnectionByIdOrName(
                descriptor.spacePluginConfiguration, this.spaceConnectionId, this.spaceConnection);
        if (spaceConnection == null) {
            throw new IllegalArgumentException("Specified JetBrains Space connection does not exist");
        }

        try {
            UserRemoteConfig remoteConfig = UtilsKt.getUserRemoteConfig(spaceConnection, projectKey, repositoryName);
            SpaceRepositoryBrowser repoBrowser = new SpaceRepositoryBrowser(spaceConnection, projectKey, repositoryName);
            gitSCM = new GitSCM(singletonList(remoteConfig), branches, repoBrowser, gitTool, extensions);
        } catch (Throwable ex) {
            LOGGER.log(Level.WARNING, "Error connecting to JetBrains Space", ex);
            throw new RuntimeException("Error connecting to JetBrains Space - " + ex);
        }
    }

    @Symbol("SpaceGit")
    @Extension
    public static class DescriptorImpl extends SCMDescriptor<SpaceSCM> {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsProvider;

        private final GitSCM.DescriptorImpl gitScmDescriptor;

        public DescriptorImpl() {
            super(SpaceRepositoryBrowser.class);
            gitScmDescriptor = new GitSCM.DescriptorImpl();
            load();
        }

        @Override
        public boolean isApplicable(Job project) { return true; }

        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains Space";
        }

        @POST
        public ListBoxModel doFillSpaceConnectionIdItems() {
            return scmParamsProvider.doFillSpaceConnectionIdItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey);
        }

        @POST
        public ListBoxModel doFillGitToolItems() {
            return gitScmDescriptor.doFillGitToolItems();
        }

        public List<GitSCMExtensionDescriptor> getExtensionDescriptors() {
            return gitScmDescriptor.getExtensionDescriptors();
        }

        public List<GitTool> getGitTools() {
            return gitScmDescriptor.getGitTools();
        }

        public boolean getShowGitToolOptions() {
            return gitScmDescriptor.showGitToolOptions();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SpaceSCM.class.getName());
}
