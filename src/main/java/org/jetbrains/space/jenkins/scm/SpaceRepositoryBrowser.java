package org.jetbrains.space.jenkins.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.scm.RepositoryBrowser;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SpaceRepositoryBrowser extends GitRepositoryBrowser {

    public SpaceRepositoryBrowser(SpaceConnection spaceConnection, String projectKey, String repositoryName) {
        this(spaceConnection.getBaseUrl() + "/p/" + projectKey + "/repositories/" + repositoryName);
    }

    @DataBoundConstructor
    public SpaceRepositoryBrowser(String repoUrl) {
        super(repoUrl);
    }

    @Override
    public URL getDiffLink(GitChangeSet.Path path) throws IOException {
        String filePath = URLEncoder.encode(path.getPath(), StandardCharsets.UTF_8);
        return new URL(getUrl(), "commits?commits=" + path.getChangeSet().getId() + "&file=" + filePath);
    }

    @Override
    public URL getFileLink(GitChangeSet.Path path) throws IOException {
        String filePath = URLEncoder.encode(path.getPath(), StandardCharsets.UTF_8);
        return new URL(getUrl(), "files/" + path.getChangeSet().getId() + "/" + filePath);
    }

    @Override
    public URL getChangeSetLink(GitChangeSet changeSet) throws IOException {
        return new URL(getUrl(), "revision/" + changeSet.getId());
    }

    @Extension
    @Symbol("jbSpace")
    public static class SpaceRepositoryBrowserDescriptor extends Descriptor<RepositoryBrowser<?>> {
        @NonNull
        public String getDisplayName() {
            return "JetBrains Space";
        }

        @Override
        public SpaceRepositoryBrowser newInstance(StaplerRequest req, @NonNull JSONObject jsonObject) {
            return req.bindJSON(SpaceRepositoryBrowser.class, jsonObject);
        }
    }

}
