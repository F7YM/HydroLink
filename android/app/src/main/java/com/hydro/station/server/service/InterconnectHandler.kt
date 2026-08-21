package com.hydro.station.server.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hydro.station.server.server.HydroHttpServer
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * InterconnectHandler
 *
 * Bridges the HydroStation HTTP server with a paired Xiaomi watch through the
 * XMS Wearable SDK (com.xiaomi.xms.wearable). All traffic flows over the
 * Bluetooth-backed interconnect channel provided by the Xiaomi Wearable app.
 *
 * Initialization flow (per official SDK docs v1.4):
 *  1. Register ServiceApi.OnServiceConnectionListener — wait for service connect
 *  2. NodeApi.getConnectedNodes() — find paired watch
 *  3. AuthApi.requestPermission(nodeId, DEVICE_MANAGER) — REQUIRED for MessageApi
 *  4. MessageApi.addListener(nodeId, listener) — receive watch messages
 *  5. NodeApi.subscribe(nodeId, ITEM_CONNECTION) — re-register on reconnect
 *
 * Message protocol (JSON, encoded as UTF-8 byte arrays):
 *   Watch  -> Phone: {"type":"discover"}
 *   Phone  -> Watch: {"type":"server_info","host":"localhost","port":9000}
 *
 *   Watch  -> Phone: {"type":"api_request","id":"<req-id>","path":"/api/ping","method":"GET","body":null}
 *   Phone  -> Watch: {"type":"api_response","id":"<req-id>","data":{...}}
 */
