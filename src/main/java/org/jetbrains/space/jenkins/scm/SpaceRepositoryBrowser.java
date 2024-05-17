package org.jetbrains.space.jenkins.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.scm.RepositoryBrowser;
import net.sf.json.JSONObject;
import org.apache.http.client.utils.URIBuilder;
import org.jenkinsci.Symbol;
import org.jetbrains.space.jenkins.config.SpaceConnection;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides links to SpaceCode for commits, files and file diffs on the build changes page.
 * Implements an extension point from the Jenkins Git plugin.
 *
 * @see <a href="https://plugins.jenkins.io/git/#plugin-content-repository-browser">Repository Browser section in Jenkins Git plugin documentation</a>
 * @see hudson.plugins.git.browser.GitRepositoryBrowser
 */
public class SpaceRepositoryBrowser extends GitRepositoryBrowser {

    public SpaceRepositoryBrowser(String spaceUrl, String projectKey, String repositoryName) {
        this(spaceUrl + "/p/" + projectKey + "/repositories/" + repositoryName);
    }

    @DataBoundConstructor
    public SpaceRepositoryBrowser(String repoUrl) {
        super(sanitizeRepoUrl(repoUrl));
    }

    /**
     * Clean SpaceCode repository url of query parameters and irrelevant stuff
     * and restrict to first 4 path segments to keep only the required part:
     * <pre>/p/{project key}/repositories/{repo name}</pre>
     */
    private static String sanitizeRepoUrl(String repoUrl) {
        try {
            // clean repo url of query parameters and fragment
            //
            URIBuilder builder = new URIBuilder(repoUrl).removeQuery().setUserInfo(null).setFragment(null);
            builder.setPathSegments(builder.getPathSegments().stream().limit(4).collect(Collectors.toList()));
            return builder.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid repoUrl", e);
        }
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
        @Override
        public String getDisplayName() {
            return "JetBrains SpaceCode";
        }

        @Override
        public SpaceRepositoryBrowser newInstance(StaplerRequest req, @NonNull JSONObject jsonObject) {
            return req.bindJSON(SpaceRepositoryBrowser.class, jsonObject);
        }
    }
}
