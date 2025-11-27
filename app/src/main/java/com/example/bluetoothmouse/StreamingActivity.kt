package com.example.bluetoothmouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

data class NvApp(val appName: String, val appId: String)

class StreamingActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private lateinit var hostAdapter: HostDeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var refreshBtn: Button
    
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscoveryRunning = false
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pairingManager: PairingManager? = null
    
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    private val resolveTimeoutRunnable = Runnable {
        if (isResolving) {
            Log.e("[Mouse]Debug", "Resolve timed out - forcing next")
            isResolving = false 
            processNextInQueue()
        }
    }

    private val stopDiscoveryTask = Runnable {
        if (isDiscoveryRunning) {
            stopDiscovery()
            statusText.text = "扫描完成"
            progressBar.visibility = View.GONE
            refreshBtn.isEnabled = true
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            isDiscoveryRunning = true
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                statusText.text = "正在搜索 Sunshine 主机..."
                refreshBtn.isEnabled = false
            }
            mainHandler.postDelayed(stopDiscoveryTask, 5000)
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("[Mouse]Debug", "Service found: ${service.serviceName}")
            if (service.serviceType.contains("_nvstream")) {
                val newService = NsdServiceInfo()
                newService.serviceName = service.serviceName
                newService.serviceType = service.serviceType
                queueResolve(newService)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e("[Mouse]Debug", "Service lost: ${service.serviceName}")
        }

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
            Log.e("[Mouse]Debug", "Discovery failed: $errorCode")
            isDiscoveryRunning = false
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            runOnUiThread {
                refreshBtn.isEnabled = true
                statusText.text = "搜索启动失败 ($errorCode)"
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("[Mouse]Debug", "Stop Discovery failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("[Mouse]Debug", "Resolve failed: $errorCode")
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            isResolving = false
            processNextInQueue()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            Log.d("[Mouse]Debug", "Resolve Succeeded: ${serviceInfo.host}")
            val hostIp = serviceInfo.host.hostAddress
            val port = serviceInfo.port
            val mDnsName = serviceInfo.serviceName

            if (hostIp != null) {
                runOnUiThread {
                    hostAdapter.addHost(HostInfo(mDnsName, hostIp, port))
                }
                fetchServerInfo(hostIp)
            }
            isResolving = false
            processNextInQueue()
        }
    }

    private fun queueResolve(service: NsdServiceInfo) {
        resolveQueue.add(service)
        if (!isResolving) {
            processNextInQueue()
        }
    }

    private fun processNextInQueue() {
        val nextService = resolveQueue.poll()
        if (nextService != null) {
            isResolving = true
            mainHandler.postDelayed(resolveTimeoutRunnable, 3000)
            try {
                nsdManager.resolveService(nextService, resolveListener)
            } catch (e: Exception) {
                Log.e("[Mouse]Debug", "Resolution crash", e)
                mainHandler.removeCallbacks(resolveTimeoutRunnable)
                mainHandler.postDelayed({ 
                    isResolving = false
                    processNextInQueue() 
                }, 500)
            }
        } else {
            isResolving = false
        }
    }

    private fun fetchServerInfo(ip: String) {
        executor.execute {
            try {
                val hostString = if (ip.contains(":")) "[$ip]" else ip
                
                // 优先尝试 HTTPS 47984
                Log.e("[Mouse]Debug", "Fetching info for $ip. Trying HTTPS 47984...")
                var success = tryFetchInfo(hostString, 47984, ip, true)
                
                if (!success) {
                    Log.e("[Mouse]Debug", "HTTPS 47984 failed. Fallback to HTTP 47989...")
                    tryFetchInfo(hostString, 47989, ip, false)
                } else {
                    Log.e("[Mouse]Debug", "HTTPS 47984 SUCCESS.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun tryFetchInfo(hostString: String, port: Int, originalIp: String, useHttps: Boolean): Boolean {
        try {
            val protocol = if (useHttps) "https" else "http"
            val uniqueId = PreferenceUtils.getUniqueId(this)
            val url = "$protocol://$hostString:$port/serverinfo?uniqueid=$uniqueId"
            
            Log.e("[Mouse]Debug", "Req URL: $url")
            Log.e("[Mouse]Debug", "Req ID: $uniqueId")

            val client = if (useHttps) {
                val sslContext = CryptoUtils.getClientSSLContext(this)
                if (sslContext != null) {
                    OkHttpClient.Builder()
                        .sslSocketFactory(sslContext.socketFactory, object : X509TrustManager {
                             override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                             override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                             override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        })
                        .hostnameVerifier { _, _ -> true }
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                } else {
                    Log.e("[Mouse]Debug", "SSLContext is NULL!")
                    return false
                }
            } else {
                NetworkUtils.getUnsafeOkHttpClient()
            }

            val request = Request.Builder()
                .url(url)
                .header("X-Nv-ClientID", uniqueId) 
                .header("Connection", "close")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                
                // 为了调试，如果还没配对成功，打印全部 XML 看看 PairStatus 到底叫什么
                if (!useHttps && bodyStr.length < 1000) {
                     Log.e("[Mouse]Debug", "Full XML: $bodyStr")
                }

                if (response.isSuccessful) {
                    val info = parseServerInfo(bodyStr)
                    if (info != null) {
                        Log.e("[Mouse]Debug", "Parsed: Host=${info.hostname}, Paired=${info.paired}")
                        if (info.hostname.isNotBlank()) {
                            runOnUiThread {
                                if (useHttps) {
                                    hostAdapter.updateHostInfo(originalIp, info.hostname, info.paired)
                                } else {
                                    if (info.paired) {
                                        hostAdapter.updateHostInfo(originalIp, info.hostname, true)
                                    } else {
                                        hostAdapter.addHost(HostInfo(info.hostname, originalIp, port, false))
                                    }
                                }
                            }
                            return true
                        }
                    } else {
                        Log.e("[Mouse]Debug", "Parse XML Failed")
                    }
                } else {
                    Log.e("[Mouse]Debug", "Request Failed with code: ${response.code}")
                }
            }
        } catch (e: Exception) { 
            Log.e("[Mouse]Debug", "Fetch EXCEPTION for $originalIp ($port): ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    data class ServerInfo(val hostname: String, val paired: Boolean)

    private fun parseServerInfo(xml: String): ServerInfo? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var hostname = ""
            var paired = false
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) { // 忽略大小写
                        "hostname" -> { try { hostname = parser.nextText() } catch (e: Exception) {} }
                        "pairstatus" -> { try { paired = parser.nextText() == "1" } catch (e: Exception) {} }
                    }
                }
                eventType = parser.next()
            }
            return ServerInfo(hostname, paired)
        } catch (e: Exception) { }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        executor.execute {
             val certHex = CryptoUtils.getCertificatePemHex(this)
             val certSig = CryptoUtils.getCertificateSignature(this)
             Log.e("[Mouse]Debug", "APP START. Cert First 20 chars: ${certHex.take(20)}")
             Log.e("[Mouse]Debug", "APP START. Cert Sig Hash: ${certSig?.contentHashCode()}")
             
             CryptoUtils.ensureKeysExist(this) 
        }
        
        pairingManager = PairingManager(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("multicastLock")
        multicastLock?.setReferenceCounted(true)

        progressBar = findViewById(R.id.progress_scanning)
        statusText = findViewById(R.id.tv_status)
        refreshBtn = findViewById(R.id.btn_refresh)
        val recycler = findViewById<RecyclerView>(R.id.recycler_hosts)
        
        statusText.setOnClickListener {
            showManualIpDialog()
        }

        hostAdapter = HostDeviceAdapter { host ->
            if (host.isPaired) {
                 fetchAppList(host)
            } else {
                 prepareAndPair(host)
            }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hostAdapter

        refreshBtn.setOnClickListener { refreshDiscovery() }
    }
    
    private fun fetchAppList(host: HostInfo) {
        val pd = AlertDialog.Builder(this)
            .setTitle("获取应用列表...")
            .setMessage("正在连接 ${host.name}...")
            .setCancelable(false)
            .create()
        pd.show()

        executor.execute {
            try {
                val uniqueId = PreferenceUtils.getUniqueId(this)
                val url = "https://${host.address}:47984/applist?uniqueid=$uniqueId"
                
                Log.e("[Mouse]Debug", "Fetching App List: $url")
                
                val sslContext = CryptoUtils.getClientSSLContext(this)
                if (sslContext == null) {
                    runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(this, "证书加载失败", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, object : X509TrustManager {
                         override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                         override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                         override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    })
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("X-Nv-ClientID", uniqueId)
                    .header("Connection", "close")
                    .build()

                client.newCall(request).execute().use { response ->
                    val xml = response.body?.string()
                    Log.e("[Mouse]Debug", "AppList Resp: ${response.code}")
                    if (response.isSuccessful && xml != null) {
                        val apps = parseAppList(xml)
                        runOnUiThread {
                            pd.dismiss()
                            showAppListDialog(host, apps)
                        }
                    } else {
                        runOnUiThread {
                            pd.dismiss()
                            Toast.makeText(this, "获取失败: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("[Mouse]Debug", "AppList Error", e)
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, "连接错误: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parseAppList(xml: String): List<NvApp> {
        val apps = ArrayList<NvApp>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            
            var eventType = parser.eventType
            var currentAppName = ""
            var currentAppId = ""
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "AppTitle" -> { try { currentAppName = parser.nextText() } catch (e: Exception) {} }
                        "ID" -> { try { currentAppId = parser.nextText() } catch (e: Exception) {} }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (parser.name == "App") {
                        if (currentAppName.isNotBlank() && currentAppId.isNotBlank()) {
                            apps.add(NvApp(currentAppName, currentAppId))
                        }
                        currentAppName = ""
                        currentAppId = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { }
        return apps
    }

    private fun showAppListDialog(host: HostInfo, apps: List<NvApp>) {
        val appNames = apps.map { it.appName }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("选择应用启动")
            .setItems(appNames) { _, which ->
                val selectedApp = apps[which]
                Toast.makeText(this, "正在启动 ${selectedApp.appName}...", Toast.LENGTH_SHORT).show()
                // TODO: 发送 Launch 请求
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        refreshDiscovery()
    }
    
    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    private fun showManualIpDialog() {
        val input = EditText(this)
        input.hint = "192.168.x.x"
        AlertDialog.Builder(this)
            .setTitle("手动添加主机 IP")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val ip = input.text.toString()
                if (ip.isNotBlank()) {
                    val host = HostInfo(ip, ip, 47989)
                    hostAdapter.addHost(host)
                    fetchServerInfo(ip)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun prepareAndPair(host: HostInfo) {
        Log.e("[Mouse]Streaming", "prepareAndPair started for ${host.address}")
        val pin = String.format("%04d", Random.nextInt(10000))

        val messageView = TextView(this)
        messageView.text = "请在电脑弹出的 Sunshine 窗口中输入以下配对码：\n\n$pin\n\n(请保持此窗口打开，直到电脑输入完成)"
        messageView.textSize = 18f
        messageView.setPadding(50, 50, 50, 0)
        messageView.textAlignment = View.TEXT_ALIGNMENT_CENTER

        val pd = AlertDialog.Builder(this)
            .setTitle("请输入配对码")
            .setView(messageView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> }
            .create()
        pd.show()

        pairingManager?.initiatePairing(host.address, 47984, pin, object : PairingManager.PairingStepCallback {
            override fun onLog(msg: String) {
                 Log.e("[Mouse]Pairing", "LOG: $msg")
            }

            override fun onStep1Success(clientSaltHex: String) {
                Log.e("[Mouse]Streaming", "Step 1 Success. PC accepted PIN request.")
                runOnUiThread {
                    messageView.text = "电脑已响应！正在验证密钥...\n\n$pin"
                }
                executePairingCompletion(host, pin, clientSaltHex, pd)
            }

            override fun onPairingSuccess() { }

            override fun onError(error: String) {
                Log.e("[Mouse]Streaming", "Pairing Error: $error")
                runOnUiThread {
                    pd.dismiss()
                    AlertDialog.Builder(this@StreamingActivity)
                        .setTitle("连接失败")
                        .setMessage(error)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        })
    }

    private fun executePairingCompletion(host: HostInfo, pin: String, serverSaltHex: String, pd: AlertDialog) {
        pairingManager?.completePairing(host.address, 47984, pin, serverSaltHex, object : PairingManager.PairingStepCallback {
            override fun onLog(msg: String) {
                Log.e("[Mouse]Pairing", "CompLog: $msg")
            }

            override fun onStep1Success(salt: String) {}

            override fun onPairingSuccess() {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this@StreamingActivity, "配对成功！", Toast.LENGTH_SHORT).show()
                    hostAdapter.updateHostInfo(host.address, host.name, true)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    pd.dismiss()
                    AlertDialog.Builder(this@StreamingActivity)
                        .setTitle("验证失败")
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
        multicastLock?.let {
            if (it.isHeld) it.release()
            it.acquire()
        }

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {}

        resolveQueue.clear()
        isResolving = false
        mainHandler.removeCallbacks(resolveTimeoutRunnable)

        mainHandler.postDelayed({
            try {
                nsdManager.discoverServices("_nvstream._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                Log.e("Streaming", "Start discovery failed", e)
                isDiscoveryRunning = false
                runOnUiThread {
                    refreshBtn.isEnabled = true
                    Toast.makeText(this, "无法启动搜索", Toast.LENGTH_SHORT).show()
                }
            }
        }, 500)
    }
    
    private fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {}
        
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(stopDiscoveryTask)
        mainHandler.removeCallbacks(resolveTimeoutRunnable)
        stopDiscovery()
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