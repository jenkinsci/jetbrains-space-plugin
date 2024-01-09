package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import kotlin.Pair;
import org.jetbrains.space.jenkins.SpaceAppInstanceStorage;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.logging.Logger;

@Extension
public class SpaceWebhookEndpoint implements UnprotectedRootAction {

    public static final String URL = "jb-space-webhook";

    private static final Logger LOGGER = Logger.getLogger(SpaceWebhookEndpoint.class.getName());

    @Inject
    private SpaceAppInstanceStorage spaceAppInstanceStorage;

    @Override
    public String getUrlName() {
        return URL;
    }

    @POST
    public HttpResponse doTrigger(StaplerRequest request, StaplerResponse response) {
        String contentType = request.getContentType();
        if (contentType != null && !contentType.startsWith("application/json")) {
            LOGGER.warning(String.format("Invalid content type %s", contentType));
            return HttpResponses.errorWithoutStack(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Expected application/json content type");
        }

        Pair<Integer, String> processResponse = SpaceWebhookTrigger.Companion.processWebhookPayload(
                request, spaceAppInstanceStorage, (job) -> new ArrayList<>(job.getTriggers().values())
        );

        if (processResponse != null) {
            int responseStatusCode = processResponse.component1();
            String responseBody = processResponse.component2();
            return (responseStatusCode < 400)
                    ? HttpResponses.status(responseStatusCode)
                    : HttpResponses.error(responseStatusCode, responseBody);
        }

        LOGGER.warning("Webhook processing resulted in no response, responding OK by default");
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
}
