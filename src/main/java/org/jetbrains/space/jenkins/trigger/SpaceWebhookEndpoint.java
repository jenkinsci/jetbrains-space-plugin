package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

/**
 * Handles HTTP requests for the webhook callbacks or safe merge commands from Space.
 * Matches the incoming payload with the trigger on one of the jobs and triggers this job if all conditions are satisfied.
 */
@Extension
public class SpaceWebhookEndpoint implements UnprotectedRootAction {

    @POST
    // Authentication is performed as part of processing the request payload by Space SDK
    // lgtm[jenkins/no-permission-check]
    public void doProcess(StaplerRequest request, StaplerResponse response) {
        SpaceWebhookEndpointKt.doProcess(this, request, response);
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

    public static final String URL = "jb-space";
}
