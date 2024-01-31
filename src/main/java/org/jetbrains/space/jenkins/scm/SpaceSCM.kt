package org.jetbrains.space.jenkins.scm

import hudson.model.Job
import hudson.model.Run
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.GitTool
import hudson.plugins.git.extensions.GitSCMExtension
import jenkins.triggers.TriggeredItem
import org.jenkinsci.plugins.structs.describable.DescribableModel
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getUserRemoteConfig
import org.jetbrains.space.jenkins.listeners.getSpaceGitCheckoutParams
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTriggerCause
import org.kohsuke.stapler.DataBoundConstructor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Initializes and returns an underlying [GitSCM] object for a given [SpaceSCM] instance.
 *
 * Fetches repository clone url from Space API
 * and configures git clone parameters using the clone url and credentials specified for the selected Space connection.
 *
 * @throws RuntimeException if there is an error connecting to JetBrains Space
 */
fun initializeGitScm(scm: SpaceSCM, job: Job<*, *>, build: Run<*, *>?, spacePluginConfiguration: SpacePluginConfiguration): GitSCM {
    LOGGER.info("SpaceSCM.initializeGitScm")

    val triggerCause = build?.getCause(SpaceWebhookTriggerCause::class.java)
    val (space, branches) = spacePluginConfiguration.getSpaceGitCheckoutParams(scm, job, triggerCause)
    try {
        val remoteConfig = space.connection.getUserRemoteConfig(space.projectKey, space.repositoryName, triggerCause)
        val repoBrowser = SpaceRepositoryBrowser(space.connection, space.projectKey, space.repositoryName)
        return GitSCM(listOf(remoteConfig), branches, repoBrowser, scm.gitTool, scm.extensions)
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, "Error connecting to JetBrains Space", ex)
        throw RuntimeException("Error connecting to JetBrains Space - $ex")
    }
}

/**
 * Retrieves the [SpaceWebhookTrigger] associated with a given [Job].
 */
fun getSpaceWebhookTrigger(job: Job<*, *>) =
    (job as? TriggeredItem)?.triggers?.values?.filterIsInstance<SpaceWebhookTrigger>()?.firstOrNull()

/**
 * This class serves as a bridge or convertor between two different representations of a configured [SpaceSCM] object.
 *
 * [SpaceSCM] has different representations in Jenkins UI and in the scripted form as part of a pipeline script.
 *   * Jenkins UI presents [SpaceSCM] configuration with a nested optional block for overriding Space connection parameters.
 *   * In a scripted form, all the Space connection parameters (if present) are part of the plain function call parameters list.
 *     Plain parameters list for configuring [SpaceSCM] instance in a pipeline script is much easier than passing in a nested object.
 *
 * [SpaceSCM] class itself has Space connection parameters as nested object, [PlainSpaceSCM] has all the same data but as plain properties list.
 * Static functions for wrapping and unwrapping Space connection params convert untyped arguments maps between the two representations
 * and are invoked from the [CustomDescribableModel] implementation on the [SpaceSCM.DescriptorImpl] descriptor class.
 */
@Suppress("unused")
class PlainSpaceSCM @DataBoundConstructor constructor(
    val spaceConnectionId: String?,
    val spaceConnection: String?,
    val projectKey: String?,
    val repository: String?,
    val branches: List<BranchSpec>,
    val postBuildStatusToSpace: Boolean = false,
    val gitTool: String?,
    val extensions: List<GitSCMExtension>
) {
    companion object {
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
    }
}

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
