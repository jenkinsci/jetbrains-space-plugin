package org.jetbrains.space.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.model.UnprotectedRootAction;
import hudson.util.Secret;
import jenkins.branch.MultiBranchProject;
import kotlin.Unit;
import org.jetbrains.space.jenkins.config.*;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jetbrains.space.jenkins.ConnectProjectKt.connectProject;

/**
 * Groups a set of HTTP endpoints used by the plugin's client-side React application
 * to fetch and manage SpaceCode connections
 */
@Extension
public class SpaceProjectConnectionsHandler implements UnprotectedRootAction {

    /**
     * Fetches the list of projects from SpaceCode on behalf of the current user
     * to populate the dropdown list of projects for establishing project-level connection in Jenkins job or multibranch project's branch source.
     * <br />
     * SpaceCode token to use when fetching the projects is expected to be present in the current session,
     * prepared by the previously completed OAuth flow in SpaceCode.
     */
    @POST
    public void doFetchProjects(StaplerRequest request, StaplerResponse response) throws IOException {
        String oAuthSessionId = request.getParameter("sessionId");
        SpaceOAuthSession spaceOAuthSession = (SpaceOAuthSession) request.getSession().getAttribute(SpaceOAuthEndpointsHandler.OAUTH_SESSION_KEY + oAuthSessionId);
        if (spaceOAuthSession == null || spaceOAuthSession.getToken() == null) {
            response.sendError(403, "OAuth session not found");
            return;
        }

        List<SpaceProject> spaceProjects = SpaceOAuthKt.fetchSpaceProjects(spaceOAuthSession);
        response.setContentType("application/json");
        response.getWriter().write(new ObjectMapper().writeValueAsString(spaceProjects));
    }

    /**
     * Fetches the list of repositories for a given project from SpaceCode on behalf of the current user
     * to populate the dropdown list of repositories for configuring Jenkins multibranch project's branch source.
     * <br />
     * SpaceCode token to use when fetching the projects is expected to be present in the current session,
     * prepared by the previously completed OAuth flow in SpaceCode.
     */
    @POST
    public void doFetchRepositories(StaplerRequest request, StaplerResponse response) throws IOException {
        String projectKey = request.getParameter("projectKey");
        if (projectKey == null) {
            response.sendError(400, "Project key is missing");
            return;
        }

        String oAuthSessionId = request.getParameter("sessionId");
        SpaceOAuthSession spaceOAuthSession = (SpaceOAuthSession) request.getSession().getAttribute(SpaceOAuthEndpointsHandler.OAUTH_SESSION_KEY + oAuthSessionId);
        if (spaceOAuthSession == null || spaceOAuthSession.getToken() == null) {
            response.sendError(403, "OAuth session not found");
            return;
        }

        List<String> spaceRepositories = SpaceOAuthKt.fetchSpaceRepositories(spaceOAuthSession, projectKey);
        response.setContentType("application/json");
        response.getWriter().write(new ObjectMapper().writeValueAsString(spaceRepositories));
    }

    /**
     * Returns JSON with the runtime URLs for React app javascript and css files.
     * This endpoint is called by the vanilla js to dynamically inject React app into the page.
     * The names of js and css files generated by webpack contain version hashes and thus aren't known at compile time
     * and cannot be referenced directly from jelly pages.
     */
    @GET
    public void doReactAppFiles(StaplerRequest request, StaplerResponse response) throws IOException {
        response.setContentType("application/json");
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("js", ReactAppKt.getReactAppJsPath());
        node.put("css", ReactAppKt.getReactAppCssPath());
        new ObjectMapper().writeValue(response.getWriter(), node);
    }

    /**
     * Fetches the current state of the SpaceCode org-level application from SpaceCode
     * (application id, name, client id and the state of permissions granted).
     * This is used to render a warning about failing SpaceCode connectivity or unapproved permissions.
     * This warning in turn is updated live with the use of SpaceCode webhooks for application authorizations,
     * see [SpaceProjectConnectionsHandler.doWaitForPermissionsApprove] endpoint.
     */
    @GET
    public void doSpaceApp(StaplerRequest request, StaplerResponse response) throws IOException {
        String connectionId = request.getParameter("connectionId");
        SpaceConnection connection = SpacePluginConfigurationKt.getOrgConnection(connectionId);
        if (connection == null) {
            response.sendError(404, "SpaceCode connection not found");
            return;
        }

        SpaceAppRequestResult result = SpaceConnectionKt.fetchSpaceApp(connection);
        if (result instanceof SpaceAppRequestError) {
            response.setStatus(((SpaceAppRequestError) result).getStatusCode());
            response.getWriter().write(((SpaceAppRequestError) result).getMessage());
            return;
        }

        response.setContentType("application/json");
        new ObjectMapper().writeValue(response.getWriter(), result);
    }

    /**
     * Fetches the current state of the SpaceCode project-level application connected with a Jenkins job from SpaceCode
     * (application id, name, client id and the state of permissions granted).
     * This is used to render a warning about failing SpaceCode connectivity or unapproved permissions.
     * This warning in turn is updated live with the use of SpaceCode webhooks for application authorizations,
     * see [SpaceProjectConnectionsHandler.doWaitForPermissionsApprove] endpoint.
     */
    @GET
    public void doProjectSpaceApp(StaplerRequest request, StaplerResponse response) throws IOException {
        String jenkinsItemFullName = request.getParameter("jenkinsItem");
        TopLevelItem item = JobExtensionsKt.getItemByName(jenkinsItemFullName);
        if (!(item instanceof Job<?,?>)) {
            response.sendError(403);
            return;
        }
        item.checkPermission(Item.CONFIGURE);

        SpaceAppRequestResult result = JobExtensionsKt.fetchProjectSpaceApp((Job<?, ?>) item);
        if (result instanceof SpaceAppRequestError) {
            response.setStatus(((SpaceAppRequestError) result).getStatusCode());
            response.getWriter().write(((SpaceAppRequestError) result).getMessage());
            return;
        }

        response.setContentType("application/json");
        new ObjectMapper().writeValue(response.getWriter(), result);
    }

