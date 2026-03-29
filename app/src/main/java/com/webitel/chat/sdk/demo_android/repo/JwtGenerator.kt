package com.webitel.chat.sdk.demo_android.repo

import android.app.Application
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*


class JwtGenerator(
    private val application: Application
) {

    private val keyId: String = "JRwokus1PukJfhhuGkXz1g"
    private val privateKeyPem: String by lazy {
        loadPem(application, "key1.pem")
    }

    private val privateKey: RSAPrivateKey by lazy {
        loadPrivateKey(privateKeyPem)
    }


    fun generate(
        subject: String,
        claims: Map<String, Any> = emptyMap(),
        ttlSeconds: Long = 2000000
    ): String {

        val now = Date()
        val exp = Date(now.time + ttlSeconds * 1000)

        val algorithm = Algorithm.RSA256(null, privateKey)

        val builder = JWT.create()
            .withKeyId(keyId)
            .withSubject(subject)
            .withIssuedAt(now)
            .withExpiresAt(exp)

        claims.forEach { (k, v) ->
            when (v) {
                is String -> builder.withClaim(k, v)
                is Int -> builder.withClaim(k, v)
                is Long -> builder.withClaim(k, v)
                is Boolean -> builder.withClaim(k, v)
            }
        }

        return builder.sign(algorithm)
    }


    private fun loadPrivateKey(pem: String): RSAPrivateKey {

        val normalized = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val decoded = Base64.getDecoder().decode(normalized)

        val spec = PKCS8EncodedKeySpec(decoded)
        val factory = KeyFactory.getInstance("RSA")

        return factory.generatePrivate(spec) as RSAPrivateKey
    }


    private fun loadPem(context: Application, fileName: String): String {
        return context.assets.open(fileName)
            .bufferedReader()
            .use { it.readText() }
    }
}