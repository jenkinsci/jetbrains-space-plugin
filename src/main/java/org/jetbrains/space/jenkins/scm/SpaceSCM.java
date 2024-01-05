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
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.jenkinsci.Symbol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.resources.Projects;
import space.jetbrains.api.runtime.types.ProjectIdentifier;
import space.jetbrains.api.runtime.types.RepositoryUrls;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Collections.singletonList;
import static org.apache.commons.lang.StringUtils.isBlank;

public class SpaceSCM extends SCM {
    private static final Logger LOGGER = Logger.getLogger(SpaceSCM.class.getName());

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
        LOGGER.info("SpaceSCM instantiated");

        this.spaceConnectionId = spaceConnectionId;
        this.spaceConnection = spaceConnection;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.branches = branches;
        this.gitTool = gitTool;
        this.extensions = extensions;
        this.postBuildStatusToSpace = true;
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
        Optional<SpaceConnection> spaceConnection = descriptor.spacePluginConfiguration.getConnectionByIdOrName(this.spaceConnectionId, this.spaceConnection);
        return spaceConnection.map(connection -> new SpaceRepositoryBrowser(connection, projectKey, repositoryName)).orElse(null);
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
        LOGGER.info("SpaceSCM.checkout");
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
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    public GitSCM getAndInitializeGitScmIfNull() {
        if (gitSCM == null) {
            initializeGitScm();
        }
        return gitSCM;
    }

    private void initializeGitScm() {
        LOGGER.info("SpaceSCM.initializeGitScm");
        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        Optional<SpaceConnection> spaceConnection = descriptor.spacePluginConfiguration.getConnectionByIdOrName(this.spaceConnectionId, this.spaceConnection);
        if (spaceConnection.isEmpty()) {
            throw new IllegalArgumentException("Specified JetBrains Space connection does not exist");
        }

        try {
            UserRemoteConfig remoteConfig = getRepositoryConfig(spaceConnection.get(), projectKey, repositoryName);
            SpaceRepositoryBrowser repoBrowser = new SpaceRepositoryBrowser(spaceConnection.get(), projectKey, repositoryName);
            gitSCM = new GitSCM(singletonList(remoteConfig), branches, repoBrowser, gitTool, extensions);
        } catch (Throwable ex) {
            LOGGER.log(Level.WARNING, ex.toString());
            throw new RuntimeException("Error connecting to JetBrains Space - " + ex);
        }
    }

    @CheckForNull
    public GitSCM getGitSCM() {
        return gitSCM;
    }

    public static UserRemoteConfig getRepositoryConfig(SpaceConnection spaceConnection, String projectKey, String repositoryName) throws InterruptedException {
        SpaceClient spaceApiClient = spaceConnection.getApiClient();
        Projects projectsApi = new Projects(spaceApiClient);
        RepositoryUrls repoUrls = BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> projectsApi.getRepositories().url(
                        new ProjectIdentifier.Key(projectKey),
                        repositoryName,
                        (r) -> {
                            r.httpUrl();
                            r.sshUrl();
                            return Unit.INSTANCE;
                        },
                        continuation
                )
        );
        return new UserRemoteConfig(
                isBlank(spaceConnection.getSshCredentialId()) ? repoUrls.getHttpUrl() : repoUrls.getSshUrl(),
                repositoryName,
                null,
                isBlank(spaceConnection.getSshCredentialId()) ? spaceConnection.getApiCredentialId() : spaceConnection.getSshCredentialId()
        );
    }

    @Symbol("SpaceGit")
    @Extension
    public static class DescriptorImpl extends SCMDescriptor<SpaceSCM> {

        private static final Logger LOGGER = Logger.getLogger(SpaceSCM.class.getName() + ".Descriptor");

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsValidator;

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
            return scmParamsValidator.doFillSpaceConnectionIdItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            return scmParamsValidator.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            return scmParamsValidator.doFillRepositoryNameItems(spaceConnectionId, projectKey);
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
}
