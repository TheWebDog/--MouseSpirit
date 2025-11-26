package com.example.bluetoothmouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.Executors
import kotlin.random.Random

class StreamingActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private lateinit var hostAdapter: HostDeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var refreshBtn: Button
    
    private var isDiscoveryRunning = false
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pairingManager: PairingManager? = null
    
    private val stopDiscoveryTask = Runnable {
        if (isDiscoveryRunning) {
            stopDiscovery()
            statusText.text = "扫描完成"
            progressBar.visibility = View.GONE
        }
    }

    // ... (NsdManager DiscoveryListener logic remains same) ...
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            isDiscoveryRunning = true
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                statusText.text = "正在搜索 Sunshine 主机..."
                refreshBtn.isEnabled = true
            }
            mainHandler.postDelayed(stopDiscoveryTask, 5000)
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("_nvstream")) {
                try {
                    nsdManager.resolveService(service, ResolveListener())
                } catch (e: Exception) {
                    Log.e("Streaming", "Resolve failed immediately", e)
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {}

        override fun onDiscoveryStopped(serviceType: String) {
            isDiscoveryRunning = false
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (statusText.text == "正在搜索 Sunshine 主机...") {
                    statusText.text = "搜索已停止"
                }
                refreshBtn.isEnabled = true
                if (pendingRestart) {
                    pendingRestart = false
                    startDiscovery()
                }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscoveryRunning = false
            nsdManager.stopServiceDiscovery(this)
            runOnUiThread { 
                refreshBtn.isEnabled = true 
                Toast.makeText(this@StreamingActivity, "扫描启动失败($errorCode)", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            nsdManager.stopServiceDiscovery(this)
        }
    }
    
    inner class ResolveListener : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val hostIp = serviceInfo.host.hostAddress
            val port = serviceInfo.port
            val mDnsName = serviceInfo.serviceName

            if (hostIp != null) {
                runOnUiThread {
                    hostAdapter.addHost(HostInfo(mDnsName, hostIp, port))
                }
                fetchServerInfo(hostIp)
            }
        }
    }

    // 自动后台获取
    private fun fetchServerInfo(ip: String) {
        val uniqueId = PreferenceUtils.getUniqueId(this)
        executor.execute {
            try {
                val hostString = if (ip.contains(":")) "[$ip]" else ip
                var success = tryFetchInfo(hostString, 47984, ip, uniqueId, false, null)
                if (!success) {
                    tryFetchInfo(hostString, 47989, ip, uniqueId, false, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun tryFetchInfo(hostString: String, port: Int, originalIp: String, uniqueId: String, isDebug: Boolean, sb: StringBuilder?): Boolean {
        try {
            val protocol = if (port == 47984) "https" else "http"
            val url = "$protocol://$hostString:$port/serverinfo"
            val client = getClient(port)
            val request = Request.Builder()
                .url(url)
                .header("X-Nv-ClientID", uniqueId)
                .header("Connection", "close")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body?.string()
                    if (xml != null) {
                        val info = parseServerInfo(xml, isDebug, sb)
                        if (info != null && info.hostname.isNotBlank()) {
                            runOnUiThread {
                                hostAdapter.updateHostInfo(originalIp, info.hostname, info.paired)
                            }
                            return true 
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return false
    }

    data class ServerInfo(val hostname: String, val paired: Boolean)

    private fun parseServerInfo(xml: String, isDebug: Boolean, sb: StringBuilder?): ServerInfo? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var hostname = ""
            var paired = false
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "hostname" -> {
                            try { hostname = parser.nextText() } catch (e: Exception) { hostname = "" }
                        }
                        "pairstatus" -> {
                            try { paired = parser.nextText() == "1" } catch (e: Exception) { paired = false }
                        }
                    }
                }
                eventType = parser.next()
            }
            return ServerInfo(hostname, paired)
        } catch (e: Exception) { }
        return null
    }

    private fun getClient(port: Int): OkHttpClient {
        return if (port == 47984) {
            val sslContext = CryptoUtils.getClientSSLContext(this)
            if (sslContext != null) {
                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, object : javax.net.ssl.X509TrustManager {
                         override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                         override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                         override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    })
                    .hostnameVerifier { _, _ -> true }
                    .build()
            } else {
                NetworkUtils.getUnsafeOkHttpClient()
            }
        } else {
            NetworkUtils.getUnsafeOkHttpClient()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)
        
        executor.execute { CryptoUtils.ensureKeysExist(this) }
        pairingManager = PairingManager(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        progressBar = findViewById(R.id.progress_scanning)
        statusText = findViewById(R.id.tv_status)
        refreshBtn = findViewById(R.id.btn_refresh)
        val recycler = findViewById<RecyclerView>(R.id.recycler_hosts)

        hostAdapter = HostDeviceAdapter { host ->
            if (host.isPaired) {
                 Toast.makeText(this, "已配对: ${host.name}", Toast.LENGTH_SHORT).show()
            } else {
                 prepareAndPair(host)
            }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hostAdapter

        refreshBtn.setOnClickListener { refreshDiscovery() }
        startDiscovery()
    }
    
    private fun prepareAndPair(host: HostInfo) {
        // 1. 生成 PIN
        val pin = String.format("%04d", Random.nextInt(10000))
        
        // 2. 显示等待对话框
        val pd = AlertDialog.Builder(this)
            .setTitle("请求连接中...")
            .setMessage("正在联系电脑 Sunshine...")
            .setCancelable(false)
            .create()
        pd.show()
        
        // 3. 执行 Step 1: 触发电脑弹窗
        // 注意：强制使用 47989 (HTTP)
        pairingManager?.initiatePairing(host.address, 47989, pin, object : PairingManager.PairingStepCallback {
            override fun onLog(msg: String) {
                // Log.d("Pairing", msg)
            }

            override fun onStep1Success(serverSaltHex: String) {
                // Step 1 成功，电脑现在应该弹出了输入框
                runOnUiThread {
                    pd.dismiss()
                    // 4. 提示用户输入
                    showPinInputPrompt(host, pin, serverSaltHex)
                }
            }

            override fun onPairingSuccess() {
                // Not used in step 1
            }

            override fun onError(error: String) {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this@StreamingActivity, "请求失败: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
    
    private fun showPinInputPrompt(host: HostInfo, pin: String, serverSaltHex: String) {
        AlertDialog.Builder(this)
            .setTitle("配对 PIN 码")
            .setMessage("请在电脑上输入：\n\n$pin\n\n输入完毕后，点击下方按钮。")
            .setPositiveButton("电脑已输入完成") { _, _ ->
                executePairingCompletion(host, pin, serverSaltHex)
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }
    
    private fun executePairingCompletion(host: HostInfo, pin: String, serverSaltHex: String) {
        val pd = AlertDialog.Builder(this)
            .setTitle("验证中...")
            .setMessage("正在完成配对...")
            .setCancelable(false)
            .create()
        pd.show()
        
        val msgView = pd.findViewById<TextView>(android.R.id.message)
        
        pairingManager?.completePairing(host.address, 47989, pin, serverSaltHex, object : PairingManager.PairingStepCallback {
            override fun onLog(msg: String) {
                runOnUiThread { msgView?.text = msg }
            }

            override fun onStep1Success(salt: String) {} // Not used here

            override fun onPairingSuccess() {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this@StreamingActivity, "配对成功！", Toast.LENGTH_SHORT).show()
                    fetchServerInfo(host.address)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    pd.dismiss()
                    AlertDialog.Builder(this@StreamingActivity)
                        .setTitle("配对失败")
                        .setMessage(error)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        })
    }

    private var pendingRestart = false
    private fun refreshDiscovery() {
        mainHandler.removeCallbacks(stopDiscoveryTask)
        hostAdapter.clear()
        if (isDiscoveryRunning) {
            pendingRestart = true
            stopDiscovery()
        } else {
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        try {
            nsdManager.discoverServices("_nvstream._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isDiscoveryRunning = false
            runOnUiThread { refreshBtn.isEnabled = true }
        }
    }
    
    private fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(stopDiscoveryTask)
        if (isDiscoveryRunning) stopDiscovery()
        executor.shutdown()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}