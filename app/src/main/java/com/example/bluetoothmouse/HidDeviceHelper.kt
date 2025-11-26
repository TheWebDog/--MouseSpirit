package com.example.bluetoothmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

class HidDeviceHelper(private val context: Context, private val listener: HidConnectionListener) {

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null

    private val executor = Executors.newSingleThreadExecutor()

    interface HidConnectionListener {
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
        fun onHidStateChanged(state: String)
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidDevice()
                listener.onHidStateChanged("HID 服务已连接")

                val connectedDevices = hidDevice?.connectedDevices
                if (!connectedDevices.isNullOrEmpty()) {
                    hostDevice = connectedDevices[0]
                    listener.onDeviceConnected(hostDevice!!)
                    Log.d(TAG, "自动恢复连接到: ${hostDevice?.name}")
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                listener.onHidStateChanged("HID 服务断开")
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                listener.onHidStateChanged("HID App 注册成功")
            } else {
                listener.onHidStateChanged("HID App 注册失败")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    device?.let { listener.onDeviceConnected(it) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (hostDevice == device) {
                        hostDevice = null
                    }
                    device?.let { listener.onDeviceDisconnected(it) }
                }
            }
        }
    }

    init {
        bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidDevice() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "AI Mouse",
            "AI HID Mouse Control",
            "Android",
            0x00,
            HidConstants.MOUSE_REPORT_DESC
        )

        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            hidDevice?.registerApp(sdpSettings, inQos, outQos, executor, callback)
        } catch (e: Exception) {
            Log.e(TAG, "注册 HID App 失败", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun sendReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        if (hidDevice != null && hostDevice != null) {
            val report = ByteArray(4)
            report[0] = buttons.toByte()
            report[1] = dx.toByte()
            report[2] = dy.toByte()
            report[3] = wheel.toByte()
            
            hidDevice?.sendReport(hostDevice, 0, report)
        }
    }
    
    fun cleanup() {
        if (hidDevice != null) {
             try {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
             } catch (e: Exception) {
                 Log.e(TAG, "Cleanup error", e)
             }
            hidDevice = null
        }
    }

    companion object {
        private const val TAG = "HidDeviceHelper"
    }
}