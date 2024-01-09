package org.jetbrains.space.jenkins.config;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

// Written in Java because of protected BaseStandardCredentialsDescriptor supertype exposed by public DescriptorImpl class
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
    public String getClientId() {
        return clientId;
    }

    @Override
    public Secret getClientSecret() {
        return clientSecret;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "JetBrains Space API credentials";
        }
    }
}
