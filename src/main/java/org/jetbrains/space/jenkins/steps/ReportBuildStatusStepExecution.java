package org.jetbrains.space.jenkins.steps;

import hudson.ExtensionList;
import hudson.model.Run;
import jenkins.model.Jenkins;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.future.FutureKt;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.resources.Projects;
import space.jetbrains.api.runtime.types.CommitExecutionStatus;
import space.jetbrains.api.runtime.types.ProjectIdentifier;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ReportBuildStatusStepExecution extends StepExecution {

    private final List<PostBuildStatusAction> actions;
    private final String buildUrl;
    private final CommitExecutionStatus buildStatus;
    private final String taskName;
    private final String taskBuildId;
    private final Long taskBuildStartTimeInMillis;
    private final String taskBuildDescription;

    public ReportBuildStatusStepExecution(
            List<PostBuildStatusAction> actions,
            CommitExecutionStatus buildStatus,
            String buildUrl,
            String taskName,
            String taskBuildId,
            Long taskBuildStartTimeInMillis,
            String taskBuildDescription,
            StepContext context
    ) {
        super(context);

        this.actions = actions;
        this.buildUrl = buildUrl;
        this.buildStatus = buildStatus;
        this.taskName = taskName;
        this.taskBuildId = taskBuildId;
        this.taskBuildStartTimeInMillis = taskBuildStartTimeInMillis;
        this.taskBuildDescription = taskBuildDescription;
    }

    @Override
    public boolean start() throws Exception {
        AtomicInteger actionsSucceeded = new AtomicInteger();
        SpacePluginConfiguration spacePluginConfiguration = ExtensionList.lookup(SpacePluginConfiguration.class)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Space plugin configuration cannot be resolved"));
        for (PostBuildStatusAction action : actions) {
            SpaceClient spaceApiClient = spacePluginConfiguration
                    .getConnectionById(action.getSpaceConnectionId())
                    .orElseThrow(() -> new IllegalArgumentException("No Space connection found by specified id"))
                    .getApiClient();

            CompletableFuture<Unit> future = FutureKt.future(
                    CoroutineScopeKt.CoroutineScope(EmptyCoroutineContext.INSTANCE),
                    EmptyCoroutineContext.INSTANCE,
                    CoroutineStart.DEFAULT,
                    (scope, continuation) -> {
                        Projects projectsApi = new Projects(spaceApiClient);
                        return projectsApi.getRepositories().getRevisions().getExternalChecks().reportExternalCheckStatus(
                                new ProjectIdentifier.Key(action.getProjectKey()),
                                action.getRepositoryName(),
                                action.getRevision(),
                                action.getBranch(),
                                Collections.emptyList(),
                                buildStatus,
                                buildUrl,
                                "Jenkins",
                                taskName,
                                taskName,
                                taskBuildId,
                                taskBuildStartTimeInMillis,
                                taskBuildDescription,
                                continuation
                        );
                    }
            );
            future.whenComplete((unit, ex) -> {
                if (ex != null) {
                    getContext().onFailure(ex);
                } else {
                    if (actionsSucceeded.getAndIncrement() == actions.size() - 1) {
                        getContext().onSuccess(unit);
                    }
                }
            });
        }
        return false;
    }
}
