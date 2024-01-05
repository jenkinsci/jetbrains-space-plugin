package org.jetbrains.space.jenkins.config;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.Util;
import hudson.util.Secret;
import org.jetbrains.annotations.NotNull;

@NameWith(SpaceApiCredentials.NameProvider.class)
public interface SpaceApiCredentials extends StandardCredentials {
    String getClientId();
    Secret getClientSecret();

    class NameProvider extends CredentialsNameProvider<SpaceApiCredentials> {

        @NotNull
        @Override
        public String getName(@NotNull SpaceApiCredentials c) {
            String description = Util.fixEmptyAndTrim(c.getDescription());
            return (description != null) ? description : ("JetBrains Space API / " + c.getClientId());
        }
    }
}