    /**
     * Fetches the current state of the SpaceCode project-level application referenced by a multibranch project's branch source ([SpaceSCMSource])
     * (application id, name, client id and the state of permissions granted).
     * This is used to render a warning about failing SpaceCode connectivity or unapproved permissions.
     * This warning in turn is updated live with the use of SpaceCode webhooks for application authorizations,
     * see [SpaceProjectConnectionsHandler.doWaitForPermissionsApprove] endpoint.
     */
    @GET
    public void doMultiBranchProjectSpaceApp(StaplerRequest request, StaplerResponse response) throws IOException {
        String jenkinsItemFullName = request.getParameter("jenkinsItem");
        TopLevelItem item = JobExtensionsKt.getItemByName(jenkinsItemFullName);
        if (!(item instanceof MultiBranchProject<?, ?>)) {
            response.sendError(403);
            return;
        }
        item.checkPermission(Item.CONFIGURE);

        String connectionId = request.getParameter("connectionId");
        if (connectionId == null) {
            response.sendError(400, "connectionId parameter is missing");
            return;
        }

        String projectKey = request.getParameter("projectKey");
        if (projectKey == null) {
            response.sendError(400, "projectKey parameter is missing");
            return;
        }

        SpaceAppRequestResult result = JobExtensionsKt.fetchProjectSpaceApp((MultiBranchProject<?, ?>) item, connectionId, projectKey);
        if (result instanceof SpaceAppRequestError) {
            response.setStatus(((SpaceAppRequestError) result).getStatusCode());
            response.getWriter().write(((SpaceAppRequestError) result).getMessage());
            return;
        }

        response.setContentType("application/json");
        new ObjectMapper().writeValue(response.getWriter(), result);
    }

    /**
     * HTTP endpoint that opens an event stream to notify the browser client about the changes in approved permissions on SpaceCode side.
     * Once an event arrives, the browser client is notified and the event stream gets closed.
     * The client should make another call to this endpoint if it is still interested in further updates to the application's permissions.
     *
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-side events</a>
     */
    @GET
    public void doWaitForPermissionsApprove(StaplerRequest request, StaplerResponse response) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);

        String appId = request.getParameter("appId");
        SpacePermissionsApproveListener.INSTANCE.wait(appId).thenAccept((Unit t) -> {
            try {
                response.getWriter().write("data: update\n\n");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not write to response", e);
            }
            asyncContext.complete();
        });
    }

    /**
     * Fetches the list of configured org-level SpaceCode connections for the global configuration page
     */
    @GET
    public void doSpaceConnections(StaplerRequest request, StaplerResponse response) throws IOException {
        response.setContentType("application/json");
        ArrayNode arr = new ObjectMapper().createArrayNode();
        ExtensionList.lookupSingleton(SpacePluginConfiguration.class).getConnections().forEach(connection -> {
            ObjectNode conn = arr.addObject();
            conn.put("id", connection.getId());
            conn.put("baseUrl", connection.getBaseUrl());
        });
        new ObjectMapper().writeValue(response.getWriter(), arr);
    }

    /**
     * Establishes a project-level connection between a SpaceCode project and a Jenkins job, workflow or multibranch project's branch source.
     * This HTTP endpoint is called from the client-side React app and expects the following query string parameters:
     * <ul>
     *     <li><b>jenkinsItem</b> - full name of the Jenkins job, workflow or multibranch project</li>
     *     <li><b>connectionId</b> - id of the org-level SpaceCode connection to use</li>
     *     <li><b>projectKey</b> - key of the SpaceCode project to connect to</li>
     *     <li><b>repository</b> - (optional, used only for multibranch projects) - SpaceCode repository to use for branches or merge requests discovery for the multibranch project</li>
     * </ul>
     */
    @POST
    public void doConnectToProject(StaplerRequest request, StaplerResponse response) throws IOException {
        String jenkinsItemFullName = request.getParameter("jenkinsItem");
        if (jenkinsItemFullName == null) {
            response.sendError(400, "No job specified");
            return;
        }
        String spaceConnectionId = request.getParameter("connectionId");
        if (spaceConnectionId == null) {
            response.sendError(400, "No connectionId specified");
            return;
        }

        String spaceProjectKey = request.getParameter("projectKey");
        if (spaceProjectKey == null) {
            response.sendError(400, "No projectKey specified");
            return;
        }

        String spaceRepository = request.getParameter("repository");

        TopLevelItem item = JobExtensionsKt.getItemByName(jenkinsItemFullName);
        if (item == null) {
            response.sendError(400, "Job not found");
            return;
        }

        if (!item.hasPermission(Item.CONFIGURE)) {
            response.sendError(403, "Forbidden");
            return;
        }

        connectProject(item, spaceConnectionId, spaceProjectKey, spaceRepository, response);
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
        return "jb-space-projects";
    }

    private static final Logger LOGGER = Logger.getLogger(SpaceProjectConnectionsHandler.class.getName());
}
