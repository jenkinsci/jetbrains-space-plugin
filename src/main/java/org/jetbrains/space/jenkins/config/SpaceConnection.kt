package org.jetbrains.space.jenkins.config

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import hudson.Extension
import hudson.model.AbstractDescribableImpl
import hudson.model.Descriptor
import hudson.model.Item
import hudson.security.ACL
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.model.Jenkins
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang.StringUtils
import org.jetbrains.space.jenkins.SpaceApiClient
import org.jetbrains.space.jenkins.spaceApiClient
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.applications
import java.util.*

class SpaceConnection @DataBoundConstructor constructor(
    id: String?,
    val name: String,
    val baseUrl: String,
    val apiCredentialId: String,
    val sshCredentialId: String
) : AbstractDescribableImpl<SpaceConnection?>() {

    val id: String = if (StringUtils.isBlank(id)) UUID.randomUUID().toString() else id!!

    fun getApiClient(): SpaceClient {
        val creds = CredentialsMatchers
            .firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                    SpaceApiCredentials::class.java,
                    Jenkins.get(),
                    ACL.SYSTEM2,
                    URIRequirementBuilder.fromUri(baseUrl).build()
                ),
                CredentialsMatchers.withId(apiCredentialId)
            )
            ?: throw IllegalStateException("No credentials found for credentialsId: $apiCredentialId")

        val appInstance = SpaceAppInstance(creds.clientId, creds.clientSecret.plainText, baseUrl)
        return SpaceClient(appInstance, SpaceAuth.ClientCredentials())
    }

    @Extension
    class DescriptorImpl : Descriptor<SpaceConnection>() {

        @POST
        fun doFillApiCredentialIdItems(@QueryParameter baseUrl: String): ListBoxModel {
            Jenkins.get().checkPermission(Item.CONFIGURE)
            return StandardListBoxModel()
                .includeMatchingAs(
                    ACL.SYSTEM2,
                    Jenkins.get(),
                    SpaceApiCredentials::class.java,
                    URIRequirementBuilder.fromUri(baseUrl).build(),
                    CredentialsMatchers.always()
                )
        }

        @POST
        fun doFillSshCredentialIdItems(@QueryParameter baseUrl: String): ListBoxModel {
            Jenkins.get().checkPermission(Item.CONFIGURE)
            return StandardListBoxModel()
                .includeMatchingAs(
                    ACL.SYSTEM2,
                    Jenkins.get(),
                    BasicSSHUserPrivateKey::class.java,
                    URIRequirementBuilder.fromUri(baseUrl).build(),
                    CredentialsMatchers.always()
                )
        }

        @POST
        fun doTestApiConnection(
            @QueryParameter baseUrl: String,
            @QueryParameter apiCredentialId: String
        ): FormValidation {
            Jenkins.get().checkPermission(Item.CONFIGURE)
            try {
                runBlocking {
                    spaceApiClient(baseUrl, apiCredentialId).applications.reportApplicationAsHealthy()
                }
            } catch (ex: Throwable) {
                return FormValidation.error(ex, "Couldn't connect to JetBrains Space API")
            }

            return FormValidation.ok()
        }
    }
}
