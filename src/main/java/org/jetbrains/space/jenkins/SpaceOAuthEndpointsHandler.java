package org.jetbrains.space.jenkins;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.Secret;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP endpoints for initiating and completing the OAuth flow in SpaceCode
 * to obtain the list of projects and (for multibranch projects) repositories from SpaceCode on behalf of the current user.
 * This process takes place when connecting a Jenkins job or multibranch project to the SpaceCode project (establishing a project-level connection).
 */
@Extension
public class SpaceOAuthEndpointsHandler implements UnprotectedRootAction  {

    public static final String OAUTH_SESSION_KEY = "OAUTH-SESSION-";


    /**
     * User is redirected to this url in a dialog window to obtain a short-lived token for fetching the list of projects on user's behalf.
     * This URL just generates the OAuth flow start URL for SpaceCode, with the redirectUrl pointing to the `doOauthComplete` endpoint,
     * and redirects the user to it.
     */
    @GET
    public void doObtainSpaceUserToken(StaplerRequest request, StaplerResponse response) throws IOException {
        String connectionId = request.getParameter("connectionId");
        boolean withVcsReadScope = request.getParameter("withVcsReadScope") != null;
        HttpSession session = request.getSession();
        String oAuthSessionId = UUID.randomUUID().toString();
        session.setAttribute(OAUTH_SESSION_KEY + oAuthSessionId, new SpaceOAuthSession(connectionId, null));

        String spaceOAuthUrl = SpaceOAuthKt.getSpaceOAuthUrl(oAuthSessionId, connectionId, withVcsReadScope);
        response.sendRedirect(spaceOAuthUrl);
    }

    /**
     * User is redirected here after finishing the OAuth flow with SpaceCode in the dialog window
     * to obtain a short-lived token for fetching the list of projects on user's behalf.
     * This OAuth flow is started by the `doObtainSpaceUserToken` HTTP endpoint.
     *
     * The resulting static html just closes the dialog window, notifying the parent window of the successful completion of the OAuth flow
     * and passing to the parent window the OAuth code that can be exchanged for access token.
     * @param request
     * @param response
     * @throws IOException
     */
    @GET
    public void doOauthComplete(StaplerRequest request, StaplerResponse response) throws IOException {
        response.setContentType("text/html");
        response.getOutputStream().write(ReactAppKt.getOAuthCompleteHtml().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extracts OAuth flow code from the state query parameter, exchanges it for the SpaceCode access token and stores the token
     * in the current user session for later use.
     */
    @POST
    public void doExchangeCodeForToken(StaplerRequest request, StaplerResponse response) throws IOException {
        String oAuthSessionId = request.getParameter("state");
        HttpSession session = request.getSession();
        SpaceOAuthSession spaceOAuthSession = (SpaceOAuthSession) session.getAttribute(OAUTH_SESSION_KEY + oAuthSessionId);
        if (spaceOAuthSession == null) {
            response.sendError(403, "OAuth session not found");
            return;
        }

        String code = request.getParameter("code");
        String token = SpaceOAuthKt.exchangeCodeForToken(spaceOAuthSession.getConnectionId(), code);
        if (token == null) {
            response.sendError(403, "Could not exchange code for auth token");
            return;
        }

        request.getSession().setAttribute(
                OAUTH_SESSION_KEY + oAuthSessionId,
                new SpaceOAuthSession(spaceOAuthSession.getConnectionId(), Secret.fromString(token)));
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

    public static final String URL = "jb-spacecode-oauth";
}
