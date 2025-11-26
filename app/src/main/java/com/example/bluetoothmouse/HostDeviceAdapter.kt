package com.example.bluetoothmouse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class HostInfo(
    val name: String,
    val address: String,
    val port: Int,
    var isPaired: Boolean = false
)

class HostDeviceAdapter(
    private val onHostClick: (HostInfo) -> Unit
) : RecyclerView.Adapter<HostDeviceAdapter.HostViewHolder>() {

    private val hosts = ArrayList<HostInfo>()

    fun addHost(host: HostInfo) {
        // 避免重复添加 (简单根据 IP 判断)
        if (hosts.none { it.address == host.address }) {
            hosts.add(host)
            notifyItemInserted(hosts.size - 1)
        }
    }

    fun clear() {
        hosts.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_host_device, parent, false)
        return HostViewHolder(view)
    }

    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        holder.bind(hosts[position])
    }

    override fun getItemCount(): Int = hosts.size

    inner class HostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.host_name)
        private val addressView: TextView = itemView.findViewById(R.id.host_address)
        private val statusView: TextView = itemView.findViewById(R.id.host_status)

        fun bind(host: HostInfo) {
            nameView.text = host.name
            addressView.text = "${host.address}:${host.port}"
            
            if (host.isPaired) {
                statusView.text = "已配对"
                statusView.setTextColor(0xFF4CAF50.toInt()) // Green
            } else {
                statusView.text = "未配对"
                statusView.setTextColor(0xFFFF9800.toInt()) // Orange
            }
            
            itemView.setOnClickListener {
                onHostClick(host)
            }
        }
    }
}