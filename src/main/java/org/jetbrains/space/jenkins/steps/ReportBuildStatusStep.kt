package org.jetbrains.space.jenkins.steps

import hudson.Extension
import hudson.Launcher
import hudson.model.Run
import hudson.model.TaskListener
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionByIdOrName
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction
import org.jetbrains.space.jenkins.scm.SpaceSCMParamsProvider
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.runtime.types.CommitExecutionStatus
import java.util.*
import javax.inject.Inject

class ReportBuildStatusStep @DataBoundConstructor constructor(buildStatus: String?) : Step() {
    private var buildStatus: CommitExecutionStatus = buildStatus?.let { CommitExecutionStatus.valueOf(it) } ?: CommitExecutionStatus.SUCCEEDED
    private var spaceConnection: String? = null
    private var spaceConnectionId: String? = null
    private var projectKey: String? = null
    private var repositoryName: String? = null
    private var revision: String? = null
    private var branch: String? = null

    fun getSpaceConnection(): String? {
        return spaceConnection
    }

    @DataBoundSetter
    fun setSpaceConnection(spaceConnection: String) {
        this.spaceConnection = spaceConnection.takeUnless { it.isBlank() }
    }

    fun getSpaceConnectionId(): String? {
        return spaceConnectionId
    }

    @DataBoundSetter
    fun setSpaceConnectionId(spaceConnectionId: String) {
        this.spaceConnectionId = spaceConnectionId.takeUnless { it.isBlank() }
    }

    fun getProjectKey(): String? {
        return projectKey
    }

    @DataBoundSetter
    fun setProjectKey(projectKey: String) {
        this.projectKey = projectKey.takeUnless { it.isBlank() }
    }

    fun getRepositoryName(): String? {
        return repositoryName
    }

    @DataBoundSetter
    fun setRepositoryName(repositoryName: String) {
        this.repositoryName = repositoryName.takeUnless { it.isBlank() }
    }

    fun getBranch(): String? {
        return branch
    }

    @DataBoundSetter
    fun setBranch(branch: String) {
        this.branch = branch.takeUnless { it.isBlank() }
    }

    fun getRevision(): String? {
        return revision
    }

    @DataBoundSetter
    fun setRevision(revision: String) {
        this.revision = revision.takeUnless { it.isBlank() }
    }

    fun getBuildStatus(): String? {
        return buildStatus?.name
    }

    @DataBoundSetter
    fun setBuildStatus(buildStatus: String) {
        this.buildStatus = CommitExecutionStatus.valueOf(buildStatus)
    }

    override fun start(context: StepContext): StepExecution {
        val run = context.get(Run::class.java)
            ?: return FailureStepExecution("StepContext does not contain the Run instance", context)

        val descriptor = descriptor as DescriptorImpl
        val actions: MutableList<PostBuildStatusAction> = ArrayList()
        if (spaceConnectionId != null || spaceConnection != null) {
            if (projectKey == null || repositoryName == null || branch == null || revision == null)
                return FailureStepExecution(
                    "Project, repositoryName, branch and revision parameters should be explicitly specified when Space connection is provided",
                    context
                )

            val connection =
                descriptor.spacePluginConfiguration.getConnectionByIdOrName(spaceConnectionId, spaceConnection)
                    ?: return FailureStepExecution("No Space connection found by specified id or name", context)

            actions.add(
                PostBuildStatusAction(
                    spaceConnectionId = connection.id,
                    projectKey = projectKey!!,
                    repositoryName = repositoryName!!,
                    branch = branch!!,
                    revision = revision!!
                )
            )
        } else {
            if (projectKey != null || repositoryName != null || branch != null || revision != null)
                return FailureStepExecution(
                    "Space connection should be also explicitly specified when project, repositoryName, branch or revision parameters are provided",
                    context
                )

            for (a in run.getActions(SpaceGitScmCheckoutAction::class.java)) {
                actions.add(
                    PostBuildStatusAction(
                        a.spaceConnectionId,
                        a.projectKey,
                        a.repositoryName,
                        a.branch,
                        a.revision
                    )
                )
            }
        }

        if (actions.isEmpty()) {
            return FailureStepExecution(
                "Space connection is not specified neither in the workflow step nor as the workflow source control (SCM)",
                context
            )
        }

        for (a in actions) {
            run.addAction(a)
        }

        return ReportBuildStatusStepExecution(
            actions = actions,
            buildStatus = buildStatus,
            buildUrl = run.absoluteUrl,
            taskName = run.parent.fullName,
            taskBuildId = run.getNumber().toString(),
            taskBuildStartTimeInMillis = run.startTimeInMillis,
            taskBuildDescription = run.description,
            context = context
        )
    }

    @Extension
    class DescriptorImpl : StepDescriptor() {
        @Inject
        lateinit var spacePluginConfiguration: SpacePluginConfiguration

        @Inject
        lateinit var scmParamsProvider: SpaceSCMParamsProvider

        override fun getDisplayName(): String {
            return "Post build status to JetBrains Space"
        }

        @POST
        fun doFillSpaceConnectionIdItems(): ListBoxModel {
            val result = scmParamsProvider.doFillSpaceConnectionIdItems()
            result.add(0, ListBoxModel.Option("(from SCM source)", ""))
            return result
        }

        @POST
        fun doFillProjectKeyItems(@QueryParameter spaceConnectionId: String): HttpResponse {
            if (spaceConnectionId.isBlank()) return ListBoxModel()
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId)
        }

        @POST
        fun doFillRepositoryNameItems(
            @QueryParameter spaceConnectionId: String,
            @QueryParameter projectKey: String
        ): HttpResponse {
            if (spaceConnectionId.isBlank()) return ListBoxModel()
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey)
        }

        /**
         * Give user a hint that repository and revision should be explicitly passed into the step when Space connection is not inherited from SCM checkout.
         * Use build status field validation for this because it is always visible on screen and not hidden under the "Advanced" section.
         */
        @POST
        fun doCheckBuildStatus(
            @QueryParameter spaceConnectionId: String?,
            @QueryParameter projectKey: String?,
            @QueryParameter repositoryName: String?,
            @QueryParameter revision: String?,
            @QueryParameter branch: String?
        ): FormValidation {
            if (!spaceConnectionId.isNullOrBlank()) {
                if (projectKey.isNullOrBlank())
                    return FormValidation.error("Project should be specified when custom Space connection is selected")
                if (repositoryName.isNullOrBlank())
                    return FormValidation.error("Repository should be specified when custom Space connection is selected")
                if (revision.isNullOrBlank())
                    return FormValidation.error("Revision parameter should be specified when custom Space connection is selected")
                if (branch.isNullOrBlank())
                    return FormValidation.error("Branch parameter should be specified when custom Space connection is selected")
            }
            return FormValidation.ok()
        }

        @POST
        fun doFillBuildStatusItems(): ListBoxModel {
            return ListBoxModel(CommitExecutionStatus.entries.map { ListBoxModel.Option(it.name) })
        }

        override fun getRequiredContext(): Set<Class<*>?> {
            return hashSetOf(Run::class.java, Launcher::class.java, TaskListener::class.java)
        }

        override fun getFunctionName(): String {
            return "postBuildStatusToSpace"
        }
    }

    class FailureStepExecution(private val message: String, context: StepContext?) : StepExecution(context!!) {
        override fun start(): Boolean {
            val ex: Throwable = IllegalArgumentException(message)
            ex.stackTrace = arrayOfNulls(0)
            context.onFailure(ex)
            return true
        }
    }
}
