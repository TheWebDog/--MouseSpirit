package com.example.bluetoothmouse

import android.content.Context
import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

class PairingManager(private val context: Context) {

    interface PairingStepCallback {
        fun onStep1Success(serverSaltHex: String)
        fun onPairingSuccess()
        fun onError(error: String)
        fun onLog(msg: String)
    }

    fun initiatePairing(host: String, port: Int, pin: String, callback: PairingStepCallback) {
        Thread {
            try {
                Log.e("[Mouse]Pairing", ">>> START PAIRING (Official Logic) with $host <<<")
                
                val uniqueId = PreferenceUtils.getUniqueId(context)
                val clientCertHex = CryptoUtils.getCertificatePemHex(context)
                
                if (clientCertHex.isEmpty()) {
                    callback.onError("错误: 客户端证书未能生成")
                    return@Thread
                }
                
                val clientSalt = CryptoUtils.randomBytes(16)
                val clientSaltHex = CryptoUtils.bytesToHex(clientSalt)
                val deviceNameEncoded = URLEncoder.encode("Android Mouse", "UTF-8").replace("+", "%20")
                
                Log.e("[Mouse]Pairing", "Using ID: $uniqueId")

                // Step 1: phrase=getservercert (HTTP 47989)
                val baseParams = "uniqueid=$uniqueId&devicename=$deviceNameEncoded&updateTimestamp=0&localversion=0"
                val step1Params = "$baseParams&phrase=getservercert&salt=$clientSaltHex&clientcert=$clientCertHex"
                
                val pairPort = 47989
                val url1 = "http://$host:$pairPort/pair?$step1Params"
                
                Log.e("[Mouse]Pairing", "Step 1 Requesting Server Cert from $url1")
                
                // 设置 120 秒超时，等待用户输入 PIN
                val resp1 = executeRequest(url1, pairPort, uniqueId, 120)
                
                Log.e("[Mouse]Pairing", "Step 1 Response Code: ${resp1.statusCode}")
                
                if (resp1.statusCode != 200) {
                    Log.e("[Mouse]Pairing", "Step 1 Error Body: ${resp1.rawXml}")
                    callback.onError("连接被拒绝 (${resp1.statusCode}): ${resp1.statusMessage}")
                    return@Thread
                }
                
                val paired = resp1.xmlMap["paired"]
                if (paired != "1") {
                    callback.onError("配对拒绝: paired=$paired (请确认 PC 端是否允许配对)")
                    return@Thread
                }
                
                Log.e("[Mouse]Pairing", "Step 1 Success. Client Salt: $clientSaltHex")
                callback.onStep1Success(clientSaltHex)

            } catch (e: Exception) {
                Log.e("[Mouse]Pairing", "CRASH", e)
                callback.onError("程序异常: ${e.message}")
            }
        }.start()
    }

