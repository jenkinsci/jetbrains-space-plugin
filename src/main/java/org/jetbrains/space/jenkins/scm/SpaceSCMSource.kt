package org.jetbrains.space.jenkins.scm

import hudson.Extension
import hudson.model.TaskListener
import hudson.scm.SCM
import hudson.util.ListBoxModel
import jenkins.plugins.git.GitSCMBuilder
import jenkins.scm.api.*
import jenkins.scm.api.trait.SCMSourceTrait
import org.jetbrains.space.jenkins.config.SpacePluginConfiguration
import org.jetbrains.space.jenkins.config.getConnectionById
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class SpaceSCMSource @DataBoundConstructor constructor(
    val spaceConnection: String,
    val projectKey: String,
    val repositoryName: String,
    id: String?
) : SCMSource() {

    init {
        super.setId(id)
    }

    override fun retrieve(
        criteria: SCMSourceCriteria?,
        observer: SCMHeadObserver,
        event: SCMHeadEvent<*>?,
        listener: TaskListener
    ) {
        val eventHeads = event?.heads(this)?.keys ?: emptySet()
        val context = SpaceSCMSourceContext(criteria!!, observer, eventHeads).withTraits(traits.orEmpty())
        context.newRequest(this, listener).use { request ->
            for (discoveryHandler in request.discoveryHandlers) {
                // Process the stream of heads as they come in and terminate the
                // stream if the request has finished observing (returns true)
                discoveryHandler.discoverHeads().anyMatch { scmHead: SCMHead ->
                    val scmRevision = discoveryHandler.toRevision(scmHead)
                    try {
                        return@anyMatch request.process(
                            scmHead,
                            scmRevision,
                            { head: SCMHead, revision: SCMRevision? ->
                                this.newProbe(head, revision)
                            },
                            { head: SCMHead?, revision: SCMRevision?, isMatch: Boolean ->
                                listener.logger.printf(
                                    "head: %s, revision: %s, isMatch: %s%n",
                                    head, revision, isMatch
                                )
                            })
                    } catch (e: IOException) {
                        listener.error(
                            "Error processing request for head: " + scmHead + ", revision: " +
                                    scmRevision + ", error: " + e.message
                        )

                        return@anyMatch true
                    } catch (e: InterruptedException) {
                        listener.error(
                            "Error processing request for head: " + scmHead + ", revision: " +
                                    scmRevision + ", error: " + e.message
                        )

                        return@anyMatch true
                    }
                }
            }
        }
    }

    override fun build(head: SCMHead, revision: SCMRevision?): SCM {
        LOGGER.info("SpaceSCMSource.build")
        val descriptor = descriptor as DescriptorImpl
        val spaceConnection = descriptor.spacePluginConfiguration.getConnectionById(this.spaceConnection)
            ?: error("No Space connection found by specified id")

        try {
            val remoteConfig = spaceConnection.getUserRemoteConfig(projectKey, repositoryName)
            val repoBrowser = SpaceRepositoryBrowser(spaceConnection, projectKey, repositoryName)

            val builder = GitSCMBuilder(head, revision, remoteConfig.url!!, remoteConfig.credentialsId)
            builder.withBrowser(repoBrowser)
            return builder.build()
        } catch (ex: Throwable) {
            LOGGER.log(Level.SEVERE, ex.message)
            return GitSCMBuilder(head, revision, "", "").build()
        }
    }

    @Extension
    class DescriptorImpl : SCMSourceDescriptor() {
        @Inject
        internal lateinit var spacePluginConfiguration: SpacePluginConfiguration

        @Inject
        private lateinit var scmParamsProvider: SpaceSCMParamsProvider

        override fun getDisplayName() = "JetBrains Space"

        @POST
        fun doFillSpaceConnectionIdItems(): ListBoxModel {
            return scmParamsProvider.doFillSpaceConnectionIdItems()
        }

        @POST
        fun doFillProjectKeyItems(@QueryParameter spaceConnectionId: String): HttpResponse {
            return scmParamsProvider.doFillProjectKeyItems(spaceConnectionId)
        }

        @POST
        fun doFillRepositoryNameItems(@QueryParameter spaceConnectionId: String, @QueryParameter projectKey: String): HttpResponse {
            return scmParamsProvider.doFillRepositoryNameItems(spaceConnectionId, projectKey)
        }
    }
}

private val LOGGER = Logger.getLogger(SpaceSCMSource::class.java.name)
