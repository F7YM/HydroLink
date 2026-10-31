package com.hydro.link.shared.data

import com.hydro.link.shared.model.Command
import com.hydro.link.shared.model.LogEntry
import com.hydro.link.shared.model.PairRequest
import com.hydro.link.shared.model.PluginRow
import com.hydro.link.shared.model.TrustedDevice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Go 服务端 HTTP 协议的数据形状（字段名与 `desktop/internal/api` 的 JSON 一一对应）。
 *
 * 手写 DTO 而不是反射/动态解析：协议是自家定的，字段少而且要跨端复用，写死最省心；
 * 解析不了的字段直接忽略（`ignoreUnknownKeys`），Go 侧加字段不会把桌面端打崩。
 */
@Serializable
private data class StatusResponse(
    @SerialName("status") val status: String = "",
    @SerialName("trusted_count") val trustedCount: Int = 0,
    @SerialName("system") val system: SystemInfo = SystemInfo(),
) {
    @Serializable
    data class SystemInfo(
        @SerialName("hostname") val hostname: String = "",
        @SerialName("version") val version: String = "",
        /** 局域网 IP：手表/手机连的就是它，状态页要显示这个（之前端点没返回，桌面显示不出地址）。 */
        @SerialName("ip") val ip: String = "",
    )
}

@Serializable
private data class DevicesResponse(
    @SerialName("devices") val devices: List<TrustedDevice> = emptyList(),
)

@Serializable
private data class PendingPairResponse(
    @SerialName("pending") val pending: List<PairRequest> = emptyList(),
)

@Serializable
private data class CommandsResponse(
    @SerialName("commands") val commands: List<Command> = emptyList(),
)

@Serializable
private data class LogsResponse(
    @SerialName("logs") val logs: List<LogEntry> = emptyList(),
)

@Serializable
data class SettingsDto(
    @SerialName("port") val port: Int = 9000,
    @SerialName("accept_all") val acceptAll: Boolean = false,
    @SerialName("market_source") val marketSource: String = "auto",
    /** 运行中真正监听的端口（改端口要重启服务，所以要和配置里的端口分开显示）。 */
    @SerialName("current_port") val currentPort: Int = 0,
)

/**
 * 一次轮询拿回来的全部状态。
 *
 * 端点之间没有聚合接口（Go 侧是给手表用的一堆小端点），所以这里并发发一轮再合并。
 */
data class ServerSnapshot(
    val reachable: Boolean,
    val version: String = "",
    val ip: String = "",
    val port: String = "",
    val devices: List<TrustedDevice> = emptyList(),
    val pairs: List<PairRequest> = emptyList(),
    val commands: List<Command> = emptyList(),
    val plugins: List<PluginRow> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val settings: SettingsDto = SettingsDto(),
    val pluginSummary: String = "",
    val error: String? = null,
)

/** HTTP 层的薄封装：只管发请求与解码，业务语义在 [HydroApi] 里。 */
class HttpTransport(
    var baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun get(path: String): String = request("GET", path, null)

    suspend fun post(path: String, body: String? = null): String = request("POST", path, body)

    suspend fun put(path: String, body: String): String = request("PUT", path, body)

    suspend fun delete(path: String): String = request("DELETE", path, null)

    private suspend fun request(method: String, path: String, body: String?): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val conn = (java.net.URL(baseUrl + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    // 显式带 charset：请求体里有中文时不能靠默认编码猜
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw ApiException(code, text)
                }
                text
            } finally {
                conn.disconnect()
            }
        }

    fun <T> decode(text: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, text)

    val jsonFormat: Json get() = json
}

class ApiException(val code: Int, body: String) : Exception("HTTP $code: $body")

/**
 * 桌面端对 Go 服务端的全部调用（①A 方案：业务在 UI 侧实现，这里是唯一的出口）。
 *
 * 端点一览（都在 `desktop/internal/api`）：
 * - `GET /api/status` 版本与已信任设备数
 * - `GET /api/devices` / `DELETE /api/devices/{id}` 已配对设备
 * - `GET /api/pending_pair` / `POST /api/pair/respond` 配对请求
 * - `GET /api/commands` / `POST /api/commands` / `PUT /api/commands/{i}` / `DELETE /api/commands/{i}` 快捷命令
 * - `GET /api/logs` 运行日志
 * - `GET /api/settings` / `PUT /api/settings` 设置（端口只存配置，改端口要重启服务）
 * - `GET /api/hydro/list` 已装插件
 */
class HydroApi(private val http: HttpTransport) {

    fun updateBaseUrl(newBaseUrl: String) {
        http.baseUrl = newBaseUrl
    }

    /** 拉一轮完整快照；任何一个端点失败都只标记 unreachable，不让整轮崩掉。 */
    suspend fun snapshot(): ServerSnapshot = try {
        val status = http.decode(http.get("/api/status"), StatusResponse.serializer())
        val devices = http.decode(http.get("/api/devices"), DevicesResponse.serializer()).devices
        val pairs = http.decode(http.get("/api/pending_pair"), PendingPairResponse.serializer()).pending
        val commands = http.decode(http.get("/api/commands"), CommandsResponse.serializer()).commands
        val logs = http.decode(http.get("/api/logs"), LogsResponse.serializer()).logs
        val settings = http.decode(http.get("/api/settings"), SettingsDto.serializer())
        val plugins = parsePluginList(http.get("/api/hydro/list"))
        ServerSnapshot(
            reachable = true,
            version = status.system.version,
            ip = status.system.ip,
            devices = devices,
            pairs = pairs,
            commands = commands,
            plugins = plugins,
            logs = logs,
            settings = settings,
            pluginSummary = "已安装 ${plugins.size} 个插件",
        )
    } catch (e: Exception) {
        ServerSnapshot(reachable = false, error = e.message)
    }

