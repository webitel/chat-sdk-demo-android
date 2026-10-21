package com.webitel.chat.sdk.demo_android.repo

import android.app.Application
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.webitel.chat.sdk.ContactIdentity
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date


/**
 * DEMO ONLY: signs a JWT on the device with a private key from `assets/key1.pem`.
 *
 * In a real app the token must be issued by your backend —
 * never ship a private key inside the app.
 */
class JwtGenerator(private val application: Application) {

    private val privateKey: RSAPrivateKey by lazy {
        loadPrivateKey(loadPem(PRIVATE_KEY_FILE))
    }


    fun generate(user: ContactIdentity, ttlSeconds: Long = TTL_SECONDS): String {
        val now = Date()
        val exp = Date(now.time + ttlSeconds * 1000)

        return JWT.create()
            .withKeyId(KEY_ID)
            .withIssuer(user.iss)
            .withSubject(user.sub)
            .withClaim("name", user.name)
            .withIssuedAt(now)
            .withExpiresAt(exp)
            .sign(Algorithm.RSA256(null, privateKey))
    }


    private fun loadPrivateKey(pem: String): RSAPrivateKey {
        val normalized = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized))
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }


    private fun loadPem(fileName: String): String =
        application.assets.open(fileName)
            .bufferedReader()
            .use { it.readText() }


    companion object {
        private const val KEY_ID = "JRwokus1PukJfhhuGkXz1g"
        private const val PRIVATE_KEY_FILE = "key1.pem"

        /** The SDK asks for a new token (AuthMethod.Token provider) when the old one expires. */
        private const val TTL_SECONDS = 60L * 60
    }
}
