package org.jetbrains.space.jenkins.config

import com.cloudbees.plugins.credentials.CredentialsNameProvider
import com.cloudbees.plugins.credentials.NameWith
import com.cloudbees.plugins.credentials.common.StandardCredentials
import hudson.util.Secret

/*
 * Type of credentials used for accessing JetBrains Space API (client id and secret)
 */
@NameWith(SpaceApiCredentials.NameProvider::class)
interface SpaceApiCredentials : StandardCredentials {
    val clientId: String
    val clientSecret: Secret

    class NameProvider : CredentialsNameProvider<SpaceApiCredentials>() {
        override fun getName(c: SpaceApiCredentials): String {
            return c.description.trim().takeUnless { it.isBlank() } ?: ("JetBrains Space API / " + c.clientId)
        }
    }
}
