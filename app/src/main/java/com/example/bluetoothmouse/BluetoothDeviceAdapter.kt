package com.example.bluetoothmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder>() {

    private val devices = ArrayList<BluetoothDevice>()
    // Simple map to store status messages for demo purposes. 
    // In a real app, this would be derived from connection state.
    private val deviceStatuses = HashMap<String, String>()

    fun addDevice(device: BluetoothDevice) {
        if (!devices.contains(device)) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clear() {
        devices.clear()
        deviceStatuses.clear()
        notifyDataSetChanged()
    }

    fun setStatus(address: String, status: String) {
        deviceStatuses[address] = status
        val index = devices.indexOfFirst { it.address == address }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.device_name)
        private val addressView: TextView = itemView.findViewById(R.id.device_address)
        private val statusView: TextView = itemView.findViewById(R.id.device_status)

        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            nameView.text = device.name ?: "Unknown Device"
            addressView.text = device.address
            
            val status = deviceStatuses[device.address] ?: "可连接"
            statusView.text = status
            
            itemView.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}