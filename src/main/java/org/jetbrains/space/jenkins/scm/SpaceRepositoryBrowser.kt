package org.jetbrains.space.jenkins.scm

import hudson.Extension
import hudson.model.Descriptor
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.browser.GitRepositoryBrowser
import hudson.scm.RepositoryBrowser
import net.sf.json.JSONObject
import org.jenkinsci.Symbol
import org.jetbrains.space.jenkins.config.SpaceConnection
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.StaplerRequest
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SpaceRepositoryBrowser @DataBoundConstructor constructor(repoUrl: String?) : GitRepositoryBrowser(repoUrl) {

    constructor(
        spaceConnection: SpaceConnection,
        projectKey: String,
        repositoryName: String
    ) : this(spaceConnection.baseUrl + "/p/" + projectKey + "/repositories/" + repositoryName)

    @Throws(IOException::class)
    override fun getDiffLink(path: GitChangeSet.Path): URL {
        val filePath = URLEncoder.encode(path.path, StandardCharsets.UTF_8)
        return URL(url, "commits?commits=" + path.changeSet.id + "&file=" + filePath)
    }

    @Throws(IOException::class)
    override fun getFileLink(path: GitChangeSet.Path): URL {
        val filePath = URLEncoder.encode(path.path, StandardCharsets.UTF_8)
        return URL(url, "files/" + path.changeSet.id + "/" + filePath)
    }

    @Throws(IOException::class)
    override fun getChangeSetLink(changeSet: GitChangeSet): URL {
        return URL(url, "revision/" + changeSet.id)
    }

    @Extension
    @Symbol("jbSpace")
    class SpaceRepositoryBrowserDescriptor : Descriptor<RepositoryBrowser<*>>() {
        override fun getDisplayName(): String {
            return "JetBrains Space"
        }

        override fun newInstance(req: StaplerRequest?, jsonObject: JSONObject): SpaceRepositoryBrowser {
            return req!!.bindJSON(SpaceRepositoryBrowser::class.java, jsonObject)
        }
    }
}