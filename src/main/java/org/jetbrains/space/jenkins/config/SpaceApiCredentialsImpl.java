package org.jetbrains.space.jenkins.config;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Implementation of credentials type used for accessing JetBrains Space API (client id and secret).
 * Written in Java because of protected BaseStandardCredentialsDescriptor supertype exposed by public DescriptorImpl class
 * and because Jenkins for some reasons has problems with extensions declared in Kotlin code.
 */
public class SpaceApiCredentialsImpl extends BaseStandardCredentials implements SpaceApiCredentials {
    private final String clientId;
    private final Secret clientSecret;

    @DataBoundConstructor
    public SpaceApiCredentialsImpl(CredentialsScope scope, String id, String description, String clientId, Secret clientSecret) {
        super(scope, id, description);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public @NotNull String getClientId() {
        return clientId;
    }

    @Override
    public @NotNull Secret getClientSecret() {
        return clientSecret;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        public @NotNull String getDisplayName() {
            return "JetBrains Space API credentials";
        }
    }
}
