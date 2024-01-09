package org.jetbrains.space.jenkins.scm

import hudson.Extension
import hudson.FilePath
import hudson.Launcher
import hudson.model.Job
import hudson.model.Run
import hudson.model.TaskListener
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.GitTool
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.extensions.GitSCMExtension
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor
import hudson.scm.*
import hudson.util.ListBoxModel
import kotlinx.coroutines.runBlocking
import org.jenkinsci.Symbol
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.ProjectIdentifier
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class SpaceSCM @DataBoundConstructor constructor(
    val spaceConnectionId: String?,
    val spaceConnection: String?,
    val projectKey: String,
    val repositoryName: String,
    val gitTool: String?,
    val extensions: List<GitSCMExtension>?
) : SCM() {
    var gitSCM: GitSCM? = null
        private set

    @set:DataBoundSetter
    var branches: List<BranchSpec>? = null
        get() = gitSCM?.branches ?: field

    @set:DataBoundSetter
    var postBuildStatusToSpace = true

    init {
        LOGGER.info("SpaceSCM instantiated for $spaceConnectionId / $spaceConnection, project $projectKey, repo $repositoryName")
    }

    override fun getBrowser(): RepositoryBrowser<*>? {
        if (projectKey.isBlank() || repositoryName.isBlank()) return null

        val descriptor = descriptor as DescriptorImpl
        return descriptor.spacePluginConfiguration
            .getConnectionByIdOrName(this.spaceConnectionId, this.spaceConnection)
            ?.let { SpaceRepositoryBrowser(it, projectKey, repositoryName) }
    }

    override fun calcRevisionsFromBuild(
        build: Run<*, *>,
        workspace: FilePath?,
        launcher: Launcher?,
        listener: TaskListener
    ): SCMRevisionState? {
        return getAndInitializeGitScmIfNull().calcRevisionsFromBuild(build, workspace, launcher, listener)
    }

    override fun checkout(
        build: Run<*, *>,
        launcher: Launcher,
        workspace: FilePath,
        listener: TaskListener,
        changelogFile: File?,
        baseline: SCMRevisionState?
    ) {
        getAndInitializeGitScmIfNull().checkout(build, launcher, workspace, listener, changelogFile, baseline)
    }

    override fun compareRemoteRevisionWith(
        project: Job<*, *>,
        launcher: Launcher?,
        workspace: FilePath?,
        listener: TaskListener,
        baseline: SCMRevisionState
    ): PollingResult {
        return getAndInitializeGitScmIfNull().compareRemoteRevisionWith(
            project,
            launcher,
            workspace,
            listener,
            baseline
        )
    }

    override fun createChangeLogParser(): ChangeLogParser {
        return gitSCM!!.createChangeLogParser()
    }

    private fun getAndInitializeGitScmIfNull(): GitSCM {
        if (gitSCM == null) {
            initializeGitScm()
        }
        return gitSCM!!
    }

    private fun initializeGitScm() {
        LOGGER.info("SpaceSCM.initializeGitScm")
        val descriptor = descriptor as DescriptorImpl
        val spaceConnection = descriptor.spacePluginConfiguration.getConnectionByIdOrName(this.spaceConnectionId, this.spaceConnection)
            ?: error("Specified JetBrains Space connection does not exist")

        try {
            val remoteConfig = spaceConnection.getUserRemoteConfig(projectKey, repositoryName)
            val repoBrowser = SpaceRepositoryBrowser(spaceConnection, projectKey, repositoryName)
            gitSCM = GitSCM(listOf(remoteConfig), branches, repoBrowser, gitTool, extensions)
        } catch (ex: Throwable) {
            LOGGER.log(Level.WARNING, "Error connecting to JetBrains Space", ex)
            throw RuntimeException("Error connecting to JetBrains Space - $ex")
        }
    }

    @Symbol("SpaceGit")
    @Extension
    class DescriptorImpl : SCMDescriptor<SpaceSCM?>(SpaceRepositoryBrowser::class.java) {
        @Inject
        lateinit var spacePluginConfiguration: SpacePluginConfiguration

        @Inject
        private lateinit var scmParamsProvider: SpaceSCMParamsProvider

        private val gitScmDescriptor = GitSCM.DescriptorImpl()

        init {
            load()
        }

        override fun isApplicable(project: Job<*, *>?) = true

        override fun getDisplayName() = "JetBrains Space"

        @POST
        fun doFillSpaceConnectionIdItems(): ListBoxModel {
            return scmParamsProvider.doFillSpaceConnectionIdItems()
        }

        @POST
        fun doFillProjectKeyItems(@QueryParameter spaceConnectionId: String): HttpResponse {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId)
        }

        @POST
        fun doFillRepositoryNameItems(
            @QueryParameter spaceConnectionId: String,
            @QueryParameter projectKey: String
        ): HttpResponse {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey)
        }

        @POST
        fun doFillGitToolItems(): ListBoxModel {
            return gitScmDescriptor.doFillGitToolItems()
        }

        val extensionDescriptors: List<GitSCMExtensionDescriptor>
            get() = gitScmDescriptor.extensionDescriptors

        val gitTools: List<GitTool>
            get() = gitScmDescriptor.gitTools

        val showGitToolOptions: Boolean
            get() = gitScmDescriptor.showGitToolOptions()
    }
}

fun SpaceConnection.getUserRemoteConfig(projectKey: String, repositoryName: String): UserRemoteConfig {
    getApiClient().use { spaceApiClient ->
        val repoUrls = runBlocking {
            spaceApiClient.projects.repositories.url(ProjectIdentifier.Key(projectKey), repositoryName) {
                httpUrl()
                sshUrl()
            }
        }
        return UserRemoteConfig(
            if (sshCredentialId.isBlank()) repoUrls.httpUrl else repoUrls.sshUrl,
            repositoryName,
            null,
            sshCredentialId.ifBlank { apiCredentialId }
        )
    }
}

private val LOGGER = Logger.getLogger(SpaceSCM::class.java.name)
