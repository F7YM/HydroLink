package com.hydro.link.server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hydro.link.server.R
import com.hydro.link.server.model.Command
import com.hydro.link.server.model.LogEntry
import com.hydro.link.server.model.PairRequest
import com.hydro.link.server.model.TrustedDevice

// ---- Device Adapter ----
class DeviceAdapter(
    private val onDelete: (TrustedDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<TrustedDevice>()

    fun update(devices: List<TrustedDevice>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = items[position]
        holder.tvName.text = device.deviceName.ifEmpty { "Unknown" }
        holder.tvId.text = device.deviceId.take(16) + "..."
        holder.btnDelete.setOnClickListener { onDelete(device) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvId: TextView = view.findViewById(R.id.tvDeviceId)
        val btnDelete: View = view.findViewById(R.id.btnDelete)
    }
}

// ---- Pair Request Adapter ----
class PairRequestAdapter(
    private val onAccept: (PairRequest) -> Unit,
    private val onReject: (PairRequest) -> Unit
) : RecyclerView.Adapter<PairRequestAdapter.VH>() {

    private val items = mutableListOf<PairRequest>()

    fun update(pairs: List<PairRequest>) {
        items.clear()
        items.addAll(pairs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pair_request, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pair = items[position]
        holder.tvName.text = pair.deviceName.ifEmpty { "Unknown" }
        holder.tvId.text = pair.deviceId.take(16) + "..."
        holder.btnAccept.setOnClickListener { onAccept(pair) }
        holder.btnReject.setOnClickListener { onReject(pair) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPairDeviceName)
        val tvId: TextView = view.findViewById(R.id.tvPairDeviceId)
        val btnAccept: MaterialButton = view.findViewById(R.id.btnAccept)
        val btnReject: MaterialButton = view.findViewById(R.id.btnReject)
    }
}

// ---- Command Adapter ----
class CommandAdapter(
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
) : RecyclerView.Adapter<CommandAdapter.VH>() {

    private val items = mutableListOf<Command>()

    fun update(commands: List<Command>) {
        items.clear()
        items.addAll(commands)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_command, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cmd = items[position]
        holder.tvName.text = cmd.name.ifEmpty { "命令 ${position + 1}" }
        holder.tvContent.text = cmd.command
        holder.btnEdit.setOnClickListener { onEdit(position) }
        holder.btnDelete.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCommandName)
        val tvContent: TextView = view.findViewById(R.id.tvCommandContent)
        val btnEdit: View = view.findViewById(R.id.btnEditCommand)
        val btnDelete: View = view.findViewById(R.id.btnDeleteCommand)
    }
}

// ---- Log Adapter ----
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val items = mutableListOf<LogEntry>()

    fun update(logs: List<LogEntry>) {
        items.clear()
        items.addAll(logs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = items[position]
        holder.tvText.text = log.text
        val colorRes = when (log.level) {
            "error" -> R.color.md_theme_error
            "warning" -> R.color.md_theme_warning
            "success" -> R.color.md_theme_success
            else -> R.color.md_theme_outline
        }
        holder.dot.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, colorRes))
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvLogText)
        val dot: View = view.findViewById(R.id.tvLogLevel)
    }
}

