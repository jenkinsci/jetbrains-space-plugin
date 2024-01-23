package org.jetbrains.space.jenkins.trigger;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * <p>Excludes Space webhook callbacks from the CSRF protection filter.</p>
 *
 * <p>Instead of providing crumbs with HTTP request or authenticating in Jenkins with a username and API token,
 * webhook events endpoint is protected in a different way.
 * Space signs each HTTP request to an external system with a private key, and receiver can then verify this signature
 * before processing the request. This plugin uses Space Kotlin SDK for communicating with Space API and
 * in particular for handling webhook callbacks, and SDK takes care of verifying the signature for each incoming request from Space.</p>
 *
 * @see <a href="https://www.jetbrains.com/help/space/verify-space-in-application.html#verifying-requests-using-a-public-key">Verifying requests from Space using a public key</a>
 * @see <a href="https://www.jenkins.io/doc/book/security/csrf-protection/">CSRF Protection in Jenkins</a>
 */
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
