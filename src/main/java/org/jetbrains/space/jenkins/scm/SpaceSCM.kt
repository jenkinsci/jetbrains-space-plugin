package org.jetbrains.space.jenkins.scm

import hudson.model.*
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitException
import hudson.plugins.git.GitSCM
import hudson.plugins.git.extensions.GitSCMExtension
import hudson.util.ListBoxModel
import jenkins.triggers.TriggeredItem
import kotlinx.coroutines.runBlocking
import org.jenkinsci.plugins.structs.describable.DescribableModel
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable
import org.jetbrains.space.jenkins.*
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getUserRemoteConfig
import org.jetbrains.space.jenkins.listeners.SpaceGitCheckoutParams
import org.jetbrains.space.jenkins.listeners.getSpaceGitCheckoutParams
import org.jetbrains.space.jenkins.listeners.mergeRequestFields
import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.MergeRequestRecord
import space.jetbrains.api.runtime.types.ProjectIdentifier
import space.jetbrains.api.runtime.types.ReviewIdentifier
import java.net.HttpURLConnection
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Initializes and returns an underlying [GitSCM] object for a given [SpaceSCM] instance.
 *
 * Fetches repository clone url from SpaceCode API
 * and configures git clone parameters using the clone url and credentials specified for the selected SpaceCode connection.
 *
 * @throws RuntimeException if there is an error connecting to JetBrains SpaceCode
 */
fun initializeGitScm(scm: SpaceSCM, job: Job<*, *>, build: Run<*, *>?, spacePluginConfiguration: SpacePluginConfiguration): GitSCM {
    LOGGER.info("SpaceSCM.initializeGitScm")

    val (gitCheckoutParams, branchToBuild, _) = spacePluginConfiguration.getSpaceGitCheckoutParams(scm, job, build)
    val remoteConfig = try {
        gitCheckoutParams.getUserRemoteConfig(scm, branchToBuild)
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, "Error connecting to JetBrains SpaceCode", ex)
        throw RuntimeException("Error connecting to JetBrains SpaceCode - $ex")
    }
    val repoBrowser = SpaceRepositoryBrowser(gitCheckoutParams.baseUrl, gitCheckoutParams.connection.projectKey, gitCheckoutParams.repositoryName)
    return GitSCM(listOf(remoteConfig), listOfNotNull(branchToBuild?.let { BranchSpec(it) }), repoBrowser, scm.gitTool, scm.extensions)
}

/**
 * Retrieves the [SpaceWebhookTrigger] associated with a given [Job].
 */
fun getSpaceWebhookTrigger(job: Job<*, *>) =
    (job as? TriggeredItem)?.triggers?.values?.filterIsInstance<SpaceWebhookTrigger>()?.firstOrNull()

fun doFillRepositoryNameItems(context: Job<*,*>?): HttpResponse {
    val (spaceConnection, spaceUrl) = context?.getProjectConnection()
        ?: return HttpResponses.errorWithoutStack(
            HttpURLConnection.HTTP_BAD_REQUEST,
            "SpaceCode connection is not configured for the job"
        )

    try {
        spaceConnection.getApiClient(spaceUrl).use { spaceApiClient ->
            val repos = runBlocking {
                spaceApiClient.projects.repositories.find.findRepositories("") {
                    projectKey()
                    repository()
                }
            }
            return ListBoxModel(repos.data.filter { it.projectKey == spaceConnection.projectKey }
                .map { ListBoxModel.Option(it.repository, it.repository) })
        }
    } catch (ex: Throwable) {
        LOGGER.log(Level.WARNING, ex.message)
        return HttpResponses.errorWithoutStack(
            HttpURLConnection.HTTP_INTERNAL_ERROR,
            "Error performing request to JetBrains SpaceCode: " + ex.message
        )
    }
}

/**
 * This class serves as a bridge or convertor between two different representations of a configured [SpaceSCM] object.
 *
 * [SpaceSCM] has different representations in Jenkins UI and in the scripted form as part of a pipeline script.
 *   * Jenkins UI presents [SpaceSCM] configuration with a nested optional block for overriding SpaceCode connection parameters.
 *   * In a scripted form, all the SpaceCode connection parameters (if present) are part of the plain function call parameters list.
 *     Plain parameters list for configuring [SpaceSCM] instance in a pipeline script is much easier than passing in a nested object.
 *
 * [SpaceSCM] class itself has SpaceCode connection parameters as nested object, [PlainSpaceSCM] has all the same data but as plain properties list.
 * Static functions for wrapping and unwrapping SpaceCode connection params convert untyped arguments maps between the two representations
 * and are invoked from the [CustomDescribableModel] implementation on the [SpaceSCM.DescriptorImpl] descriptor class.
 */
@Suppress("unused")
class PlainSpaceSCM @DataBoundConstructor constructor(
    val spaceConnection: String?,
    @field:SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    val projectKey: String?,
    val repository: String?,
    val branches: List<BranchSpec>,
    val postBuildStatusToSpace: Boolean = false,
    val gitTool: String?,
    val extensions: List<GitSCMExtension>
) {
    companion object {
        fun wrapCustomSpaceRepositoryParams(args: Map<String, Any?>) = HashMap(args).apply {
            val repository = args[REPOSITORY]
            if (repository != null) {
                put(
                    CUSTOM_SPACE_REPOSITORY,
                    UninstantiatedDescribable(
                        HashMap(args).apply {
                            put(REPOSITORY, repository)
                            args[BRANCHES]?.let { put(BRANCHES, it) }
                        }
                    )
                )
            } else {
                if (args.containsKey(BRANCHES))
                    throw IllegalArgumentException("Parameter '$BRANCHES' can be specified only when SpaceCode connection is also explicitly specified")
            }
            listOf(REPOSITORY, BRANCHES).forEach {
                remove(it)
            }
        }

        fun unwrapCustomSpaceRepositoryParams(ud: UninstantiatedDescribable) = HashMap(ud.arguments)
            .apply {
                (this[CUSTOM_SPACE_REPOSITORY] as? UninstantiatedDescribable)?.let {
                    putAll(it.arguments)
                    remove(CUSTOM_SPACE_REPOSITORY)
                }
            }
            .let { ud.withArguments(it) }
            .also {
                val model = DescribableModel.of(PlainSpaceSCM::class.java)
                it.model = model
            }
    }
}

fun Run<*, *>.getForcedBranchName() =
    (getAction(ParametersAction::class.java)?.getParameter("GIT_BRANCH") as? StringParameterValue)?.getValue()?.let {
        if (it.startsWith("refs/")) it else "refs/heads/$it"
    }

fun Run<*, *>.getForcedMergeRequestNumber() =
    (getAction(ParametersAction::class.java)?.getParameter("SPACE_MERGE_REQUEST_NUMBER") as? StringParameterValue)?.getValue()?.toIntOrNull()

fun Run<*, *>.getForcedMergeRequest(space: SpaceGitCheckoutParams) =
    getForcedMergeRequestNumber()?.let { mergeRequestNumber ->
        runBlocking {
            space.connection.getApiClient(space.baseUrl).use {
                it.projects.codeReviews.getCodeReview(
                    ProjectIdentifier.Key(space.connection.projectKey),
                    ReviewIdentifier.Number(mergeRequestNumber),
                    mergeRequestFields
                )
            } as? MergeRequestRecord
        }
    }

private const val CUSTOM_SPACE_REPOSITORY = "customSpaceRepository"
private const val REPOSITORY = "repository"
private const val BRANCHES = "branches"

private val LOGGER = Logger.getLogger(SpaceSCM::class.java.name)