    suspend fun respondPair(deviceId: String, accept: Boolean, deviceName: String? = null) {
        http.post(
            "/api/pair/respond",
            http.jsonFormat.encodeToString(
                PairRespond.serializer(),
                PairRespond(deviceId, if (accept) "accept" else "reject", deviceName?.takeIf { it.isNotBlank() }),
            ),
        )
    }

    suspend fun deleteDevice(deviceId: String) {
        http.delete("/api/devices/" + java.net.URLEncoder.encode(deviceId, "UTF-8"))
    }

    suspend fun saveCommand(index: Int?, name: String, command: String) {
        val body = http.jsonFormat.encodeToString(
            CommandDto.serializer(),
            CommandDto(name = name, command = command),
        )
        if (index == null) http.post("/api/commands", body) else http.put("/api/commands/$index", body)
    }

    suspend fun deleteCommand(index: Int) {
        http.delete("/api/commands/$index")
    }

    suspend fun runCommand(index: Int) {
        http.post("/api/commands/$index/run")
    }

    suspend fun saveSettings(port: Int? = null, acceptAll: Boolean? = null, marketSource: String? = null) {
        val body = buildString {
            append('{')
            var first = true
            if (port != null) { append("\"port\":").append(port); first = false }
            if (acceptAll != null) { if (!first) append(','); append("\"accept_all\":").append(acceptAll); first = false }
            if (marketSource != null) {
                if (!first) append(',')
                append("\"market_source\":\"").append(marketSource).append('"')
            }
            append('}')
        }
        http.put("/api/settings", body)
    }

    /**
     * 卸载插件：调 Go 侧的卸载端点，再重扫让运行时立刻生效。
     * 插件存储按安卓端语义保留（重装后设置还在）。
     */
    suspend fun uninstallPlugin(id: String) {
        http.delete("/api/plugins/" + java.net.URLEncoder.encode(id, "UTF-8"))
    }

    /**
     * 让 Go 侧安装一个本地 .hpk。
     *
     * 职责划分与安卓端同构：UI 负责下载与 sha256 校验，Go 侧负责解包安装并热注册——
     * 否则两端各写一套解包逻辑，插件目录格式一旦变就要改两处。
     */
    suspend fun installPlugin(path: String): String? {
        return try {
            val conn = (java.net.URL(http.baseUrl + "/api/plugins/install").openConnection() as
                java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use {
                it.write(
                    http.jsonFormat.encodeToString(InstallReq.serializer(), InstallReq(path))
                        .toByteArray(Charsets.UTF_8),
                )
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) null else text
        } catch (e: Exception) {
            e.message ?: e::class.simpleName.orEmpty()
        }
    }

    /** 装完插件让运行时重扫（不必重启服务）。 */
    suspend fun reloadPlugins(): List<String> {
        val text = http.post("/api/hydro/reload")
        val obj = http.jsonFormat.parseToJsonElement(text).jsonObject
        return obj["plugins"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    }

    /** 拉取当前已安装并注册的插件列表。 */
    suspend fun listPlugins(): List<PluginRow> = try {
        parsePluginList(http.get("/api/hydro/list"))
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * `/api/hydro/list` 的解析。
     *
     * 插件清单是嵌套的（`{"plugins":[{"package":{"id":…},"apps":[…]}]}`，对应 Go 侧
     * `hydrokit.PluginSummary`），和扁平的应用包 manifest 不是一回事——两种形状别混用。
     */
    private fun parsePluginList(text: String): List<PluginRow> {
        val root = http.jsonFormat.parseToJsonElement(text).jsonObject
        val plugins = root["plugins"]?.jsonArray ?: return emptyList()
        return plugins.mapNotNull { element ->
            val obj = element.jsonObject
            val pkg = obj["package"]?.jsonObject ?: return@mapNotNull null
            val id = pkg["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val apps = obj["apps"]?.jsonArray
            val itemNames = apps?.mapNotNull { app ->
                val label = app.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                "应用 $label"
            } ?: emptyList()
            PluginRow(
                id = id,
                name = pkg["name"]?.jsonPrimitive?.content ?: id,
                author = pkg["author"]?.jsonPrimitive?.content ?: "",
                version = pkg["version"]?.jsonPrimitive?.content ?: "",
                runtime = pkg["runtime"]?.jsonPrimitive?.content ?: "",
                items = itemNames.joinToString("、"),
                permissions = pkg["permissions"]?.jsonArray
                    ?.joinToString(", ") { it.jsonPrimitive.content } ?: "",
                hasWeb = obj["web"]?.jsonPrimitive?.contentOrNull() == "true" ||
                    obj["has_web"]?.jsonPrimitive?.contentOrNull() == "true",
                workerOnline = obj["worker_online"]?.jsonPrimitive?.contentOrNull() == "true",
                error = obj["error"]?.jsonPrimitive?.contentOrNull(),
                // 图标取第一个 app/widget 的 icon（服务端已把相对路径转成 asset URL）
                iconUrl = (apps?.firstNotNullOfOrNull { app ->
                    app.jsonObject["icon"]?.jsonPrimitive?.contentOrNull()
                } ?: obj["widgets"]?.jsonArray?.firstNotNullOfOrNull { w ->
                    w.jsonObject["icon"]?.jsonPrimitive?.contentOrNull()
                }).orEmpty(),
            )
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}

@Serializable
private data class PairRespond(
    @SerialName("device_id") val deviceId: String,
    @SerialName("action") val action: String,
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
private data class InstallReq(
    @SerialName("path") val path: String,
)

@Serializable
private data class CommandDto(
    @SerialName("name") val name: String,
    @SerialName("command") val command: String,
)
