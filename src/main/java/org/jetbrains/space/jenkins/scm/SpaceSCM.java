package org.jetbrains.space.jenkins.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.*;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.UtilsKt;
import org.jetbrains.space.jenkins.trigger.Env;
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SpaceSCM extends SCM {

    private GitSCM gitSCM;
    private boolean postBuildStatusToSpace;
    private final CustomSpaceConnection customSpaceConnection;
    private final String gitTool;
    private final List<GitSCMExtension> extensions;

    @DataBoundConstructor
    public SpaceSCM(CustomSpaceConnection customSpaceConnection, String gitTool, List<GitSCMExtension> extensions) {
        this.customSpaceConnection = customSpaceConnection;
        this.gitTool = gitTool;
        this.extensions = extensions;
        this.postBuildStatusToSpace = true;
    }

    public GitSCM getGitSCM() {
        return gitSCM;
    }

    public CustomSpaceConnection getCustomSpaceConnection() {
        return customSpaceConnection;
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
        if (gitSCM != null)
            return gitSCM.getBrowser();

        if (customSpaceConnection == null)
            return null;

        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        SpaceConnection spaceConnection = UtilsKt.getConnectionByIdOrName(
                descriptor.spacePluginConfiguration, customSpaceConnection.spaceConnectionId, customSpaceConnection.spaceConnection);
        return (spaceConnection != null)
                ? new SpaceRepositoryBrowser(spaceConnection, customSpaceConnection.projectKey, customSpaceConnection.repository)
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
        return getAndInitializeGitScmIfNull(build.getParent())
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
        getAndInitializeGitScmIfNull(build.getParent())
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
        return getAndInitializeGitScmIfNull(project)
                .compareRemoteRevisionWith(project, launcher, workspace, listener, baseline);
    }

    @Override
    public void buildEnvironment(@NotNull Run<?, ?> build, java.util.@NotNull Map<String, String> env) {
        if (customSpaceConnection != null) {
            if (customSpaceConnection.projectKey != null) {
                env.put(Env.PROJECT_KEY, customSpaceConnection.projectKey);
            }
            if (customSpaceConnection.repository != null) {
                env.put(Env.REPOSITORY_NAME, customSpaceConnection.repository);
            }
        } else {
            SpaceWebhookTrigger trigger = SpaceSCMKt.getSpaceWebhookTrigger(build.getParent());
            if (trigger != null) {
                env.put(Env.PROJECT_KEY, trigger.getProjectKey());
                env.put(Env.REPOSITORY_NAME, trigger.getRepositoryName());
            }
        }
        getAndInitializeGitScmIfNull(build.getParent()).buildEnvironment(build, env);
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    private GitSCM getAndInitializeGitScmIfNull(Job<?, ?> job) {
        if (gitSCM == null) {
            initializeGitScm(job);
        }
        return gitSCM;
    }

    private void initializeGitScm(Job<?, ?> job) {
        gitSCM = SpaceSCMKt.initializeGitScm(this, job, ((DescriptorImpl)getDescriptor()).spacePluginConfiguration);
    }

    @Symbol("SpaceGit")
    @Extension
    public static class DescriptorImpl extends SCMDescriptor<SpaceSCM> implements CustomDescribableModel {

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

        @Override
        public @NonNull Map<String, Object> customInstantiate(@NonNull Map<String, Object> arguments) {
            Map<String, Object> result = SpaceSCMKt.wrapCustomSpaceConnectionParams(arguments);
            return result;
        }

        @Override
        public @NonNull UninstantiatedDescribable customUninstantiate(@NonNull UninstantiatedDescribable ud) {
            UninstantiatedDescribable result = SpaceSCMKt.unwrapCustomSpaceConnectionParams(ud);
            return result;
        }

        @POST
        public ListBoxModel doFillSpaceConnectionItems() {
            return scmParamsProvider.doFillSpaceConnectionNameItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnection) {
            return scmParamsProvider.doFillProjectKeyItems(null, spaceConnection);
        }

        @POST
        public HttpResponse doFillRepositoryItems(@QueryParameter String spaceConnection, @QueryParameter String projectKey) {
            return scmParamsProvider.doFillRepositoryNameItems(null, spaceConnection, projectKey);
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

    public static class CustomSpaceConnection {
        private final String spaceConnectionId;
        private final String spaceConnection;
        private final String projectKey;
        private final String repository;
        private final List<BranchSpec> branches;

        @DataBoundConstructor
        public CustomSpaceConnection(String spaceConnectionId, String spaceConnection, String projectKey, String repository, List<BranchSpec> branches) {
            this.spaceConnectionId = spaceConnectionId;
            this.spaceConnection = spaceConnection;
            this.projectKey = projectKey;
            this.repository = repository;
            this.branches = branches;
        }

        public String getSpaceConnectionId() {
            return spaceConnectionId;
        }

        public String getSpaceConnection() {
            return spaceConnection;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public String getRepository() {
            return repository;
        }

        public List<BranchSpec> getBranches() {
            return branches;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SpaceSCM.class.getName());
}
