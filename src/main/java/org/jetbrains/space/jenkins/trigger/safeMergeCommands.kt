package org.jetbrains.space.jenkins.trigger

import hudson.ExtensionList
import hudson.model.*
import hudson.model.Queue.LeftItem
import hudson.model.Queue.Task
import hudson.model.queue.ScheduleResult.Created
import hudson.model.queue.ScheduleResult.Existing
import hudson.model.queue.ScheduleResult.Refused
import io.ktor.http.*
import jenkins.model.Jenkins
import jenkins.triggers.SCMTriggerItem
import jenkins.triggers.TriggeredItem
import net.sf.json.JSON
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getApiClient
import org.jetbrains.space.jenkins.config.getConnectionByClientId
import org.jetbrains.space.jenkins.listeners.mergeRequestFields
import space.jetbrains.api.ExperimentalSpaceSdkApi
import space.jetbrains.api.runtime.helpers.ProcessingScope
import space.jetbrains.api.runtime.helpers.RequestAdapter
import space.jetbrains.api.runtime.helpers.SpaceHttpResponse
import space.jetbrains.api.runtime.print
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import space.jetbrains.api.runtime.types.structure.JenkinsBuildStructure
import java.util.logging.Logger

/**
 * Processes incoming start safe merge build command from Space.
 *
 * Validates the command, fills in the build cause and attaches it to queued build,
 * puts the build into the queue and returns the resulting queue item ([JenkinsBuild.QueueItem] JSON) as request body.
 *
 * @see <a href="https://www.jetbrains.com/help/space/branch-and-merge-restrictions.html#safe-merge">Safe Merge in Space</a>
 */
@OptIn(ExperimentalSpaceSdkApi::class)
suspend fun ProcessingScope.startSafeMerge(clientId: String, command: SafeMergeCommand.Start, requestAdapter: RequestAdapter): SpaceHttpResponse {
    val spaceConnection = getSpaceConnectionByClientId(clientId)
        ?: return SpaceHttpResponse.RespondWithCode(HttpStatusCode.Unauthorized)

    val job = Jenkins.get().getItemByFullName(command.project)
        ?: return SpaceHttpResponse.RespondWithCode(HttpStatusCode.NotFound)

    (job as? Queue.Task)
        ?: run {
            LOGGER.warning("Job found by name \"${command.project}\" but can't be scheduled")
            return SpaceHttpResponse.RespondWithCode(HttpStatusCode.NotFound)
        }

    // check that safe merge is enabled for this Jenkins project
    (job as? TriggeredItem)?.triggers?.values?.filterIsInstance<SpaceWebhookTrigger>()
        ?.firstOrNull {
            it.spaceConnectionId == spaceConnection.id &&
                    (it.triggerType == SpaceWebhookTriggerType.OnlySafeMerge || it.allowSafeMerge)
        }
        ?: run {
            LOGGER.warning("Safe merge trigger is not enabled for the job \"${command.project}\"")
            return SpaceHttpResponse.RespondWithCode(HttpStatusCode.Unauthorized)
        }

    // fetch additional data about the merge request from Space
    val mergeRequest = spaceConnection.getApiClient().use {
        it.projects.codeReviews.getCodeReview(
            ProjectIdentifier.Id(command.spaceProjectId),
            ReviewIdentifier.Id(command.mergeRequestId),
            mergeRequestFields
        )
    } as MergeRequestRecord

    val causeAction = CauseAction(
        SpaceWebhookTriggerCause.fromMergeRequest(
            mergeRequest,
            spaceConnection,
            TriggerCauseSafeMerge(command.branch, command.commit, command.isDryRun, command.startedByUserId)
        )
    )

    when (val scheduleResult = Jenkins.get().queue.schedule2(job, 0, causeAction)) {
        is Created, is Existing -> {
            LOGGER.info("Scheduled safe merge build for ${command.project}")
            requestAdapter.respond(HttpStatusCode.OK.value, JenkinsBuildStructure.serialize(scheduleResult.item!!.toJenkinsBuild()).print())
            return SpaceHttpResponse.AlreadyResponded
        }
        is Refused -> {
            LOGGER.info("Could not schedule build for ${command.project}")
            return SpaceHttpResponse.RespondWithCode(HttpStatusCode.Conflict)
        }
        else ->
            error("Unexpected queue schedule result - ${scheduleResult.javaClass.name}")
    }
}

