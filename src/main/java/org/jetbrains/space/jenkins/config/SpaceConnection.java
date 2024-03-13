package org.jetbrains.space.jenkins.config;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
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
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Represents a connection to JetBrains Space instance, with its base url and both API and SSH credentials.
 */
public class SpaceConnection extends AbstractDescribableImpl<SpaceConnection> {

    private final String id;
    private final String name;
    private final String baseUrl;
    private final String apiCredentialId;
    private final String sshCredentialId;

    @DataBoundConstructor
    public SpaceConnection(@Nullable String id, String name, String baseUrl, String apiCredentialId, String sshCredentialId) {
        this.id = StringUtils.isBlank(id) ? UUID.randomUUID().toString() : id;
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

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends Descriptor<SpaceConnection> {

        @POST
        public ListBoxModel doFillApiCredentialIdItems(@AncestorInPath Item item, @QueryParameter String baseUrl, @QueryParameter String apiCredentialId) {
            if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(apiCredentialId);
            }
            if (item != null && !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return new StandardListBoxModel().includeCurrentValue(apiCredentialId);
            }

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
        public ListBoxModel doFillSshCredentialIdItems(@AncestorInPath Item item, @QueryParameter String baseUrl, @QueryParameter String sshCredentialId) {
            if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(sshCredentialId);
            }
            if (item != null && !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return new StandardListBoxModel().includeCurrentValue(sshCredentialId);
            }

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
        public FormValidation doTestApiConnection(@QueryParameter String baseUrl, @QueryParameter String apiCredentialId) {
            try {
                UtilsKt.testSpaceApiConnection(baseUrl, apiCredentialId);
            } catch (Exception ex) {
                return FormValidation.error(ex, "Couldn't connect to JetBrains Space API");
            }

            return FormValidation.ok("Connection established successfully");
        }
    }
}
