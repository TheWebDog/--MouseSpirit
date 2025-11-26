package com.example.bluetoothmouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class StreamingActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private lateinit var hostAdapter: HostDeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var refreshBtn: Button

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                statusText.text = "正在搜索 Sunshine 主机..."
            }
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("_nvstream")) {
                nsdManager.resolveService(service, resolveListener)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            // Optional: Remove from list
        }

        override fun onDiscoveryStopped(serviceType: String) {
            runOnUiThread {
                progressBar.visibility = View.GONE
                statusText.text = "搜索结束"
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            runOnUiThread {
                Toast.makeText(this@StreamingActivity, "搜索失败: $errorCode", Toast.LENGTH_SHORT).show()
                nsdManager.stopServiceDiscovery(this)
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            nsdManager.stopServiceDiscovery(this)
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Resolve failed
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host
            val port = serviceInfo.port
            val name = serviceInfo.serviceName

            runOnUiThread {
                hostAdapter.addHost(HostInfo(name, host.hostAddress ?: "", port))
                statusText.text = "发现主机: $name"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

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
            // TODO: 点击后进入配对流程
            Toast.makeText(this, "选中主机: ${host.name}", Toast.LENGTH_SHORT).show()
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hostAdapter

        refreshBtn.setOnClickListener {
            startDiscovery()
        }

        startDiscovery()
    }

    private fun startDiscovery() {
        try {
            hostAdapter.clear()
            // Moonlight/Sunshine 使用 _nvstream._tcp 协议
            nsdManager.discoverServices("_nvstream._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            // Discovery might be already running
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}