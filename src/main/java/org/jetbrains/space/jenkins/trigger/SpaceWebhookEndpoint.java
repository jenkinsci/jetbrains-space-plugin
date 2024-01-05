package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

@Extension
public class SpaceWebhookEndpoint implements UnprotectedRootAction {

    @POST
    public HttpResponse doTrigger(StaplerRequest request, StaplerResponse response) {
        return HttpResponses.ok();
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
        return null;
    }
}