/**
 * Processes incoming stop safe merge command from Space.
 *
 * The `command.buildId` might reference either a queue item or a running build (distinguished by the build id prefix).
 * If build is still in queue, it is removed from the queue; if it is already running, it is aborted.
 * The latest state of the build or queue item before the cancellation is returned as response ([JenkinsBuild] JSON) if build was found and successfully canelled.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
suspend fun ProcessingScope.stopSafeMerge(clientId: String, command: SafeMergeCommand.Stop, requestAdapter: RequestAdapter): SpaceHttpResponse {
    val result = when (val result = getQueueItemOrBuild(clientId, command.project, command.buildId)) {
        is GetQueueItemOrBuildResult.QueueItem -> {
            if (!Jenkins.get().queue.cancel(result.queueItem)) {
                LOGGER.info("Could not cancel the queue item  ${command.buildId}")
                return SpaceHttpResponse.RespondWithOk
            }
            result.queueItem.toJenkinsBuild()
        }

        is GetQueueItemOrBuildResult.Build -> {
            val executor = result.run.getExecutor()
                ?: run {
                    LOGGER.info("Build ${command.buildId} has already completed")
                    return SpaceHttpResponse.RespondWithOk
                }
            executor.interrupt()
            result.run.toJenkinsBuild()
        }

        is GetQueueItemOrBuildResult.Error -> {
            LOGGER.warning(result.message)
            return SpaceHttpResponse.RespondWithCode(result.statusCode)
        }
    }
    requestAdapter.respond(HttpStatusCode.OK.value, JenkinsBuildStructure.serialize(result).print())
    return SpaceHttpResponse.AlreadyResponded
}

/**
 * Fetches the most up-to-date state of the safe merge build from Jenkins and returns it as an instance of [JenkinsBuild].
 *
 * If the `command.buildId` references a queue item, we check whether this queue item has already started execution
 * and return the resulting running build state if it is the case.
 */
@OptIn(ExperimentalSpaceSdkApi::class)
suspend fun ProcessingScope.fetchSafeMergeStatus(clientId: String, command: SafeMergeCommand.FetchStatus, requestAdapter: RequestAdapter): SpaceHttpResponse {
    val result = when (val result = getQueueItemOrBuild(clientId, command.project, command.buildId)) {
        is GetQueueItemOrBuildResult.QueueItem ->
            result.queueItem.toJenkinsBuild()
        is GetQueueItemOrBuildResult.Build ->
            result.run.toJenkinsBuild()
        is GetQueueItemOrBuildResult.Error -> {
            LOGGER.warning(result.message)
            return SpaceHttpResponse.RespondWithCode(result.statusCode)
        }
    }
    requestAdapter.respond(HttpStatusCode.OK.value, JenkinsBuildStructure.serialize(result).print())
    return SpaceHttpResponse.AlreadyResponded
}

private fun getSpaceConnectionByClientId(clientId: String): SpaceConnection? {
    val spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration::class.java).singleOrNull()
        ?: error("Space plugin configuration cannot be resolved")

    return spacePluginConfiguration.getConnectionByClientId(clientId)
}

private fun List<Cause>.hasSafeMergeCause(spaceConnection: SpaceConnection) =
    filterIsInstance<SpaceWebhookTriggerCause>().firstOrNull()
        ?.let { it.spaceConnectionId == spaceConnection.id && it.mergeRequest?.safeMerge != null } == true

/**
 * Fetches up-to-date information about the safe merge started in Jenkins.
 * It can be either queue item if build is still pending, or a running build if build is already running or completed.
 *
 * @param clientId Client id of the Space application that handles Jenkins integration
 * @param project Full name of the Jenkins job or workflow that executes safe merge. Contains of several parts split by `/` if it is nested in a folder hierarchy.
 * @param buildId Identifier of the queue item (`queue-item-` + number) or running build (`build-` + number) in Jenkins
 */
