package org.jetbrains.space.jenkins.scm

import hudson.model.Job
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.GitTool
import hudson.plugins.git.extensions.GitSCMExtension
import jenkins.triggers.TriggeredItem
import org.jenkinsci.plugins.structs.describable.DescribableModel
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getUserRemoteConfig
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import org.kohsuke.stapler.DataBoundConstructor
import java.util.logging.Level
import java.util.logging.Logger

fun initializeGitScm(scm: SpaceSCM, job: Job<*, *>, spacePluginConfiguration: SpacePluginConfiguration): GitSCM {
    LOGGER.info("SpaceSCM.initializeGitScm")

    val (space, branches) = getSpaceGitCheckoutParams(scm, job, spacePluginConfiguration)
    try {
        val remoteConfig = space.connection.getUserRemoteConfig(space.projectKey, space.repositoryName)
        val repoBrowser = SpaceRepositoryBrowser(space.connection, space.projectKey, space.repositoryName)
        return GitSCM(listOf(remoteConfig), branches, repoBrowser, scm.gitTool, scm.extensions)
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, "Error connecting to JetBrains Space", ex)
        throw RuntimeException("Error connecting to JetBrains Space - $ex")
    }
}

fun getSpaceWebhookTrigger(job: Job<*, *>) =
    (job as? TriggeredItem)?.triggers?.values?.filterIsInstance<SpaceWebhookTrigger>()?.firstOrNull()

fun wrapCustomSpaceConnectionParams(args: Map<String, Any?>) = HashMap(args).apply {
    val connectionId = args[CONNECTION_ID]
    val connectionName = args[CONNECTION]
    if (connectionId != null || connectionName != null) {
        if (connectionId != null && connectionName != null)
            throw IllegalArgumentException("$CONNECTION_ID and $CONNECTION cannot be specified at the same time")

        connectionId?.let { put(CONNECTION_ID, it) }
        connectionName?.let { put(CONNECTION, it) }
        copyFrom(args, PROJECT_KEY)
        copyFrom(args, REPOSITORY)
        copyFrom(args, BRANCHES, required = false)
    } else {
        checkParameterAbsence(args, PROJECT_KEY)
        checkParameterAbsence(args, REPOSITORY)
        checkParameterAbsence(args, BRANCHES)
    }
    listOf(CONNECTION_ID, CONNECTION, PROJECT_KEY, REPOSITORY, BRANCHES).forEach {
        remove(it)
    }
}

fun unwrapCustomSpaceConnectionParams(ud: UninstantiatedDescribable) = HashMap(ud.arguments)
    .apply {
        (this[CUSTOM_SPACE_CONNECTION] as? UninstantiatedDescribable)?.let {
            putAll(it.arguments)
            remove(CUSTOM_SPACE_CONNECTION)
        }
    }
    .let { ud.withArguments(it) }
    .also {
        val model = DescribableModel.of(PlainSpaceSCM::class.java)
        it.model = model
    }

// used to create DescribableModel for the SpaceSCM representation in a pipeline script
@Suppress("unused")
private class PlainSpaceSCM @DataBoundConstructor constructor(
    val spaceConnectionId: String?,
    val spaceConnection: String?,
    val projectKey: String?,
    val repository: String?,
    val branches: List<BranchSpec>,
    val postBuildStatusToSpace: Boolean = false,
    val gitTool: String?,
    val extensions: List<GitSCMExtension>
)

private fun MutableMap<String, Any?>.copyFrom(args: Map<String, Any?>, key: String, required: Boolean = true) {
    args[key]?.let { put(key, it) }
        ?: run {
            if (required)
                throw IllegalArgumentException("Parameter '$key' is required when Space connection specified explicitly")
        }
}

private fun checkParameterAbsence(args: Map<String, Any?>, key: String) {
    if (args.containsKey(key))
        throw IllegalArgumentException("Parameter '$key' can be specified only when Space connection is also explicitly specified")
}

private const val CUSTOM_SPACE_CONNECTION = "customSpaceConnection"
private const val CONNECTION_ID = "spaceConnectionId"
private const val CONNECTION = "spaceConnection"
private const val PROJECT_KEY = "projectKey"
private const val REPOSITORY = "repository"
private const val BRANCHES = "branches"

private val LOGGER = Logger.getLogger(SpaceSCM::class.java.name)
