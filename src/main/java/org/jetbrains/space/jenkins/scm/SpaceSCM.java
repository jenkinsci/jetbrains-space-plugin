package org.jetbrains.space.jenkins.scm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.*;
import hudson.util.ListBoxModel;
import kotlin.Pair;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.JobExtensionsKt;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.SpaceProjectConnection;
import org.jetbrains.space.jenkins.Env;
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * <p>{@link SCM} implementation for JetBrains SpaceCode. Wraps the standard Git SCM implementation, adding some features on top of it.</p>
 * <p>Allows checking out source code by selecting a configured SpaceCode connection and then choosing a project and repository,
 * provides SpaceCode links for commits, files and file diffs on the build changes page
 * and enables automatic build status posting to SpaceCode.</p>
 * <p>Specifying SpaceCode connection parameters is not required, it is taken from the build trigger settings if omitted</p>
 *
 * @see <a href="https://github.com/jenkinsci/workflow-scm-step-plugin">Workflow SCM step plugin</a>
 */
public class SpaceSCM extends SCM {

    private GitSCM gitSCM;
    private boolean postBuildStatusToSpace;
    private final CustomSpaceRepository customSpaceRepository;
    private final String gitTool;
    private final List<GitSCMExtension> extensions;

    @DataBoundConstructor
    public SpaceSCM(CustomSpaceRepository customSpaceRepository, String gitTool, List<GitSCMExtension> extensions) {
        this.customSpaceRepository = customSpaceRepository;
        this.gitTool = gitTool;
        this.extensions = extensions;
        this.postBuildStatusToSpace = true;
    }

    public GitSCM getGitSCM() {
        return gitSCM;
    }

    @CheckForNull
    public CustomSpaceRepository getCustomSpaceRepository() {
        return customSpaceRepository;
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

        return null;
    }

    @CheckForNull
    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NotNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NotNull TaskListener listener
    ) throws IOException, InterruptedException {
        return getAndInitializeGitScmIfNull(build.getParent(), build)
                .calcRevisionsFromBuild(build, workspace, launcher, listener);
    }

    @Override
    public void checkout(
            @NotNull Run<?, ?> build,
            @NotNull Launcher launcher,
            @NotNull FilePath workspace,
            @NotNull TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState baseline
    ) throws IOException, InterruptedException {
        initializeGitScm(build.getParent(), build);
        gitSCM.checkout(build, launcher, workspace, listener, changelogFile, baseline);
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            @NotNull Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            @NotNull TaskListener listener,
            @NotNull SCMRevisionState baseline
    ) throws IOException, InterruptedException {
        return getAndInitializeGitScmIfNull(project, null)
                .compareRemoteRevisionWith(project, launcher, workspace, listener, baseline);
    }

    /**
     * Builds the environment for a Jenkins build, populating the provided map with relevant environment variables.
     */
    @Override
    public void buildEnvironment(@NotNull Run<?, ?> build, java.util.@NotNull Map<String, String> env) {
        Pair<SpaceProjectConnection, String> projectConnection = JobExtensionsKt.getProjectConnection(build.getParent());
        if (projectConnection != null) {
            env.put(Env.PROJECT_KEY, projectConnection.component1().getProjectKey());

            if (customSpaceRepository != null && customSpaceRepository.repository != null) {
                env.put(Env.REPOSITORY_NAME, customSpaceRepository.repository);
            } else {
                SpaceWebhookTrigger trigger = SpaceSCMKt.getSpaceWebhookTrigger(build.getParent());
                if (trigger != null) {
                    env.put(Env.REPOSITORY_NAME, trigger.getRepositoryName());
                }
            }
        }

        getAndInitializeGitScmIfNull(build.getParent(), build).buildEnvironment(build, env);
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    private GitSCM getAndInitializeGitScmIfNull(Job<?, ?> job, @Nullable Run<?, ?> build) {
        if (gitSCM == null) {
            initializeGitScm(job, build);
        }
        return gitSCM;
    }

    private void initializeGitScm(Job<?, ?> job, @Nullable Run<?, ?> build) {
        gitSCM = SpaceSCMKt.initializeGitScm(this, job, build, ExtensionList.lookupSingleton(SpacePluginConfiguration.class));
    }

    @SuppressWarnings("unused")
    @Symbol("SpaceGit")
    @Extension
    public static class DescriptorImpl extends SCMDescriptor<SpaceSCM> implements CustomDescribableModel {

        private final GitSCM.DescriptorImpl gitScmDescriptor;

        public DescriptorImpl() {
            super(SpaceRepositoryBrowser.class);
            gitScmDescriptor = new GitSCM.DescriptorImpl();
            load();
        }

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains SpaceCode";
        }

        @Override
        public @NonNull Map<String, Object> customInstantiate(@NonNull Map<String, Object> arguments) {
            return PlainSpaceSCM.Companion.wrapCustomSpaceRepositoryParams(arguments);
        }

        @Override
        public @NonNull UninstantiatedDescribable customUninstantiate(@NonNull UninstantiatedDescribable ud) {
            return PlainSpaceSCM.Companion.unwrapCustomSpaceRepositoryParams(ud);
        }

        @POST
        public HttpResponse doFillRepositoryItems(@AncestorInPath Job<?,?> context) {
            return SpaceSCMKt.doFillRepositoryNameItems(context);
        }

        @POST
        // lgtm[jenkins/no-permission-check]
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

    /**
     * Contains explicitly specified SpaceCode repository and refspec for checking out source code.
     * Optional for {@link SpaceSCM}, when missing those parameters will be taken from the build trigger configuration.
     */
    public static class CustomSpaceRepository {
        private final String repository;
        private final String refspec;

        @DataBoundConstructor
        public CustomSpaceRepository(String repository, String refspec) {
            this.repository = repository;
            this.refspec = refspec;
        }

        public String getRepository() {
            return repository;
        }

        public String getRefspec() {
            return refspec;
        }
    }
}
