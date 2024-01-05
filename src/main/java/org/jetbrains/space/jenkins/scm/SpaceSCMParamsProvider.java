package org.jetbrains.space.jenkins.scm;

import hudson.util.ListBoxModel;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.kohsuke.stapler.HttpResponse;
import space.jetbrains.api.runtime.Batch;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.resources.Projects;
import space.jetbrains.api.runtime.types.PR_Project;
import space.jetbrains.api.runtime.types.RepositoryDetails;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static org.kohsuke.stapler.HttpResponses.errorWithoutStack;

@Singleton
public class SpaceSCMParamsProvider {

    private static final Logger LOGGER = Logger.getLogger(SpaceSCMParamsProvider.class.getName());

    @Inject
    public SpacePluginConfiguration spacePluginConfiguration;


    public ListBoxModel doFillSpaceConnectionIdItems() {
        return spacePluginConfiguration.getConnections()
                .stream()
                .map(c -> new ListBoxModel.Option(c.getName(), c.getId()))
                .collect(Collectors.toCollection(ListBoxModel::new));
    }

    public HttpResponse doFillProjectKeyItems(String spaceConnectionId) {
        Optional<SpaceClient> spaceApiClient = spacePluginConfiguration.getSpaceApiClient(spaceConnectionId);
        if (spaceApiClient.isEmpty()) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "Space connection is not selected");
        }

        try {
            Projects projectsApi = new Projects(spaceApiClient.get());
            Batch<PR_Project> projects = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> projectsApi.getAllProjects(
                            null, null, null, null,
                            (p) -> {
                                p.id();
                                p.name();
                                p.key_ProjectKeyPartial((pk) -> {
                                    pk.key();
                                    return Unit.INSTANCE;
                                });
                                return Unit.INSTANCE;
                            },
                            continuation
                    )
            );
            return projects.getData().stream()
                    .map((p) -> {
                        String key = p.getKey().getKey();
                        return new ListBoxModel.Option(p.getName(), key);
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        } catch (Throwable ex) {
            LOGGER.log(Level.WARNING, ex.toString());
            return errorWithoutStack(HTTP_INTERNAL_ERROR, "Error performing request to JetBrains Space: " + ex);
        }
    }

    public HttpResponse doFillRepositoryNameItems(String spaceConnectionId, String projectKey) {
        Optional<SpaceClient> spaceApiClient = spacePluginConfiguration.getSpaceApiClient(spaceConnectionId);
        if (spaceApiClient.isEmpty()) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "Space connection is not selected");
        }

        if (projectKey == null || projectKey.isBlank()) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "Space project is not selected");
        }

        try {
            Projects projectsApi = new Projects(spaceApiClient.get());
            Batch<RepositoryDetails> repos = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> projectsApi.getRepositories().getFind().findRepositories(
                            "", null,
                            (r) -> {
                                r.projectKey();
                                r.repository();
                                return Unit.INSTANCE;
                            },
                            continuation
                    )
            );
            return repos.getData().stream()
                    .filter((r) -> r.getProjectKey().equals(projectKey))
                    .map((r) -> new ListBoxModel.Option(r.getRepository(), r.getRepository()))
                    .collect(Collectors.toCollection(ListBoxModel::new));
        } catch (Throwable ex) {
            LOGGER.log(Level.WARNING, ex.getMessage());
            return errorWithoutStack(HTTP_INTERNAL_ERROR, "Error performing request to JetBrains Space: " + ex.getMessage());
        }
    }
}
