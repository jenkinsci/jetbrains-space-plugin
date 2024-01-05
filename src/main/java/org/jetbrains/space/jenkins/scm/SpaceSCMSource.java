package org.jetbrains.space.jenkins.scm;

import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.util.ListBoxModel;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.*;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SpaceSCMSource extends SCMSource {

    private static final Logger LOGGER = Logger.getLogger(SpaceSCMSource.class.getName());

    private final String spaceConnection;
    private final String projectKey;
    private final String repositoryName;
    private List<SCMSourceTrait> traits;

    @DataBoundConstructor
    public SpaceSCMSource(
            String spaceConnection,
            String projectKey,
            String repositoryName,
            String id,
            List<SCMSourceTrait> traits
    ) {
        LOGGER.info("SpaceSCMSource instantiated");
        super.setId(id);

        this.spaceConnection = spaceConnection;
        this.projectKey = projectKey;
        this.repositoryName = repositoryName;
        this.traits = new ArrayList<>();
        if (traits != null) {
            this.traits.addAll(traits);
        }
    }

    public String getSpaceConnection() {
        return spaceConnection;
    }

    public String getProjectKey() { return projectKey; }

    public String getRepositoryName() { return repositoryName; }

    @Override
    public List<SCMSourceTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    @Override
    protected void retrieve(SCMSourceCriteria criteria, @NotNull SCMHeadObserver observer, SCMHeadEvent<?> event, @NotNull TaskListener listener) throws IOException, InterruptedException {
        LOGGER.info("SpaceSCMSource.retrieve");

        Collection<SCMHead> eventHeads = event == null ? Collections.emptySet() : event.heads(this).keySet();

        SpaceSCMSourceContext context = new SpaceSCMSourceContext(criteria, observer, eventHeads).withTraits(traits);

        try (SpaceSCMSourceRequest request = context.newRequest(this, listener)) {
            for (SpaceSCMHeadDiscoveryHandler discoveryHandler : request.getDiscoveryHandlers()) {
                // Process the stream of heads as they come in and terminate the
                // stream if the request has finished observing (returns true)
                discoveryHandler.discoverHeads().anyMatch(scmHead -> {
                    SCMRevision scmRevision = discoveryHandler.toRevision(scmHead);
                    try {
                        return request.process(
                                scmHead,
                                scmRevision,
                                this::newProbe,
                                (head, revision, isMatch) ->
                                        listener.getLogger().printf("head: %s, revision: %s, isMatch: %s%n",
                                                head, revision, isMatch));
                    } catch (IOException | InterruptedException e) {
                        listener.error("Error processing request for head: " + scmHead + ", revision: " +
                                scmRevision + ", error: " + e.getMessage());

                        return true;
                    }
                });
            }
        }
    }

    @Override
    @DataBoundSetter
    public void setTraits(List<SCMSourceTrait> traits) {
        this.traits = new ArrayList<>(Util.fixNull(traits));
    }

    @Override
    public SCM build(SCMHead head, @CheckForNull SCMRevision revision) {
        LOGGER.info("SpaceSCMSource.build");
        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        Optional<SpaceConnection> spaceConnection = descriptor.spacePluginConfiguration.getConnectionById(this.spaceConnection);

        try {
            UserRemoteConfig remoteConfig = SpaceSCM.getRepositoryConfig(spaceConnection.get(), projectKey, repositoryName);
            SpaceRepositoryBrowser repoBrowser = new SpaceRepositoryBrowser(spaceConnection.get(), projectKey, repositoryName);

            GitSCMBuilder<?> builder = new GitSCMBuilder<>(head, revision, remoteConfig.getUrl(), remoteConfig.getCredentialsId());
            builder.withBrowser(repoBrowser);
            builder.withTraits(traits);
            return builder.build();
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage());
            return new GitSCMBuilder<>(head, revision, "", "").build();
        }
    }

    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {

        @Inject
        private SpacePluginConfiguration spacePluginConfiguration;

        @Inject
        private SpaceSCMParamsProvider scmParamsValidator;

        private final GitSCMSource.DescriptorImpl gitScmSourceDescriptor;

        public DescriptorImpl() {
            super();
            gitScmSourceDescriptor = new GitSCMSource.DescriptorImpl();
        }


        @Override
        public String getDisplayName() {
            return "JetBrains Space";
        }


        @POST
        public ListBoxModel doFillSpaceConnectionIdItems() {
            return scmParamsValidator.doFillSpaceConnectionIdItems();
        }

        @POST
        public HttpResponse doFillProjectKeyItems(@QueryParameter String spaceConnectionId) {
            return scmParamsValidator.doFillProjectKeyItems(spaceConnectionId);
        }

        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String spaceConnectionId, @QueryParameter String projectKey) {
            return scmParamsValidator.doFillRepositoryNameItems(spaceConnectionId, projectKey);
        }
    }
}
