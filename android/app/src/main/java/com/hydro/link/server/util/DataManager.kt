package com.hydro.link.server.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hydro.link.server.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DataManager private constructor(private val context: Context) {

    private val gson = Gson()
    private val dataDir: File by lazy {
        File(context.filesDir, "data").apply { mkdirs() }
    }

    private val trustedDevicesFile get() = File(dataDir, "trusted_devices.json")
    private val commandsFile get() = File(dataDir, "commands.json")
    private val configFile get() = File(dataDir, "config.json")
    private val rejectedDevicesFile get() = File(dataDir, "rejected_devices.json")
    private val pendingPairFile get() = File(dataDir, "pending_pair.json")

    // ---- Trusted Devices ----
    fun loadTrustedDevices(): MutableList<TrustedDevice> {
        return loadJson(trustedDevicesFile) { mutableListOf() }
    }

    fun saveTrustedDevices(devices: List<TrustedDevice>) {
        saveJson(trustedDevicesFile, devices)
    }

    fun isTrusted(deviceId: String): Boolean {
        return loadTrustedDevices().any { it.deviceId == deviceId }
    }

    fun addTrustedDevice(deviceId: String, deviceName: String) {
        val devices = loadTrustedDevices()
        if (!devices.any { it.deviceId == deviceId }) {
            devices.add(TrustedDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                trustedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            ))
            saveTrustedDevices(devices)
        }
    }

    fun removeTrustedDevice(deviceId: String) {
        val devices = loadTrustedDevices()
        devices.removeAll { it.deviceId == deviceId }
        saveTrustedDevices(devices)
    }

    // ---- Rejected Devices ----
    fun isRejected(deviceId: String): Boolean {
        val list: MutableList<String> = loadJson(rejectedDevicesFile) { mutableListOf() }
        return list.contains(deviceId)
    }

    fun addRejected(deviceId: String) {
        val list: MutableList<String> = loadJson(rejectedDevicesFile) { mutableListOf() }
        if (!list.contains(deviceId)) {
            list.add(deviceId)
            saveJson(rejectedDevicesFile, list)
        }
    }

    // ---- Pending Pairs ----
    fun loadPendingPairs(): MutableList<PairRequest> {
        return loadJson(pendingPairFile) { mutableListOf() }
    }

    fun addPendingPair(deviceId: String, deviceName: String) {
        val pairs = loadPendingPairs()
        if (!pairs.any { it.deviceId == deviceId }) {
            pairs.add(PairRequest(deviceId, deviceName))
            saveJson(pendingPairFile, pairs)
        }
    }

    fun removePendingPair(deviceId: String) {
        val pairs = loadPendingPairs()
        pairs.removeAll { it.deviceId == deviceId }
        saveJson(pendingPairFile, pairs)
    }

    // ---- Commands ----
    fun loadCommands(): MutableList<Command> {
        return loadJson(commandsFile) { mutableListOf() }
    }

    fun saveCommands(commands: List<Command>) {
        saveJson(commandsFile, commands)
    }

    fun addCommand(name: String, command: String) {
        val commands = loadCommands()
        commands.add(Command(name, command))
        saveCommands(commands)
    }

    /** 编辑已有命令（索引越界则忽略）。 */
    fun updateCommand(index: Int, name: String, command: String) {
        val commands = loadCommands()
        if (index in commands.indices) {
            commands[index] = Command(name, command)
            saveCommands(commands)
        }
    }

    fun removeCommand(index: Int) {
        val commands = loadCommands()
        if (index in commands.indices) {
            commands.removeAt(index)
            saveCommands(commands)
        }
    }

    // ---- Config ----
    fun loadConfig(): AppConfig {
        val config = loadJson(configFile) { AppConfig() }
        if (config.encryptionKey.isEmpty()) {
            config.encryptionKey = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            saveConfig(config)
        }
        return config
    }

    fun saveConfig(config: AppConfig) {
        saveJson(configFile, config)
    }

    // ---- Generic JSON helpers ----
    private inline fun <reified T> loadJson(file: File, default: () -> T): T {
        if (!file.exists()) return default()
        return try {
            val text = file.readText()
            if (text.isBlank()) return default()
            val type = object : TypeToken<T>() {}.type
            gson.fromJson(text, type) ?: default()
        } catch (e: Exception) {
            // Data corrupted — return default so app doesn't crash on startup
            default()
        }
    }

    private fun saveJson(file: File, data: Any) {
        try {
            // Write to temp file first, then rename for atomicity
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(data))
            tmp.renameTo(file)
        } catch (e: Exception) {
            // ignore write errors
        }
    }

    companion object {
        @Volatile
        private var instance: DataManager? = null

        fun get(context: Context): DataManager {
            return instance ?: synchronized(this) {
                instance ?: DataManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
