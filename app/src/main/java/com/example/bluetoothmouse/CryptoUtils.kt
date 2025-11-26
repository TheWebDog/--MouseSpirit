package com.example.bluetoothmouse

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CryptoUtils {
    private const val KEY_STORE_NAME = "MoonlightKS"
    private const val KEY_ALIAS = "client_cert"
    private const val PREF_KEY = "client_private_key"
    private const val PREF_CERT = "client_certificate"

    // 确保密钥对和证书存在
    fun ensureKeysExist(context: Context) {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_KEY) || !prefs.contains(PREF_CERT)) {
            generateAndSaveKeys(context)
        }
    }

    private fun generateAndSaveKeys(context: Context) {
        try {
            // 1. 生成 RSA 密钥对
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()

            // 2. 生成自签名证书
            val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24)
            val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 20) // 20 years
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

            // 3. 保存 (这里简单用 Base64 存 SharedPreferences，生产环境建议用 Android KeyStore)
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

    // 创建带有客户端证书的 SSLContext
    fun getClientSSLContext(context: Context): SSLContext? {
        try {
            val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
            val keyStr = prefs.getString(PREF_KEY, null) ?: return null
            val certStr = prefs.getString(PREF_CERT, null) ?: return null

            // 还原 PrivateKey
            val keyBytes = android.util.Base64.decode(keyStr, android.util.Base64.DEFAULT)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            // 还原 Certificate
            val certBytes = android.util.Base64.decode(certStr, android.util.Base64.DEFAULT)
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

            // 创建 KeyStore
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry(KEY_ALIAS, privateKey, null, arrayOf(cert))

            // 初始化 KeyManager
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            // 初始化 TrustManager (信任所有服务端证书)
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
    
    // 获取公钥证书的 HEX 字符串 (用于配对时的验证)
    fun getCertificateHex(context: Context): String {
        val prefs = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        val certStr = prefs.getString(PREF_CERT, null) ?: return ""
        val certBytes = android.util.Base64.decode(certStr, android.util.Base64.DEFAULT)
        return bytesToHex(certBytes)
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}