    fun completePairing(host: String, port: Int, pin: String, clientSaltHex: String, callback: PairingStepCallback) {
        Thread {
            try {
                val uniqueId = PreferenceUtils.getUniqueId(context)
                val deviceNameEncoded = URLEncoder.encode("Android Mouse", "UTF-8").replace("+", "%20")
                Log.e("[Mouse]Pairing", "Completing Pairing...")

                val pairPort = 47989
                val salt = CryptoUtils.hexToBytes(clientSaltHex)
                
                // PIN Hash (SHA256)
                val pinBytes = pin.toByteArray(Charsets.UTF_8)
                val saltedPin = CryptoUtils.concat(salt, pinBytes)
                val keyHash = CryptoUtils.sha256(saltedPin)
                
                // AES Key (First 16 bytes of SHA256 hash)
                val aesKey = ByteArray(16)
                System.arraycopy(keyHash, 0, aesKey, 0, 16)
                
                // Step 2: Client Challenge
                val randomChallenge = CryptoUtils.randomBytes(16)
                val encryptedChallenge = CryptoUtils.aesEncrypt(randomChallenge, aesKey)
                val encryptedChallengeHex = CryptoUtils.bytesToHex(encryptedChallenge)
                
                val baseParams = "uniqueid=$uniqueId&devicename=$deviceNameEncoded&updateTimestamp=0&localversion=0"
                val step2Params = "$baseParams&clientchallenge=$encryptedChallengeHex"
                
                val url2 = buildUrl(host, pairPort, step2Params)
                val resp2 = executeRequest(url2, pairPort, uniqueId, 10)
                
                if (resp2.statusCode != 200) {
                    Log.e("[Mouse]Pairing", "Step 2 Failed: ${resp2.rawXml}")
                    callback.onError("PIN 验证错误 (${resp2.statusCode})")
                    return@Thread
                }
                
                // Step 3: Server Challenge Response
                val encServerChallengeRespHex = resp2.xmlMap["challengeresponse"]
                if (encServerChallengeRespHex == null) {
                    callback.onError("协议错误: 缺少 challengeresponse")
                    return@Thread
                }
                
                val encServerChallengeResp = CryptoUtils.hexToBytes(encServerChallengeRespHex)
                val decServerChallengeResp = CryptoUtils.aesDecrypt(encServerChallengeResp, aesKey)
                
                // Decrypted format: ServerResponse(32) + ServerChallenge(16)
                if (decServerChallengeResp.size < 48) {
                     callback.onError("协议错误: 解密数据长度不足 (${decServerChallengeResp.size})")
                     return@Thread
                }
                
                val serverChallenge = ByteArray(16)
                System.arraycopy(decServerChallengeResp, 32, serverChallenge, 0, 16)
                
                // Step 3: 计算 Hash
                // hash = SHA256( ServerChallenge + ClientCertSig + ClientSecret )
                val clientSecret = CryptoUtils.randomBytes(16)
                val clientCertSig = CryptoUtils.getCertificateSignature(context)
                
                if (clientCertSig == null) {
                    callback.onError("错误: 无法获取证书签名")
                    return@Thread
                }
                
                // 拼接: ServerChallenge + ClientCertSig + ClientSecret
                val dataToHash = CryptoUtils.concat(CryptoUtils.concat(serverChallenge, clientCertSig), clientSecret)
                val challengeRespHash = CryptoUtils.sha256(dataToHash)
                
                // Encrypt Hash
                val challengeRespEncrypted = CryptoUtils.aesEncrypt(challengeRespHash, aesKey)
                val challengeRespEncryptedHex = CryptoUtils.bytesToHex(challengeRespEncrypted)
                
                val step3Params = "$baseParams&serverchallengeresp=$challengeRespEncryptedHex"
                val url3 = buildUrl(host, pairPort, step3Params)
                val resp3 = executeRequest(url3, pairPort, uniqueId, 10)
                
                // 这里如果返回 200，说明 Server 接受了我们的 Hash (证明我们是合法的客户端)
                if (resp3.statusCode != 200 || resp3.xmlMap["paired"] != "1") {
                    Log.e("[Mouse]Pairing", "Step 3 Failed: ${resp3.rawXml}")
                    callback.onError("密钥交换错误 (Step 3)")
                    return@Thread
                }
                
                // Step 4: Client Pairing Secret
                // signature = Sign(ClientSecret) using Client Private Key
                val clientSecretSig = CryptoUtils.signData(context, clientSecret)
                if (clientSecretSig == null) {
                    callback.onError("签名失败")
                    return@Thread
                }
                
                // data = ClientSecret + Signature
                val clientPairingSecret = CryptoUtils.concat(clientSecret, clientSecretSig)
                val clientPairingSecretHex = CryptoUtils.bytesToHex(clientPairingSecret)
                
                val step4Params = "$baseParams&clientpairingsecret=$clientPairingSecretHex"
                val url4 = buildUrl(host, pairPort, step4Params)
                val resp4 = executeRequest(url4, pairPort, uniqueId, 10)
                
                if (resp4.statusCode == 200 && resp4.xmlMap["paired"] == "1") {
                    Log.e("[Mouse]Pairing", "SUCCESS!")
                    callback.onPairingSuccess()
                } else {
                    Log.e("[Mouse]Pairing", "Step 4 Failed: ${resp4.rawXml}")
                    callback.onError("最终确认失败: ${resp4.statusMessage}")
                }

            } catch (e: Exception) {
                Log.e("[Mouse]Pairing", "Complete Error", e)
                callback.onError("验证过程异常: ${e.message}")
            }
        }.start()
    }

    private fun buildUrl(host: String, port: Int, query: String): String {
        val protocol = "http"
        val hostStr = if (host.contains(":")) "[$host]" else host
        return "$protocol://$hostStr:$port/pair?$query"
    }

    private data class Response(val statusCode: Int, val statusMessage: String, val xmlMap: Map<String, String>, val rawXml: String)

    private fun executeRequest(url: String, port: Int, uniqueId: String, timeoutSeconds: Long): Response {
        val clientBuilder = NetworkUtils.getUnsafeOkHttpClient().newBuilder()
        
        // 关键：设置足够长的超时，等待用户输入 PIN
        clientBuilder.readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        clientBuilder.writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        clientBuilder.connectTimeout(10, TimeUnit.SECONDS)
        
        val client = clientBuilder.build()
        
        val request = Request.Builder()
            .url(url)
            .header("X-Nv-ClientID", uniqueId)
            .header("Connection", "close")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val xml = response.body?.string() ?: ""
                val map = parseXml(xml)
                var code = response.code
                var msg = response.message
                if (map.containsKey("status_code")) {
                    code = map["status_code"]?.toIntOrNull() ?: code
                }
                if (map.containsKey("status_message")) {
                    msg = map["status_message"] ?: msg
                }
                return Response(code, msg, map, xml)
            }
        } catch (e: Exception) {
             Log.e("[Mouse]Pairing", "Request failed: $url", e)
             return Response(0, "Request Failed: ${e.message}", emptyMap(), "")
        }
    }

    private fun parseXml(xml: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (xml.isBlank()) return map
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            var currentTag = ""
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        for (i in 0 until parser.attributeCount) {
                             map[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag.isNotEmpty()) {
                            val text = parser.text
                            if (text.isNotBlank()) {
                                map[currentTag] = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> { currentTag = "" }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return map
    }
}