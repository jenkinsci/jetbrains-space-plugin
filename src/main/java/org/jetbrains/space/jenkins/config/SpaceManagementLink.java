package org.jetbrains.space.jenkins.config;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Hudson;
import hudson.model.ManagementLink;
import jenkins.model.Jenkins;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.space.jenkins.SpaceOAuthKt;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;

/**
 * Represents the link to the SpaceCode connections configuration on the global "Jenkins / Manage" page
 * and the configuration page itself behind this link.
 */
@Extension
public class SpaceManagementLink extends ManagementLink implements StaplerProxy {

    @Override
    public String getIconFileName() {
        return "symbol-space plugin-jetbrains-space";
    }

    @Override
    public String getDisplayName() {
        return "JetBrains SpaceCode";
    }

    @Override
    public String getUrlName() {
        return "spacecode";
    }

    @Override
    public String getDescription() {
        return "Use Jenkins to build source code hosted in JetBrains SpaceCode git repositories";
    }

    @Override
    public @NotNull Category getCategory() {
        return Category.CONFIGURATION;
    }

    /**
     * Getter property for the jelly page
     */
    public List<SpaceConnection> getConnections() {
        return ExtensionList.lookupSingleton(SpacePluginConfiguration.class).getConnections();
    }

    /**
     * Endpoint that is called by the dialog opened by the client-side React app to establish a new connection to SpaceCode.
     * Redirects the user to the SpaceCode entrypoint URL for creating a new org-level SpaceCode application.
     */
    @POST
    public void doCreateSpaceApp(StaplerRequest req, StaplerResponse rsp) throws IOException {
        rsp.sendRedirect(302, SpaceOAuthKt.buildCreateRootSpaceAppUrl());
    }

    /**
     * Endpoint for the callback redirect from SpaceCode after the application creation is completed.
     * Renders the SpaceCode connections list jelly view, but the javascript on it recognizes the request url,
     * closes the application installation dialog and notifies the parent window that it should update the list of SpaceCode connections.
     */
    @GET
    public void doAppInstalled(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    /**
     * Endpoint that is called by the client-side React app when the user deletes connection to SpaceCode organization.
     */
    @POST
    public void doDeleteConnection(StaplerRequest req, StaplerResponse rsp) throws IOException {
        String connectionId = req.getParameter("connectionId");
        if (!ExtensionList.lookupSingleton(SpacePluginConfiguration.class).removeConnectionById(connectionId)) {
            rsp.sendError(404, "Connection not found by id");
        }
    }

    /**
     * Administrative permissions in Jenkins are required to access the SpaceCode connections management page
     */
    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Hudson.ADMINISTER);
        return this;
    }
}
