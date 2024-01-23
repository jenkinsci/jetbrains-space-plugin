package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import org.jetbrains.space.jenkins.config.SpaceAppInstanceStorageImpl;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;

/**
 * Handles HTTP requests for the webhook callbacks from Space.
 * Matches the incoming event with the trigger on one of the jobs and triggers this job if all conditions are satisfied.
 */
@Extension
public class SpaceWebhookEndpoint implements UnprotectedRootAction {

    @Inject
    private SpaceAppInstanceStorageImpl spaceAppInstanceStorage;

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

    public SpaceAppInstanceStorageImpl getSpaceAppInstanceStorage() {
        return spaceAppInstanceStorage;
    }
}
