package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.SpaceApiClient;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.steps.PostBuildStatusAction;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension
public class RunListenerImpl extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(RunListenerImpl.class.getName());

    @Inject
    private SpacePluginConfiguration spacePluginConfiguration;

    @Override
    public void onCompleted(Run<?, ?> build, @NotNull TaskListener listener) {
        List<PostBuildStatusAction> postBuildStatusActions = build.getActions(PostBuildStatusAction.class);
        List<SpaceGitScmCheckoutAction> checkoutActions = build.getActions(SpaceGitScmCheckoutAction.class).stream()
                .filter(SpaceGitScmCheckoutAction::getPostBuildStatusToSpace)
                .filter(action -> postBuildStatusActions.stream().noneMatch(a ->
                                Objects.equals(a.getSpaceConnectionId(), action.getSpaceConnectionId()) &&
                                        Objects.equals(a.getProjectKey(), action.getProjectKey()) &&
                                        Objects.equals(a.getRepositoryName(), action.getRepositoryName())))
                .collect(Collectors.toList());

        for (SpaceGitScmCheckoutAction action : checkoutActions) {
            Optional<SpaceConnection> spaceConnection = spacePluginConfiguration.getConnectionById(action.getSpaceConnectionId());
            if (spaceConnection.isEmpty()) {
                LOGGER.info("RunListenerImpl.onCompleted - could not find Space connection");
                return;
            }
            SpaceApiClient spaceApiClient = new SpaceApiClient(spaceConnection.get(), action.getProjectKey(), action.getRepositoryName());
            spaceApiClient.postBuildStatus(action, build, null, listener);
        }
    }
}
