package org.jetbrains.space.jenkins.config

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.*


/**
 * Generates SSH key pairs for authenticating Jenkins SCM with SpaceCode git repositories.
 * Private key is stored as SSH credentials in Jenkins with the id corresponding to the project-level SpaceCode application client id.
 * Public key is uploaded to SpaceCode and attached to the same project-level SpaceCode application.
 */
object SSHKeyGenerator {
    fun generateKeyPair(): KeyPair {
        val keyGen: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(4096)
        return keyGen.generateKeyPair()
    }

    fun getPrivateKeyString(privateKey: PrivateKey): String {
        return "-----BEGIN PRIVATE KEY-----\n" +
                "${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey.encoded)}\n" +
                "-----END PRIVATE KEY-----"
    }

    fun getPublicKeyString(publicKey: PublicKey): String {
        val sshRsaPublicKey = encode(publicKey as RSAPublicKey)
        return "ssh-rsa " + Base64.getEncoder().encodeToString(sshRsaPublicKey)
    }

    private fun encode(key: RSAPublicKey) = ByteArrayOutputStream().use {
        DataOutputStream(it).use { stream ->
            val name = "ssh-rsa".toByteArray()
            stream.writeInt(name.size)
            stream.write(name)
            key.publicExponent.toByteArray().let {
                stream.writeInt(it.size)
                stream.write(it)
            }
            key.modulus.toByteArray().let {
                stream.writeInt(it.size)
                stream.write(it)
            }
        }
        it.toByteArray()
    }

    private fun write(str: ByteArray, os: OutputStream) {
        var shift = 24
        while (shift >= 0) {
            os.write((str.size ushr shift) and 0xFF)
            shift -= 8
        }
        os.write(str)
    }
}