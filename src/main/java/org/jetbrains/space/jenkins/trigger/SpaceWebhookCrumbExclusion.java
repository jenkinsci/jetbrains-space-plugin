package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Extension
public class SpaceWebhookCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String pathInfo = request.getPathInfo();
        if (pathInfo.isBlank() || !pathInfo.startsWith(EXCLUSION_PATH)) {
            return false;
        }
        chain.doFilter(request, response);
        return true;
    }

    private static final String EXCLUSION_PATH = "/" + SpaceWebhookEndpoint.URL + "/";
}
