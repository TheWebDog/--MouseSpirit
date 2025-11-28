package com.example.bluetoothmouse

import android.content.Context
import android.content.Intent
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
import com.limelight.nvstream.ConnectionContext
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

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
    
    // Map to hold NvHTTP instances for each host address to avoid recreating them
    private val httpClients = HashMap<String, NvHTTP>()
    
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    private val resolveTimeoutRunnable = Runnable {
        if (isResolving) {
            Log.e("StreamingActivity", "Resolve timed out - forcing next")
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
            Log.d("StreamingActivity", "Service found: ${service.serviceName}")
            if (service.serviceType.contains("_nvstream")) {
                val newService = NsdServiceInfo()
                newService.serviceName = service.serviceName
                newService.serviceType = service.serviceType
                queueResolve(newService)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e("StreamingActivity", "Service lost: ${service.serviceName}")
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
            Log.e("StreamingActivity", "Discovery failed: $errorCode")
            isDiscoveryRunning = false
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            runOnUiThread {
                refreshBtn.isEnabled = true
                statusText.text = "搜索启动失败 ($errorCode)"
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("StreamingActivity", "Stop Discovery failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("StreamingActivity", "Resolve failed: $errorCode")
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            isResolving = false
            processNextInQueue()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            mainHandler.removeCallbacks(resolveTimeoutRunnable)
            Log.d("StreamingActivity", "Resolve Succeeded: ${serviceInfo.host}")
            val hostIp = serviceInfo.host.hostAddress
            val port = serviceInfo.port
            val mDnsName = serviceInfo.serviceName

            if (hostIp != null) {
                // Add to UI initially
                runOnUiThread {
                    hostAdapter.addHost(HostInfo(mDnsName, hostIp, port))
                }
                // Fetch details to get pairing status and actual hostname
                refreshHostDetails(hostIp, port)
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
                Log.e("StreamingActivity", "Resolution crash", e)
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

    private fun getNvHTTP(address: String, httpsPort: Int = NvHTTP.DEFAULT_HTTPS_PORT): NvHTTP {
        if (httpClients.containsKey(address)) {
            return httpClients[address]!!
        }
        // Initialize CryptoUtils with context if not already
        CryptoUtils.init(applicationContext)
        
        // Create NvHTTP instance
        val tuple = ComputerDetails.AddressTuple(address, NvHTTP.DEFAULT_HTTP_PORT)
        // Note: NvHTTP constructor can throw IOException
        val nvHttp = NvHTTP(tuple, httpsPort, PreferenceUtils.getUniqueId(this), null, CryptoUtils)
        httpClients[address] = nvHttp
        return nvHttp
    }

    private fun refreshHostDetails(ip: String, port: Int) {
        executor.execute {
            try {
                val nvHttp = getNvHTTP(ip)
                
                // Determine online status and get ServerInfo XML
                val serverInfoXml = nvHttp.getServerInfo(true)
                val details = nvHttp.getComputerDetails(serverInfoXml)
                
                runOnUiThread {
                    hostAdapter.updateHostInfo(ip, details.name, details.pairState == PairState.PAIRED)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("StreamingActivity", "Failed to get details for $ip: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        // Ensure keys exist
        CryptoUtils.ensureKeysExist(this)
        CryptoUtils.init(this)

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

        hostAdapter = HostDeviceAdapter { hostInfo ->
            // Check pairing status again or proceed based on UI status
            if (hostInfo.isPaired) {
                 fetchAppList(hostInfo)
            } else {
                 prepareAndPair(hostInfo)
            }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hostAdapter

        refreshBtn.setOnClickListener { refreshDiscovery() }
    }
    
    private fun fetchAppList(hostInfo: HostInfo) {
        val pd = AlertDialog.Builder(this)
            .setTitle("Getting App List...")
            .setMessage("Connecting to ${hostInfo.name}...")
            .setCancelable(false)
            .create()
        pd.show()

        executor.execute {
            try {
                val nvHttp = getNvHTTP(hostInfo.address)
                
                // Verify pairing status first (and update certificate if needed)
                val pairState = nvHttp.pairState
                if (pairState != PairState.PAIRED) {
                    throw Exception("Not paired with ${hostInfo.name}")
                }

                val appList = nvHttp.appList
                runOnUiThread {
                    pd.dismiss()
                    showAppListDialog(hostInfo, appList, nvHttp)
                }
            } catch (e: Exception) {
                Log.e("StreamingActivity", "AppList Error", e)
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, "Connection Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    // If error implies not paired, update UI
                    hostInfo.isPaired = false
                    hostAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun showAppListDialog(hostInfo: HostInfo, apps: List<NvApp>, nvHttp: NvHTTP) {
        val appNames = apps.map { it.appName }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Application")
            .setItems(appNames) { _, which ->
                val selectedApp = apps[which]
                startApp(hostInfo, selectedApp, nvHttp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startApp(hostInfo: HostInfo, app: NvApp, nvHttp: NvHTTP) {
        val pd = AlertDialog.Builder(this)
            .setTitle("Launching...")
            .setMessage("Starting ${app.appName}...")
            .setCancelable(false)
            .create()
        pd.show()
        
        executor.execute {
            try {
                // 1. Create Stream Configuration
                val config = StreamConfiguration.Builder()
                    .setApp(app)
                    .setResolution(1280, 720)
                    .setRefreshRate(60)
                    .setBitrate(5000) // 5 Mbps
                    .setClientRefreshRateX100(6000)
                    .build()
                
                // 2. Create Connection Context
                val context = ConnectionContext()
                context.streamConfig = config
                context.serverAddress = ComputerDetails.AddressTuple(hostInfo.address, hostInfo.port)
                context.httpsPort = NvHTTP.DEFAULT_HTTPS_PORT // Should get from server info
                context.riKey = CryptoUtils.getClientPrivateKey() // Using private key as RI Key for now? No, RI Key is usually separate.
                // Note: In Moonlight, RI Key is often generated per session or re-used.
                // For simplicity, let's generate a random AES key.
                // Actually, StreamConfiguration handles some of this, but ConnectionContext needs riKey.
                // Let's generate a temporary key.
                val riKeyBytes = CryptoUtils.randomBytes(16)
                context.riKey = javax.crypto.spec.SecretKeySpec(riKeyBytes, "AES")
                context.riKeyId = Random.nextInt()
                
                // Need to fill in server details for context
                val serverInfo = nvHttp.getServerInfo(true)
                context.serverAppVersion = nvHttp.getServerVersion(serverInfo)
                context.serverGfeVersion = nvHttp.getGfeVersion(serverInfo)
                context.serverCodecModeSupport = nvHttp.getServerCodecModeSupport(serverInfo).toInt()
                context.isNvidiaServerSoftware = context.serverGfeVersion != null
                
                // 3. Send Launch Request
                Log.i("StreamingActivity", "Sending Launch request...")
                val success = nvHttp.launchApp(context, "launch", app.appId, false)
                
                if (success) {
                    Log.i("StreamingActivity", "Launch successful! RTSP URL: ${context.rtspSessionUrl}")
                    
                    // 4. Store context and Navigate to GameActivity
                    GlobalContext.connectionContext = context
                    
                    runOnUiThread {
                        pd.dismiss()
                        val intent = Intent(this, GameActivity::class.java)
                        intent.putExtra("HOST_ADDRESS", hostInfo.address)
                        intent.putExtra("APP_ID", app.appId.toString())
                        startActivity(intent)
                    }
                } else {
                    throw Exception("Launch failed (server returned failure)")
                }
                
            } catch (e: Exception) {
                Log.e("StreamingActivity", "Launch Error", e)
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, "Launch Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
            .setTitle("Manually Add Host IP")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val ip = input.text.toString()
                if (ip.isNotBlank()) {
                    val host = HostInfo(ip, ip, 47989)
                    hostAdapter.addHost(host)
                    refreshHostDetails(ip, 47989)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun prepareAndPair(hostInfo: HostInfo) {
        val pin = PairingManager.generatePinString()

        val messageView = TextView(this)
        messageView.text = "Please enter this PIN on the target PC:\n\n$pin\n\n(Keep this dialog open)"
        messageView.textSize = 18f
        messageView.setPadding(50, 50, 50, 0)
        messageView.textAlignment = View.TEXT_ALIGNMENT_CENTER

        val pd = AlertDialog.Builder(this)
            .setTitle("Pairing")
            .setView(messageView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> }
            .create()
        pd.show()

        executor.execute {
            try {
                val nvHttp = getNvHTTP(hostInfo.address)
                val pm = nvHttp.pairingManager
                
                // Retrieve server info for pairing
                val serverInfo = nvHttp.getServerInfo(true)
                
                // Execute pairing (blocking)
                val state = pm.pair(serverInfo, pin)
                
                runOnUiThread {
                    pd.dismiss()
                    if (state == PairState.PAIRED) {
                        Toast.makeText(this, "Pairing Successful!", Toast.LENGTH_SHORT).show()
                        hostAdapter.updateHostInfo(hostInfo.address, hostInfo.name, true)
                    } else {
                        Toast.makeText(this, "Pairing Failed: $state", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamingActivity", "Pairing Error", e)
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    Toast.makeText(this, "Start Discovery Failed", Toast.LENGTH_SHORT).show()
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
