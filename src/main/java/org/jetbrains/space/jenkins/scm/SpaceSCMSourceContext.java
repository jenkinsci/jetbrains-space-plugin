package org.jetbrains.space.jenkins.scm;

import hudson.model.TaskListener;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.trait.SCMSourceContext;
import org.jetbrains.annotations.NotNull;

import javax.annotation.CheckForNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static java.util.Objects.requireNonNull;

public class SpaceSCMSourceContext extends SCMSourceContext<SpaceSCMSourceContext, SpaceSCMSourceRequest> {

    private final Collection<SCMHead> eventHeads;
    private final Collection<SpaceSCMHeadDiscoveryHandler> discoveryHandlers = new ArrayList<>();

    public SpaceSCMSourceContext(
            @CheckForNull SCMSourceCriteria criteria,
            SCMHeadObserver observer,
            Collection<SCMHead> eventHeads
    ) {
        super(criteria, observer);
        this.eventHeads = requireNonNull(eventHeads, "eventHeads");
    }

    @NotNull
    @Override
    public SpaceSCMSourceRequest newRequest(@NotNull SCMSource source, TaskListener listener) {
        return new SpaceSCMSourceRequest(source, this, listener);
    }

    public Collection<SCMHead> getEventHeads() {
        return Collections.unmodifiableCollection(eventHeads);
    }

    public Collection<SpaceSCMHeadDiscoveryHandler> getDiscoveryHandlers() {
        return Collections.unmodifiableCollection(discoveryHandlers);
    }

    public void withDiscoveryHandler(SpaceSCMHeadDiscoveryHandler handler) {
        discoveryHandlers.add(requireNonNull(handler, "handler"));
    }
}
