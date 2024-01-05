package org.jetbrains.space.jenkins;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.listeners.SpaceGitScmCheckoutAction;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.resources.Projects;
import space.jetbrains.api.runtime.types.CommitExecutionStatus;
import space.jetbrains.api.runtime.types.ProjectIdentifier;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;

public class SpaceApiClient {

    private static final Logger LOGGER = Logger.getLogger(SpaceApiClient.class.getName());

    private final SpaceClient spaceApiClient;
    private final Projects projectsApi;
    private final String projectKey;
    private final String repositoryName;

    public SpaceApiClient(SpaceConnection spaceConnection, String projectKey, String repositoryName) {
        this.spaceApiClient = spaceConnection.getApiClient();
        this.projectsApi = new Projects(this.spaceApiClient);
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
    }

    public void postBuildStatus(SpaceGitScmCheckoutAction action, Run<?, ?> build, CommitExecutionStatus status, TaskListener listener) {
        try {
            BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> projectsApi.getRepositories().getRevisions().getExternalChecks().reportExternalCheckStatus(
                            new ProjectIdentifier.Key(action.getProjectKey()),
                            action.getRepositoryName(),
                            action.getRevision(),
                            action.getBranch(),
                            Collections.emptyList(),
                            (status != null) ? status : getStatusFromBuild(build),
                            build.getAbsoluteUrl(),
                            "Jenkins",
                            build.getParent().getFullName(),
                            build.getParent().getFullName(),
                            Integer.toString(build.getNumber()),
                            build.getStartTimeInMillis(),
                            build.getDescription(),
                            continuation
                    )
            );
        } catch (Throwable ex) {
            String message = "Failed to post build status to JetBrains Space, details: " + ex;
            LOGGER.warning(message);
            listener.getLogger().println(message);
        }
    }


    public static CommitExecutionStatus getStatusFromBuild(Run<?, ?> build) {
        CommitExecutionStatus status;
        if (build.isBuilding()) {
            status = CommitExecutionStatus.RUNNING;
        } else if (successfulResults.contains(build.getResult())) {
            status = CommitExecutionStatus.SUCCEEDED;
        } else if (cancelledResults.contains(build.getResult())) {
            status = CommitExecutionStatus.TERMINATED;
        } else {
            status = CommitExecutionStatus.FAILED;
        }
        return status;
    }

    private static final Collection<Result> successfulResults = Arrays.asList(Result.SUCCESS, Result.UNSTABLE);
    private static final Collection<Result> cancelledResults = Arrays.asList(Result.ABORTED, Result.NOT_BUILT);
}
