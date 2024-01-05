package org.jetbrains.space.jenkins.config;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import space.jetbrains.api.runtime.SpaceAppInstance;
import space.jetbrains.api.runtime.SpaceAuth;
import space.jetbrains.api.runtime.SpaceClient;
import space.jetbrains.api.runtime.resources.Applications;

import javax.annotation.Nullable;

import java.util.UUID;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentialsInItemGroup;
import static org.apache.commons.lang.StringUtils.isBlank;

public class SpaceConnection extends AbstractDescribableImpl<SpaceConnection> {

    private final String id;
    private final String name;
    private final String baseUrl;
    private final String apiCredentialId;
    private final String sshCredentialId;

    @DataBoundConstructor
    public SpaceConnection(@Nullable String id, String name, String baseUrl, String apiCredentialId, String sshCredentialId) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiCredentialId = apiCredentialId;
        this.sshCredentialId = sshCredentialId;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiCredentialId() {
        return apiCredentialId;
    }

    public String getSshCredentialId() {
        return sshCredentialId;
    }

    public SpaceClient getApiClient() {
        SpaceApiCredentials creds = firstOrNull(
                lookupCredentialsInItemGroup(
                        SpaceApiCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(baseUrl).build()),
                CredentialsMatchers.withId(apiCredentialId)
        );
        if (creds == null) {
            throw new IllegalStateException("No credentials found for credentialsId: " + apiCredentialId);
        }

        SpaceAppInstance appInstance = new SpaceAppInstance(creds.getClientId(), creds.getClientSecret().getPlainText(), baseUrl);
        return new SpaceClient(appInstance, new SpaceAuth.ClientCredentials(), c -> Unit.INSTANCE);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<SpaceConnection> {

        @POST
        public ListBoxModel doFillApiCredentialIdItems(@QueryParameter String baseUrl) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            return new StandardListBoxModel()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            SpaceApiCredentials.class,
                            URIRequirementBuilder.fromUri(baseUrl).build(),
                            CredentialsMatchers.always()
                    );
        }

        @POST
        public ListBoxModel doFillSshCredentialIdItems(@QueryParameter String baseUrl) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            return new StandardListBoxModel()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            BasicSSHUserPrivateKey.class,
                            URIRequirementBuilder.fromUri(baseUrl).build(),
                            CredentialsMatchers.always()
                    );
        }

        @POST
        public FormValidation doTestApiConnection(
                @QueryParameter String baseUrl,
                @QueryParameter String apiCredentialId
        ) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            try {
                SpaceClient spaceClient = new SpaceConnection(null, "test", baseUrl, apiCredentialId, null).getApiClient();
                Applications applicationsApi = new Applications(spaceClient);
                BuildersKt.runBlocking(
                        EmptyCoroutineContext.INSTANCE,
                        (scope, continuation) -> applicationsApi.reportApplicationAsHealthy(continuation)
                );
            } catch (Throwable ex) {
                return FormValidation.error(ex, "Couldn't connect to JetBrains Space API");
            }

            return FormValidation.ok();
        }
    }
}
