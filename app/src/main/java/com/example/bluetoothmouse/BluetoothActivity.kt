package com.example.bluetoothmouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BluetoothActivity : AppCompatActivity(), HidDeviceHelper.HidConnectionListener {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private lateinit var switchBluetooth: Switch
    private var hidHelper: HidDeviceHelper? = null
    
    private var pendingConnectionDevice: BluetoothDevice? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
            val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (connectGranted && scanGranted) {
                    startScan()
                } else {
                    Toast.makeText(this, "Android 12+ 需要蓝牙连接和扫描权限", Toast.LENGTH_LONG).show()
                }
            } else {
                if (locationGranted) {
                    checkLocationEnabledAndScan()
                } else {
                    Toast.makeText(this, "Android 11及以下搜索蓝牙需要定位权限", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        switchBluetooth.isChecked = true
                        checkPermissionsAndScan()
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        switchBluetooth.isChecked = false
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        // 移除名称过滤，确保能看到所有搜到的设备
                        deviceAdapter.addDevice(it)
                        if (it.bondState == BluetoothDevice.BOND_NONE) {
                             // 只更新未配对设备的状态，已配对的保持“已配对”
                             // 这里检查一下adapter里是否已经是配对状态，避免覆盖
                             // 简单处理：如果adapter里没有或者状态是默认，则设为未配对
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    supportActionBar?.subtitle = "正在搜索附近设备..."
                    Toast.makeText(context, "开始扫描设备...", Toast.LENGTH_SHORT).show()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    supportActionBar?.subtitle = null
                    // Toast.makeText(context, "扫描结束", Toast.LENGTH_SHORT).show()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    
                    if (device != null) {
                        when (state) {
                            BluetoothDevice.BOND_BONDING -> deviceAdapter.setStatus(device.address, "配对中...")
                            BluetoothDevice.BOND_BONDED -> {
                                deviceAdapter.setStatus(device.address, "已配对")
                                if (pendingConnectionDevice?.address == device.address) {
                                    connectToDevice(device)
                                    pendingConnectionDevice = null
                                }
                            }
                            BluetoothDevice.BOND_NONE -> deviceAdapter.setStatus(device.address, "未配对")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)

        hidHelper = HidDeviceHelper(this, this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        switchBluetooth = findViewById(R.id.switch_bluetooth)
        val recyclerDevices = findViewById<RecyclerView>(R.id.recycler_devices)

        deviceAdapter = BluetoothDeviceAdapter { device ->
            handleDeviceClick(device)
        }

        recyclerDevices.layoutManager = LinearLayoutManager(this)
        recyclerDevices.adapter = deviceAdapter

        switchBluetooth.isChecked = bluetoothAdapter.isEnabled
        switchBluetooth.setOnClickListener {
            if (switchBluetooth.isChecked) {
                // 用户试图开启
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                // 简单检查一下是否有权限启动intent，但在老版本通常不需要权限，新版本可能需要BLUETOOTH_CONNECT
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                     ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                         requestPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                 } else {
                     startActivity(enableBtIntent)
                 }
            } else {
                // 用户试图关闭
                Toast.makeText(this, "请在系统设置中关闭蓝牙", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)

        if (bluetoothAdapter.isEnabled) {
            checkPermissionsAndScan()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        }
        unregisterReceiver(bluetoothReceiver)
        hidHelper?.cleanup()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_bluetooth, menu)
        return true
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        // Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 11及以下
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            // 权限都有了，如果是老版本，还需要检查GPS开关
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                checkLocationEnabledAndScan()
            } else {
                startScan()
            }
        }
    }

    private fun checkLocationEnabledAndScan() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        
        if (!isGpsEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要开启定位服务")
                .setMessage("Android 11及以下系统要求开启GPS定位服务才能搜索蓝牙设备。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        deviceAdapter.clear()
        
        // 1. 加载已配对设备
        val pairedDevices = bluetoothAdapter.bondedDevices
        if (!pairedDevices.isNullOrEmpty()) {
            pairedDevices.forEach { device ->
                deviceAdapter.addDevice(device)
                deviceAdapter.setStatus(device.address, "已配对")
            }
        }

        // 2. 开启发现
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        
        val success = bluetoothAdapter.startDiscovery()
        if (!success) {
            Toast.makeText(this, "启动扫描失败，请检查蓝牙状态或重启App", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceClick(device: BluetoothDevice) {
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        if (device.bondState == BluetoothDevice.BOND_NONE) {
            deviceAdapter.setStatus(device.address, "请求配对...")
            pendingConnectionDevice = device
            val bonded = device.createBond()
            if (!bonded) {
                Toast.makeText(this, "配对请求失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            connectToDevice(device)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        deviceAdapter.setStatus(device.address, "连接中...")
        hidHelper?.connect(device)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        } else if (item.itemId == R.id.action_refresh) {
            checkPermissionsAndScan()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        runOnUiThread {
            deviceAdapter.setStatus(device.address, "已连接")
            Toast.makeText(this, "HID 已连接: ${device.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        runOnUiThread {
            deviceAdapter.setStatus(device.address, "已断开")
        }
    }

    override fun onHidStateChanged(state: String) {
        // Log.d("BluetoothActivity", state)
    }
}