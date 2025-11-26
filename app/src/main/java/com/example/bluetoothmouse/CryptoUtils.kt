package com.example.bluetoothmouse

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CryptoUtils {
    private const val KEY_STORE_NAME = "MoonlightKS"
    private const val KEY_ALIAS = "client_cert"
    private const val PREF_KEY = "client_private_key"
    private const val PREF_CERT = "client_certificate"

    fun ensureKeysExist(context: Context) {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_KEY) || !prefs.contains(PREF_CERT)) {
            generateAndSaveKeys(context)
        }
    }

    private fun generateAndSaveKeys(context: Context) {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()

            val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24)
            val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 20)
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            
            val certBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=NVIDIA GameStream Client"),
                serial,
                notBefore,
                notAfter,
                X500Name("CN=NVIDIA GameStream Client"),
                keyPair.public
            )
            
            val contentSigner = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
            val certHolder = certBuilder.build(contentSigner)
            val cert = JcaX509CertificateConverter().getCertificate(certHolder)

            val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
            val keyString = android.util.Base64.encodeToString(keyPair.private.encoded, android.util.Base64.DEFAULT)
            val certString = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT)

            prefs.edit()
                .putString(PREF_KEY, keyString)
                .putString(PREF_CERT, certString)
                .apply()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getClientSSLContext(context: Context): SSLContext? {
        try {
            val (privateKey, cert) = loadKeys(context) ?: return null

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry(KEY_ALIAS, privateKey, null, arrayOf(cert))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, trustAllCerts, SecureRandom())
            
            return sslContext

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    private fun loadKeys(context: Context): Pair<PrivateKey, X509Certificate>? {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        val keyStr = prefs.getString(PREF_KEY, null) ?: return null
        val certStr = prefs.getString(PREF_CERT, null) ?: return null

        val keyBytes = android.util.Base64.decode(keyStr, android.util.Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val certBytes = android.util.Base64.decode(certStr, android.util.Base64.DEFAULT)
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        
        return Pair(privateKey, cert)
    }
    
    fun getCertificateHex(context: Context): String {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        val certStr = prefs.getString(PREF_CERT, null) ?: return ""
        val certBytes = android.util.Base64.decode(certStr, android.util.Base64.DEFAULT)
        return bytesToHex(certBytes)
    }
    
    fun getCertificateBytes(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        val certStr = prefs.getString(PREF_CERT, null) ?: return null
        return android.util.Base64.decode(certStr, android.util.Base64.DEFAULT)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
    
    fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }
    
    fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }
    
    fun signData(context: Context, data: ByteArray): ByteArray? {
        return try {
            val (privateKey, _) = loadKeys(context) ?: return null
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, result, 0, a.size)
        System.arraycopy(b, 0, result, a.size, b.size)
        return result
    }
    
    fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}