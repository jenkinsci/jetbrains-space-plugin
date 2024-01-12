package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import org.jetbrains.space.jenkins.config.SpaceAppInstanceStorage;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;

@Extension
public class SpaceWebhookEndpoint implements UnprotectedRootAction {

    @Inject
    private SpaceAppInstanceStorage spaceAppInstanceStorage;

    @POST
    public void doTrigger(StaplerRequest request, StaplerResponse response) {
        SpaceWebhookEndpointKt.doTrigger(this, request, response);
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL;
    }

    public static final String URL = "jb-space-webhook";

    public SpaceAppInstanceStorage getSpaceAppInstanceStorage() {
        return spaceAppInstanceStorage;
    }
}