private fun getQueueItemOrBuild(clientId: String, project: String, buildId: String): GetQueueItemOrBuildResult {
    val spaceConnection = getSpaceConnectionByClientId(clientId)
        ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.Unauthorized, "Space connection with client id $clientId not configured")

    when {
        buildId.startsWith(BuildIdPrefix.QUEUE_ITEM) -> {
            val queue = Jenkins.get().queue
            val queueItemId = buildId.removePrefix(BuildIdPrefix.QUEUE_ITEM).toLongOrNull()
                ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.BadRequest, "Cannot parse queue item id \"$buildId\"")

            val queueItem = queue.getItem(queueItemId)
                ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.NotFound, "Queue item not found by id = $queueItemId")

            if (!queueItem.causes.hasSafeMergeCause(spaceConnection))
                return GetQueueItemOrBuildResult.Error(HttpStatusCode.NotFound, "Queue item with id $queueItemId was started not by the safe merge")

            (queueItem as? LeftItem)?.executable?.let { executable ->
                return if (executable is Run<*, *>)
                    GetQueueItemOrBuildResult.Build(executable)
                else
                    GetQueueItemOrBuildResult.Error(HttpStatusCode.NotFound, "Queue item with id $queueItemId resulted in unexpected executable of type '${executable?.javaClass?.name}'")
            }

            return GetQueueItemOrBuildResult.QueueItem(queueItem)
        }

        buildId.startsWith(BuildIdPrefix.BUILD) -> {
            val buildNumber = buildId.removePrefix(BuildIdPrefix.BUILD).toIntOrNull()
                ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.BadRequest, "Cannot parse build id \"$buildId\"")

            val job = (Jenkins.get().getItemByFullName(project) as? Job<*, *>)
                ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.NotFound, "Project not found in Jenkins by name \"$project\"")

            val build = job.getBuildByNumber(buildNumber)
                ?: return GetQueueItemOrBuildResult.Error(HttpStatusCode.NotFound, "Build not found by number $buildNumber in project \"$project\"")

            return GetQueueItemOrBuildResult.Build(build)
        }

        else ->
            return GetQueueItemOrBuildResult.Error(HttpStatusCode.BadRequest, "Cannot parse build id \"$buildId\"")
    }
}

// we do not fill executable because we know that there is no running build for this queue item,
// otherwise we would have got it from the getQueueItemOrBuild call right away
fun Queue.Item.toJenkinsBuild() =
    JenkinsBuild.QueueItem(
        id,
        if (Jenkins.get().rootUrl != null) Jenkins.get().rootUrl + url else url,
        (this as? LeftItem)?.isCancelled ?: false,
        isStuck,
        why)

@Suppress("DEPRECATION")
fun Run<*, *>.toJenkinsBuild() =
    JenkinsBuild.RunningBuild(
        id,
        if (Jenkins.get().rootUrl != null) absoluteUrl else url,
        getFullDisplayName(),
        isInProgress(),
        getDuration(),
        getResult()?.toString(),
        getQueueId()
    )

/**
 * Up-to-date information about Jenkins build that is performing safe merge operation
 */
@OptIn(ExperimentalSpaceSdkApi::class)
private sealed class GetQueueItemOrBuildResult {
    class Build(val run: Run<*, *>): GetQueueItemOrBuildResult()
    class QueueItem(val queueItem: Queue.Item): GetQueueItemOrBuildResult()
    class Error(val statusCode: HttpStatusCode, val message: String): GetQueueItemOrBuildResult()
}

/**
 * Whenever a job or workflow is queued for execution in Jenkins, the build is not immediately created for it.
 * First, a queue item is created put into the queue, and build is only created when it actually starts running.
 * Queue items and builds are identified by sequential integer numbers, but their sequences are independent of each other,
 * so we need to tell one from the other when communicating build statuses to Space.
 *
 * Build identifiers used in communication with Space are strings composed from a prefix
 * designating whether it's a build or a queue item, and the actual build or queue item integer id.
 */
object BuildIdPrefix {
    const val QUEUE_ITEM = "queue-item-"
    const val BUILD = "build-"
}

private val LOGGER = Logger.getLogger("SafeMerge")