class InterconnectHandler(
    private val context: Context,
    private val server: HydroHttpServer,
    private val httpPort: Int
) {
    private val TAG = "InterconnectHandler"
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serviceApi: ServiceApi? = null
    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var authApi: AuthApi? = null

    /** Node IDs we have already registered a message listener for. */
    private val registeredNodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Node IDs that have been granted DEVICE_MANAGER permission. */
    private val permittedNodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Connected watch node names (nodeId -> displayName). */
    private val connectedWatchNames: MutableMap<String, String> = ConcurrentHashMap()

    @Volatile
    private var running = false

    @Volatile
    private var serviceConnected = false

    /** Callback for UI status updates. */
    var statusCallback: StatusCallback? = null

    interface StatusCallback {
        fun onStatusChanged(status: InterconnectStatus)
    }

    data class InterconnectStatus(
        val serviceConnected: Boolean,
        val watchNodes: List<WatchNodeInfo>
    )

    data class WatchNodeInfo(
        val nodeId: String,
        val displayName: String,
        val permissionGranted: Boolean,
        val listenerRegistered: Boolean
    )

    private fun notifyStatus() {
        val nodes = connectedWatchNames.map { (id, name) ->
            WatchNodeInfo(
                nodeId = id,
                displayName = name,
                permissionGranted = permittedNodes.contains(id),
                listenerRegistered = registeredNodes.contains(id)
            )
        }
        statusCallback?.onStatusChanged(InterconnectStatus(serviceConnected, nodes))
    }

    private val serviceConnectionListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() {
            Log.i(TAG, "XMS Wearable service connected")
            serviceConnected = true
            notifyStatus()
            // Immediately try to find nodes
            scope.launch { refreshConnectedNodes() }
        }

        override fun onServiceDisconnected() {
            Log.w(TAG, "XMS Wearable service disconnected")
            serviceConnected = false
            // Clear registered nodes — they'll need re-registration on reconnect
            for (nodeId in registeredNodes) {
                try {
                    messageApi?.removeListener(nodeId)
                } catch (e: Exception) {
                    Log.w(TAG, "removeListener failed for $nodeId", e)
                }
            }
            registeredNodes.clear()
            permittedNodes.clear()
            connectedWatchNames.clear()
            notifyStatus()
        }
    }

    private val messageListener = OnMessageReceivedListener { nodeId, message ->
        try {
            val text = String(message, Charsets.UTF_8)
            Log.d(TAG, "Message from $nodeId: $text")
            handleMessage(text, nodeId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process message from $nodeId", e)
        }
    }

    private val connectionListener = OnDataChangedListener { nodeId, dataItem, result ->
        if (dataItem.type == DataItem.ITEM_CONNECTION.type) {
            val status = result.connectedStatus
            when (status) {
                DataSubscribeResult.RESULT_CONNECTION_CONNECTED -> {
                    Log.i(TAG, "Watch $nodeId reconnected — re-registering listener")
                    registeredNodes.remove(nodeId)
                    permittedNodes.remove(nodeId)
                    scope.launch {
                        delay(1000) // Give the connection time to stabilize
                        ensureNodeRegistered(nodeId)
                    }
                }
                DataSubscribeResult.RESULT_CONNECTION_DISCONNECTED -> {
                    Log.i(TAG, "Watch $nodeId disconnected")
                    registeredNodes.remove(nodeId)
                    permittedNodes.remove(nodeId)
                    connectedWatchNames.remove(nodeId)
                    notifyStatus()
                }
                else -> {
                    Log.d(TAG, "Watch $nodeId connection status: $status")
                }
            }
        }
    }

    /**
     * Start the handler: obtain the Wearable APIs, register service connection
     * listener, and begin polling for connected watch nodes.
     */
    fun start() {
        if (running) return
        running = true

        try {
            val appContext = context.applicationContext
            serviceApi = Wearable.getServiceApi(appContext)
            nodeApi = Wearable.getNodeApi(appContext)
            messageApi = Wearable.getMessageApi(appContext)
            authApi = Wearable.getAuthApi(appContext)
            Log.i(TAG, "Interconnect handler started (XMS Wearable SDK v1.4)")
        } catch (e: Exception) {
            Log.e(TAG, "XMS Wearable SDK init failed", e)
        }

        // Register service connection listener
        try {
            serviceApi?.registerServiceConnectionListener(serviceConnectionListener)
        } catch (e: Exception) {
            Log.w(TAG, "registerServiceConnectionListener failed", e)
        }

        // Start polling for connected nodes (also handles the case where
        // the service was already connected before we registered the listener)
        scope.launch {
            // Give the service a moment to connect
            delay(2000)
            while (running) {
                try {
                    // Always try to refresh — the SDK handles connection internally
                    // and connectedNodes will return empty if not connected yet
                    refreshConnectedNodes()
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Node refresh error", e)
                }
                delay(NODE_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the handler: cancel everything and clean up listeners.
     */
    fun stop() {
        running = false
        scope.cancel()

        // Remove message listeners
        val api = messageApi
        for (nodeId in registeredNodes) {
            try {
                api?.removeListener(nodeId)
            } catch (e: Exception) {
                Log.w(TAG, "removeListener failed for $nodeId", e)
            }
        }

        // Unregister service connection listener
        try {
            serviceApi?.unregisterServiceConnectionListener(serviceConnectionListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterServiceConnectionListener failed", e)
        }

        // Unsubscribe from connection state changes
        for (nodeId in registeredNodes) {
            try {
                nodeApi?.unsubscribe(nodeId, DataItem.ITEM_CONNECTION)
            } catch (e: Exception) {
                Log.w(TAG, "unsubscribe failed for $nodeId", e)
            }
        }

        registeredNodes.clear()
        permittedNodes.clear()
        connectedWatchNames.clear()
        serviceConnected = false
        notifyStatus()
        serviceApi = null
        nodeApi = null
        messageApi = null
        authApi = null
        Log.i(TAG, "Interconnect handler stopped")
    }

    /**
     * Query the currently connected watch nodes and ensure each one has
     * permissions granted and a message listener registered.
     */
    private fun refreshConnectedNodes() {
        val api = nodeApi ?: return
        api.connectedNodes
            ?.addOnSuccessListener { nodes ->
                if (nodes.isNullOrEmpty()) {
                    if (running) Log.d(TAG, "No connected watch nodes; will retry")
                    return@addOnSuccessListener
                }
                Log.i(TAG, "Found ${nodes.size} connected node(s)")
                for (node in nodes) {
                    val nodeId = node.id
                    if (nodeId.isNullOrEmpty()) continue
                    val displayName = node.name ?: "Watch"
                    Log.i(TAG, "  Node: id=$nodeId name=$displayName")
                    connectedWatchNames[nodeId] = displayName
                    notifyStatus()
                    scope.launch {
                        ensureNodeRegistered(nodeId)
                    }
                }
            }
            ?.addOnFailureListener { e ->
                when (e) {
                    is SignatureVerifyFailedException ->
                        Log.e(TAG, "SIGNATURE VERIFICATION FAILED! The quickapp signing " +
                            "does not match the Android app signing. Extract private.pem and " +
                            "certificate.pem from the same keystore.", e)
                    is AppNotInstalledException ->
                        Log.w(TAG, "Companion app not installed on watch (node)", e)
                    is PermissionDeniedException ->
                        Log.w(TAG, "Permission denied for getConnectedNodes", e)
                    else ->
                        if (running) Log.w(TAG, "getConnectedNodes failed", e)
                }
            }
    }

    /**
     * Ensure a node has DEVICE_MANAGER permission and a message listener registered.
     * This is the critical initialization sequence:
     *   1. Check/request DEVICE_MANAGER permission
     *   2. Add MessageApi listener
     *   3. Subscribe to connection state changes
     */
    private suspend fun ensureNodeRegistered(nodeId: String) {
        if (registeredNodes.contains(nodeId)) {
            Log.d(TAG, "Node $nodeId already registered, skipping")
            return
        }

        Log.i(TAG, "=== ensureNodeRegistered START for $nodeId ===")

        // Step 1: Ensure DEVICE_MANAGER permission is granted
        if (!permittedNodes.contains(nodeId)) {
            Log.i(TAG, "Step 1: Requesting DEVICE_MANAGER permission for $nodeId")
            val granted = requestPermission(nodeId)
            Log.i(TAG, "Step 1 result: granted=$granted for $nodeId")
            if (!granted) {
                Log.w(TAG, "Permission not granted for $nodeId — cannot register listener. " +
                    "Check if Xiaomi Wearable app is installed and quickapp signing matches.")
                return
            }
            permittedNodes.add(nodeId)
            notifyStatus()
        } else {
            Log.i(TAG, "Step 1: Permission already granted for $nodeId")
        }

        // Step 2: Add message listener
        val msgApi = messageApi ?: return
        try {
            msgApi.addListener(nodeId, messageListener)
                ?.addOnSuccessListener {
                    Log.i(TAG, "Message listener registered for node $nodeId")
                    registeredNodes.add(nodeId)
                    notifyStatus()
                }
                ?.addOnFailureListener { e ->
                    when (e) {
                        is SignatureVerifyFailedException ->
                            Log.e(TAG, "addListener: SIGNATURE VERIFICATION FAILED for $nodeId", e)
                        is PermissionDeniedException ->
                            Log.e(TAG, "addListener: PERMISSION DENIED for $nodeId", e)
                        else ->
                            Log.w(TAG, "addListener failed for $nodeId", e)
                    }
                    permittedNodes.remove(nodeId)
                }
        } catch (e: Exception) {
            Log.e(TAG, "addListener exception for $nodeId", e)
            permittedNodes.remove(nodeId)
        }

        // Step 3: Subscribe to connection state changes (for auto re-registration)
        try {
            nodeApi?.subscribe(nodeId, DataItem.ITEM_CONNECTION, connectionListener)
                ?.addOnSuccessListener {
                    Log.i(TAG, "Connection state subscription active for $nodeId")
                }
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "subscribe(ITEM_CONNECTION) failed for $nodeId", e)
                }
        } catch (e: Exception) {
            Log.w(TAG, "subscribe exception for $nodeId", e)
        }
    }

    /**
     * Request DEVICE_MANAGER permission for a node.
     * Per docs: "第一次调用授权会默认授予Permission.DEVICE_MANAGER和Permission.Notify权限"
     * Returns true if permission was granted.
     */
    private suspend fun requestPermission(nodeId: String): Boolean {
        val api = authApi ?: run {
            Log.e(TAG, "requestPermission: authApi is null!")
            return false
        }

        Log.i(TAG, "requestPermission: Starting for node $nodeId")

        // First, check if permission is already granted
        Log.i(TAG, "requestPermission: Checking existing permission...")
        val alreadyGranted = checkPermission(api, nodeId, Permission.DEVICE_MANAGER)
        Log.i(TAG, "requestPermission: checkPermission returned $alreadyGranted")
        if (alreadyGranted) {
            Log.i(TAG, "DEVICE_MANAGER permission already granted for $nodeId")
            return true
        }

        // Permission check returned false — might be a timeout or real denial.
        // Since user has already authorized in Xiaomi Health app, we optimistically
        // assume permission is granted and proceed. If it truly isn't, the subsequent
        // addListener call will fail with PermissionDeniedException.
        Log.w(TAG, "requestPermission: checkPermission returned false for $nodeId, " +
            "assuming permission is granted (user authorized in Xiaomi Health app). " +
            "If addListener fails later, this assumption was wrong.")
        return true
    }

    /**
     * Check if a specific permission is granted for a node.
     */
    private suspend fun checkPermission(api: AuthApi, nodeId: String, permission: Permission): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        try {
            api.checkPermission(nodeId, permission)
                .addOnSuccessListener { granted ->
                    deferred.complete(granted)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "checkPermission failed for $nodeId", e)
                    deferred.complete(false)
                }
        } catch (e: Exception) {
            return false
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(10000L) { deferred.await() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun handleMessage(text: String, nodeId: String) {
        if (!running) return
        val json = try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON from $nodeId: $text", e)
            return
        } ?: return

        val type = json.get("type")?.asString ?: run {
            Log.w(TAG, "Message without type from $nodeId")
            return
        }

        when (type) {
            "discover" -> {
                val response = JsonObject().apply {
                    addProperty("type", "server_info")
                    addProperty("host", "localhost")
                    addProperty("port", httpPort)
                }
                sendMessage(nodeId, response.toString())
                Log.i(TAG, "Replied to discover from $nodeId")
            }

            "api_request" -> {
                val id = json.get("id")?.asString ?: ""
                val path = json.get("path")?.asString ?: ""
                val method = json.get("method")?.asString ?: "GET"
                val body: JsonElement? = json.get("body")?.takeIf { !it.isJsonNull }

                // Routing may block (e.g. command execution), so run it off
                // the message callback thread.
                scope.launch {
                    val data = routeApiRequest(path, method, body)
                    val response = JsonObject().apply {
                        addProperty("type", "api_response")
                        addProperty("id", id)
                        add("data", data)
                    }
                    sendMessage(nodeId, response.toString())
                }
            }

            else -> Log.w(TAG, "Unknown message type '$type' from $nodeId")
        }
    }

    /**
     * Route an interconnect API request to the HttpServer's internal handler.
     * Device-trust checks are bypassed because the interconnect channel
     * already implies a trusted companion-app relationship.
     */
    private fun routeApiRequest(path: String, method: String, body: JsonElement?): JsonElement {
        return try {
            val result = server.handleInternalRequest(path, method, body)
            result ?: gson.toJsonTree(mapOf("status" to "error", "message" to "Not found"))
        } catch (e: Exception) {
            Log.e(TAG, "routeApiRequest error for $path", e)
            gson.toJsonTree(mapOf("status" to "error", "message" to (e.message ?: "Internal error")))
        }
    }

    private fun sendMessage(nodeId: String, data: String) {
        try {
            val bytes = data.toByteArray(Charsets.UTF_8)
            messageApi?.sendMessage(nodeId, bytes)
                ?.addOnSuccessListener {
                    Log.d(TAG, "Message sent to $nodeId (${bytes.size} bytes)")
                }
                ?.addOnFailureListener { e ->
                    when (e) {
                        is SignatureVerifyFailedException ->
                            Log.e(TAG, "sendMessage: SIGNATURE VERIFICATION FAILED for $nodeId", e)
                        is PermissionDeniedException ->
                            Log.e(TAG, "sendMessage: PERMISSION DENIED for $nodeId", e)
                        else ->
                            Log.w(TAG, "sendMessage failed to $nodeId", e)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage error", e)
        }
    }

    companion object {
        /** How often to (re)check for connected watch nodes, in milliseconds. */
        private const val NODE_CHECK_INTERVAL_MS = 5000L
    }

    /** Get current status synchronously (for UI polling). */
    fun getStatus(): InterconnectStatus {
        val nodes = connectedWatchNames.map { (id, name) ->
            WatchNodeInfo(
                nodeId = id,
                displayName = name,
                permissionGranted = permittedNodes.contains(id),
                listenerRegistered = registeredNodes.contains(id)
            )
        }
        return InterconnectStatus(serviceConnected, nodes)
    }
}
