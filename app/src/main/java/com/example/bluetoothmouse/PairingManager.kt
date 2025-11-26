package com.example.bluetoothmouse

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

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
                val uniqueId = PreferenceUtils.getUniqueId(context)
                val clientCertHex = CryptoUtils.getCertificateHex(context)
                val clientSalt = CryptoUtils.randomBytes(16)
                val clientSaltHex = CryptoUtils.bytesToHex(clientSalt)
                
                callback.onLog("Unique ID: $uniqueId")
                callback.onLog("正在连接 $host:$port ...")
                
                // 补全参数：增加 updateTimestamp 和 localversion
                // GET /pair?uniqueid=...&devicename=...&updateTimestamp=...&localversion=...&salt=...&clientcert=...
                val query = "salt=$clientSaltHex&clientcert=$clientCertHex&updateTimestamp=0&localversion=0"
                val url1 = buildUrl(host, port, query)
                
                callback.onLog("请求URL: $url1")
                
                val resp1 = executeRequest(url1, uniqueId)
                
                callback.onLog("Step 1 响应码: ${resp1.statusCode}")
                callback.onLog("Step 1 消息: ${resp1.statusMessage}")
                
                if (resp1.statusCode != 200) {
                    callback.onError("连接被拒绝: ${resp1.statusMessage} (${resp1.statusCode})")
                    return@Thread
                }
                
                val serverSaltHex = resp1.xmlMap["salt"] ?: ""
                if (serverSaltHex.isEmpty()) {
                    callback.onError("服务器未返回 Salt")
                    return@Thread
                }
                
                callback.onLog("获取到 Server Salt: $serverSaltHex")
                callback.onStep1Success(serverSaltHex)

            } catch (e: Exception) {
                Log.e("Pairing", "Init Error", e)
                callback.onError("初始化失败: ${e.message}")
            }
        }.start()
    }

    // 第二阶段逻辑保持不变，主要是 executeRequest 和 buildUrl 需要一致
    fun completePairing(host: String, port: Int, pin: String, serverSaltHex: String, callback: PairingStepCallback) {
        Thread {
            try {
                val uniqueId = PreferenceUtils.getUniqueId(context)
                callback.onLog("开始计算密钥...")

                val serverSalt = CryptoUtils.hexToBytes(serverSaltHex)
                val pinBytes = pin.toByteArray(Charsets.UTF_8)
                val combined = CryptoUtils.concat(serverSalt, pinBytes)
                val keyFull = CryptoUtils.sha256(combined)
                val aesKey = ByteArray(16)
                System.arraycopy(keyFull, 0, aesKey, 0, 16)
                
                callback.onLog("Step 2: 发送 Challenge...")
                val clientSecret = CryptoUtils.randomBytes(16)
                val clientSecretEnc = CryptoUtils.aesEncrypt(clientSecret, aesKey)
                val clientSecretHex = CryptoUtils.bytesToHex(clientSecretEnc)
                
                val url2 = buildUrl(host, port, "clientchallenge=$clientSecretHex")
                val resp2 = executeRequest(url2, uniqueId)
                
                if (resp2.statusCode != 200) {
                    callback.onError("PIN 验证失败: ${resp2.statusCode}")
                    return@Thread
                }
                
                val serverChallengeRespHex = resp2.xmlMap["serverchallengeresp"]
                if (serverChallengeRespHex == null) {
                    callback.onError("服务器响应异常 (Missing Challenge Resp)")
                    return@Thread
                }
                
                callback.onLog("Step 3: 交换配对密钥...")
                val clientPairingSecret = CryptoUtils.randomBytes(16)
                val clientPairingSecretEnc = CryptoUtils.aesEncrypt(clientPairingSecret, aesKey)
                val clientPairingSecretHex = CryptoUtils.bytesToHex(clientPairingSecretEnc)
                
                val url3 = buildUrl(host, port, "clientpairingsecret=$clientPairingSecretHex")
                val resp3 = executeRequest(url3, uniqueId)
                
                if (resp3.statusCode != 200) {
                    callback.onError("密钥交换失败")
                    return@Thread
                }
                
                val serverPairingSecretHex = resp3.xmlMap["serverpairingsecret"]
                
                callback.onLog("Step 4: 签署证书...")
                val serverSecretBytes = CryptoUtils.hexToBytes(serverPairingSecretHex ?: "")
                val myCertBytes = CryptoUtils.getCertificateBytes(context) ?: ByteArray(0)
                val dataToSign = CryptoUtils.concat(myCertBytes, serverSecretBytes)
                
                val signature = CryptoUtils.signData(context, dataToSign)
                if (signature == null) {
                    callback.onError("签名失败")
                    return@Thread
                }
                
                val signatureHex = CryptoUtils.bytesToHex(signature)
                val url4 = buildUrl(host, port, "clientpairingsecret=$clientPairingSecretHex&clientsignature=$signatureHex")
                
                val resp4 = executeRequest(url4, uniqueId)
                
                if (resp4.statusCode == 200 && resp4.xmlMap["paired"] == "1") {
                    callback.onLog("配对成功!")
                    callback.onPairingSuccess()
                } else {
                    callback.onError("最终验证失败: ${resp4.statusMessage}")
                }

            } catch (e: Exception) {
                Log.e("Pairing", "Complete Error", e)
                callback.onError("验证过程异常: ${e.message}")
            }
        }.start()
    }

    private fun buildUrl(host: String, port: Int, query: String): String {
        val protocol = if (port == 47984) "https" else "http"
        val hostStr = if (host.contains(":")) "[$host]" else host
        return "$protocol://$hostStr:$port/pair?$query"
    }

    private data class Response(val statusCode: Int, val statusMessage: String, val xmlMap: Map<String, String>)

    private fun executeRequest(url: String, uniqueId: String): Response {
        val client = NetworkUtils.getUnsafeOkHttpClient()
        
        // Append common parameters if not present in url
        var fullUrl = url
        if (!fullUrl.contains("uniqueid=")) {
            fullUrl += "&uniqueid=$uniqueId"
        }
        if (!fullUrl.contains("devicename=")) {
            fullUrl += "&devicename=AndroidMouse"
        }
        if (!fullUrl.contains("updateTimestamp=")) {
             fullUrl += "&updateTimestamp=0"
        }
        if (!fullUrl.contains("localversion=")) {
             fullUrl += "&localversion=0"
        }
        
        val request = Request.Builder()
            .url(fullUrl)
            .header("X-Nv-ClientID", uniqueId)
            .header("Connection", "close")
            .build()

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
                    for (i in 0 until parser.attributeCount) {
                        map[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                    }
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