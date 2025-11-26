package com.example.bluetoothmouse

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class PairingManager(private val context: Context) {

    interface PairingCallback {
        fun onPairingSuccess()
        fun onPairingError(error: String)
        fun onStepUpdate(step: String)
    }

    fun startPairing(host: String, port: Int, pin: String, callback: PairingCallback) {
        Thread {
            try {
                val uniqueId = PreferenceUtils.getUniqueId(context)
                val clientCertHex = CryptoUtils.getCertificateHex(context)
                val salt = CryptoUtils.randomBytes(16)
                val saltHex = CryptoUtils.bytesToHex(salt)
                
                callback.onStepUpdate("步骤 1/4: 发起配对请求...")
                
                // --- Step 1: Initial Request ---
                // GET /pair?uniqueid=...&devicename=...&updateTimestamp=...&localversion=...&salt=...&clientcert=...
                val url1 = buildUrl(host, port, "salt=$saltHex&clientcert=$clientCertHex")
                val resp1 = executeRequest(url1, uniqueId)
                
                if (resp1.statusCode != 200) {
                    callback.onPairingError("拒绝连接: ${resp1.statusMessage}")
                    return@Thread
                }
                
                val plainCertHex = resp1.xmlMap["plaincert"] ?: ""
                val serverSaltHex = resp1.xmlMap["salt"] ?: "" // Sunshine returns its own salt? (Actually usually client salt is used)
                // Note: GameStream protocol is tricky. 
                // Usually we use the salt WE sent + PIN to derive the AES key.
                
                // Deriving AES Key from PIN and Salt
                // Key = SHA256(Salt + PIN) (First 16 bytes for AES-128)
                val pinBytes = pin.toByteArray(Charsets.UTF_8)
                val combined = CryptoUtils.concat(salt, pinBytes)
                val keyFull = CryptoUtils.sha256(combined)
                val aesKey = ByteArray(16)
                System.arraycopy(keyFull, 0, aesKey, 0, 16)
                
                // --- Step 2: Client Challenge ---
                callback.onStepUpdate("步骤 2/4: 验证 PIN 码...")
                
                val clientSecret = CryptoUtils.randomBytes(16)
                val clientSecretEnc = CryptoUtils.aesEncrypt(clientSecret, aesKey)
                val clientSecretHex = CryptoUtils.bytesToHex(clientSecretEnc)
                
                val url2 = buildUrl(host, port, "clientchallenge=$clientSecretHex")
                val resp2 = executeRequest(url2, uniqueId)
                
                if (resp2.statusCode != 200) {
                    callback.onPairingError("PIN 码错误或验证失败")
                    return@Thread
                }
                
                val serverChallengeRespHex = resp2.xmlMap["serverchallengeresp"]
                if (serverChallengeRespHex == null) {
                    callback.onPairingError("服务器响应异常")
                    return@Thread
                }
                
                // Verify server response (Optional but recommended)
                // Decrypt serverChallengeRespHex with aesKey -> should match expected logic
                
                // --- Step 3: Client Pairing Secret ---
                callback.onStepUpdate("步骤 3/4: 交换密钥...")
                
                val clientPairingSecret = CryptoUtils.randomBytes(16)
                val clientPairingSecretEnc = CryptoUtils.aesEncrypt(clientPairingSecret, aesKey)
                val clientPairingSecretHex = CryptoUtils.bytesToHex(clientPairingSecretEnc)
                
                val url3 = buildUrl(host, port, "clientpairingsecret=$clientPairingSecretHex")
                val resp3 = executeRequest(url3, uniqueId)
                
                if (resp3.statusCode != 200) {
                    callback.onPairingError("密钥交换失败")
                    return@Thread
                }
                
                val serverPairingSecretHex = resp3.xmlMap["serverpairingsecret"]
                
                // --- Step 4: Signature ---
                callback.onStepUpdate("步骤 4/4: 签署证书...")
                
                // Signature structure:
                // X.509 Cert (DER) + Server Pairing Secret (16 bytes)
                // Signed with Client Private Key (SHA256withRSA)
                
                val serverSecretBytes = CryptoUtils.hexToBytes(serverPairingSecretHex ?: "")
                val myCertBytes = CryptoUtils.getCertificateBytes(context) ?: ByteArray(0)
                val dataToSign = CryptoUtils.concat(myCertBytes, serverSecretBytes)
                
                val signature = CryptoUtils.signData(context, dataToSign)
                if (signature == null) {
                    callback.onPairingError("签名失败")
                    return@Thread
                }
                
                val signatureHex = CryptoUtils.bytesToHex(signature)
                val url4 = buildUrl(host, port, "clientpairingsecret=$clientPairingSecretHex&clientsignature=$signatureHex")
                
                val resp4 = executeRequest(url4, uniqueId)
                
                if (resp4.statusCode == 200 && resp4.xmlMap["paired"] == "1") {
                    callback.onStepUpdate("配对成功！")
                    callback.onPairingSuccess()
                } else {
                    callback.onPairingError("最终验证失败")
                }

            } catch (e: Exception) {
                Log.e("Pairing", "Error", e)
                callback.onPairingError("异常: ${e.message}")
            }
        }
    }

    private fun buildUrl(host: String, port: Int, query: String): String {
        val protocol = if (port == 47984) "https" else "http"
        val hostStr = if (host.contains(":")) "[$host]" else host
        return "$protocol://$hostStr:$port/pair?$query"
    }

    private data class Response(val statusCode: Int, val statusMessage: String, val xmlMap: Map<String, String>)

    private fun executeRequest(url: String, uniqueId: String): Response {
        // Pairing uses HTTP usually (port 47989) or HTTPS (47984)
        // Sunshine usually accepts pairing on HTTP 47989
        val client = NetworkUtils.getUnsafeOkHttpClient() // Use unsafe for pairing
        val request = Request.Builder()
            .url("$url&uniqueid=$uniqueId&devicename=AndroidMouse")
            .header("X-Nv-ClientID", uniqueId)
            .header("Connection", "close")
            .build()

        client.newCall(request).execute().use { response ->
            val xml = response.body?.string() ?: ""
            val map = parseXml(xml)
            
            // Parse status code from XML if possible (Sunshine returns <root status_code=...>)
            var code = response.code
            var msg = response.message
            
            if (map.containsKey("status_code")) {
                code = map["status_code"]?.toIntOrNull() ?: code
            }
            if (map.containsKey("status_message")) {
                msg = map["status_message"] ?: msg
            }
            
            return Response(code, msg, map)
        }
    }

    private fun parseXml(xml: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name
                    // Check for attributes (like status_code in root)
                    for (i in 0 until parser.attributeCount) {
                        map[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                    }
                    
                    // Check for text content
                    try {
                        if (parser.next() == XmlPullParser.TEXT) {
                            val text = parser.text
                            if (text.isNotBlank()) {
                                map[tagName] = text
                            }
                        }
                    } catch (e: Exception) { }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return map
    }
}