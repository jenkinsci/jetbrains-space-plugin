package org.jetbrains.space.jenkins.listeners;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import org.jetbrains.space.jenkins.SpaceApiClient;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.jetbrains.space.jenkins.config.SpaceRepository;
import org.jetbrains.space.jenkins.scm.SpaceSCM;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

@Extension
public class SCMListenerImpl extends SCMListener {

    private static final Logger LOGGER = Logger.getLogger(SCMListenerImpl.class.getName());

    @Inject
    private SpacePluginConfiguration spacePluginConfiguration;

    @CheckForNull
    public GitSCM getUnderlyingGitSCM(SCM scm) {
        if (scm instanceof GitSCM) {
            // Already a git SCM
            return (GitSCM) scm;
        }
        if (scm instanceof SpaceSCM) {
            return ((SpaceSCM) scm).getGitSCM();
        }
        return null;
    }

    @Override
    public void onCheckout(
            Run<?, ?> build,
            SCM scm,
            FilePath workspace,
            TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState pollingBaseline
    ) {
        LOGGER.info("SpaceSCMListener.onCheckout called for " + scm.getType());

        GitSCM underlyingGitScm = getUnderlyingGitSCM(scm);
        if (underlyingGitScm == null) {
            LOGGER.info("SpaceSCMListener.onCheckout - no underlying Git SCM instance");
            return;
        }
        Map<String, String> env = new HashMap<>();
        underlyingGitScm.buildEnvironment(build, env);

        Optional<SpaceRepository> spaceRepository = Optional.empty();
        SpaceSCM spaceSCM = getSpaceSCM(scm);
        if (spaceSCM != null) {
            spaceRepository = spacePluginConfiguration
                    .getConnectionByIdOrName(spaceSCM.getSpaceConnectionId(), spaceSCM.getSpaceConnection())
                    .map(c -> new SpaceRepository(c, spaceSCM.getProjectKey(), spaceSCM.getRepositoryName()));
        } else {
//            spaceRepository = spacePluginConfiguration.getSpaceRepositoryByGitCloneUrl(env.get(GitSCM.GIT_URL));
        }

        if (spaceRepository.isEmpty()) {
            LOGGER.info("SpaceSCMListener.onCheckout - no Space SCM found");
            return;
        }

        SpaceConnection spaceConnection = spaceRepository.get().getSpaceConnection();
        SpaceGitScmCheckoutAction revisionAction = new SpaceGitScmCheckoutAction(
                spaceConnection.getId(),
                spaceRepository.get().getProjectKey(),
                spaceRepository.get().getRepositoryName(),
                env.get(GitSCM.GIT_BRANCH),
                env.get(GitSCM.GIT_COMMIT),
                spaceSCM.getPostBuildStatusToSpace()
        );
        build.addAction(revisionAction);

        if (revisionAction.getPostBuildStatusToSpace()) {
            SpaceApiClient spaceApiClient = new SpaceApiClient(spaceConnection, revisionAction.getProjectKey(), revisionAction.getRepositoryName());
            spaceApiClient.postBuildStatus(revisionAction, build, null, listener);
        }
    }

    @CheckForNull
    public SpaceSCM getSpaceSCM(SCM scm) {
        if (scm instanceof SpaceSCM)
            return (SpaceSCM)scm;

        return null;
    }
}
