package org.jetbrains.space.jenkins.scm;

import hudson.model.TaskListener;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

public class SpaceSCMSourceRequest extends SCMSourceRequest {

    private final Collection<SpaceSCMHeadDiscoveryHandler> discoveryHandlers;

    protected SpaceSCMSourceRequest(@NotNull SCMSource source, @NotNull SpaceSCMSourceContext context, TaskListener listener) {
        super(source, context, listener);
        this.discoveryHandlers = requireNonNull(context.getDiscoveryHandlers(), "discoveryHandlers");
    }

    public Collection<SpaceSCMHeadDiscoveryHandler> getDiscoveryHandlers() {
        return discoveryHandlers;
    }

}
