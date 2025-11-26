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
import kotlin.random.Random

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
            Log.e("Streaming", "Resolve timed out - forcing next")
            isResolving = false // 强制重置状态
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
            // 缩短自动搜索时间到 5 秒，避免长时间占用
            mainHandler.postDelayed(stopDiscoveryTask, 5000)
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("Streaming", "Service found: ${service.serviceName}")
            if (service.serviceType.contains("_nvstream")) {
                val newService = NsdServiceInfo()
                newService.serviceName = service.serviceName
                newService.serviceType = service.serviceType
                queueResolve(newService)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e("Streaming", "Service lost: ${service.serviceName}")
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
            Log.e("Streaming", "Discovery failed: $errorCode")
            isDiscoveryRunning = false
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            runOnUiThread {
                refreshBtn.isEnabled = true
                statusText.text = "搜索启动失败 ($errorCode)"
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("Streaming", "Stop Discovery failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("Streaming", "Resolve failed: $errorCode")
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            isResolving = false
            processNextInQueue()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            Log.d("Streaming", "Resolve Succeeded: ${serviceInfo.host}")
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
                Log.e("Streaming", "Resolution crash", e)
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
        // 直接使用 HTTP 47989 获取信息，这比 HTTPS 快且不需要证书
        executor.execute {
            try {
                val hostString = if (ip.contains(":")) "[$ip]" else ip
                // 优先尝试 HTTP 47989
                tryFetchInfo(hostString, 47989, ip)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun tryFetchInfo(hostString: String, port: Int, originalIp: String) {
        try {
            // 使用 unsafe client 即可
            val url = "http://$hostString:$port/serverinfo"
            val client = NetworkUtils.getUnsafeOkHttpClient()
            val request = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body?.string()
                    if (xml != null) {
                        val info = parseServerInfo(xml)
                        if (info != null && info.hostname.isNotBlank()) {
                            runOnUiThread {
                                hostAdapter.updateHostInfo(originalIp, info.hostname, info.paired)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { 
            Log.w("Streaming", "Fetch info failed for $originalIp: ${e.message}")
        }
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
                    when (parser.name) {
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
             CryptoUtils.regenerateKeys(this)
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
        
        // 添加手动输入 IP 的功能 (临时 debug 用，点击标题栏文字)
        statusText.setOnClickListener {
            showManualIpDialog()
        }

        hostAdapter = HostDeviceAdapter { host ->
            Toast.makeText(this, "尝试连接 ${host.name}...", Toast.LENGTH_SHORT).show()
            Log.e("[Mouse]Streaming", "CLICKED Host: ${host.name} at ${host.address}")
            
            if (host.isPaired) {
                 Toast.makeText(this, "该设备已配对", Toast.LENGTH_SHORT).show()
            } else {
                 prepareAndPair(host)
            }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hostAdapter

        refreshBtn.setOnClickListener { refreshDiscovery() }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到页面都自动开始搜索
        refreshDiscovery()
    }
    
    override fun onPause() {
        super.onPause()
        // 离开页面停止搜索，释放资源
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
                    // 刷新列表项状态
                    hostAdapter.updateHostInfo(host.address, host.name, true)
                    // 延迟进入下一步，给用户一点反馈时间
                    mainHandler.postDelayed({
                        // TODO: 跳转到鼠标控制界面
                    }, 1000)
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
        // 每次启动搜索前，强制刷新组播锁
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

        // 稍微延时启动，确保之前的 stop 完成
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