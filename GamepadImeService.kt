package com.pswebpad.host

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.hypot

class GamepadImeService : InputMethodService() {

    companion object {
        const val HTTP_PORT = 8080
        const val WS_PORT = 8081
        private const val STICK_DEADZONE = 0.22

        private val PLAYER_1_KEYMAP: Map<String, Int> = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "CROSS" to KeyEvent.KEYCODE_BUTTON_A,
            "CIRCLE" to KeyEvent.KEYCODE_BUTTON_B,
            "SQUARE" to KeyEvent.KEYCODE_BUTTON_X,
            "TRIANGLE" to KeyEvent.KEYCODE_BUTTON_Y,
            "L1" to KeyEvent.KEYCODE_BUTTON_L1,
            "L2" to KeyEvent.KEYCODE_BUTTON_L2,
            "R1" to KeyEvent.KEYCODE_BUTTON_R1,
            "R2" to KeyEvent.KEYCODE_BUTTON_R2,
            "START" to KeyEvent.KEYCODE_BUTTON_START,
            "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT
        )

        private val PLAYER_2_KEYMAP: Map<String, Int> = mapOf(
            "UP" to KeyEvent.KEYCODE_BUTTON_1,
            "DOWN" to KeyEvent.KEYCODE_BUTTON_2,
            "LEFT" to KeyEvent.KEYCODE_BUTTON_3,
            "RIGHT" to KeyEvent.KEYCODE_BUTTON_4,
            "CROSS" to KeyEvent.KEYCODE_BUTTON_5,
            "CIRCLE" to KeyEvent.KEYCODE_BUTTON_6,
            "SQUARE" to KeyEvent.KEYCODE_BUTTON_7,
            "TRIANGLE" to KeyEvent.KEYCODE_BUTTON_8,
            "L1" to KeyEvent.KEYCODE_BUTTON_9,
            "L2" to KeyEvent.KEYCODE_BUTTON_10,
            "R1" to KeyEvent.KEYCODE_BUTTON_11,
            "R2" to KeyEvent.KEYCODE_BUTTON_12,
            "START" to KeyEvent.KEYCODE_BUTTON_13,
            "SELECT" to KeyEvent.KEYCODE_BUTTON_14
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val slotLock = Any()

    private var player1Conn: WebSocket? = null
    private var player2Conn: WebSocket? = null

    private val activeStickDirs = mutableMapOf(
        1 to emptySet<String>(),
        2 to emptySet<String>()
    )
    private val activeButtons = mutableMapOf(
        1 to mutableSetOf<String>(),
        2 to mutableSetOf<String>()
    )

    private var httpExecutor: ExecutorService? = null
    private var httpServerSocket: ServerSocket? = null
    @Volatile
    private var httpRunning = false

    private var wsServer: GamepadWebSocketServer? = null
    private var pingScheduler: ScheduledExecutorService? = null
    private var padHtmlBytes: ByteArray = ByteArray(0)

    override fun onCreate() {
        super.onCreate()
        padHtmlBytes = loadPadHtml()
        startHttpServer()
        startWebSocketServer()
        startPingScheduler()
        HostBridgeState.update {
            it.copy(
                serviceRunning = true,
                httpPort = HTTP_PORT,
                wsPort = WS_PORT,
                lastEventSummary = "Bridge active on HTTP :$HTTP_PORT / WS :$WS_PORT"
            )
        }
    }

    override fun onDestroy() {
        stopPingScheduler()
        stopHttpServer()
        stopWebSocketServer()
        resetPlayerInputs(1)
        resetPlayerInputs(2)
        HostBridgeState.update {
            BridgeSnapshot(
                serviceRunning = false,
                lastEventSummary = "Bridge stopped"
            )
        }
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // Prevent an on-screen software keyboard from covering foreground emulators (e.g. ARMSX2).
        return false
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    private fun loadPadHtml(): ByteArray {
        return try {
            assets.open("pad.html").use { it.readBytes() }
        } catch (e: Exception) {
            "<!doctype html><html><body style='background:#000;color:#fff;font-family:sans-serif'><h2>Missing pad.html asset</h2></body></html>"
                .toByteArray(Charsets.UTF_8)
        }
    }

    private fun startHttpServer() {
        httpRunning = true
        val executor = Executors.newCachedThreadPool()
        httpExecutor = executor
        executor.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(HTTP_PORT))
                }
                httpServerSocket = server
                while (httpRunning && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    executor.execute { handleHttpClient(client) }
                }
            } catch (_: Exception) {
                HostBridgeState.update {
                    it.copy(lastEventSummary = "HTTP server failed to bind port $HTTP_PORT")
                }
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                val rawPath = if (parts.size >= 2) parts[1].substringBefore("?") else "/"
                val out = client.getOutputStream()

                when (rawPath) {
                    "/", "/pad.html", "/index.html" -> {
                        val body = if (padHtmlBytes.isNotEmpty()) padHtmlBytes else loadPadHtml()
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        out.write(headers)
                        out.write(body)
                        out.flush()
                    }
                    "/health" -> {
                        val body = """{"status":"ok","wsPort":$WS_PORT}""".toByteArray(Charsets.UTF_8)
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        out.write(headers)
                        out.write(body)
                        out.flush()
                    }
                    else -> {
                        val body = "Not Found".toByteArray(Charsets.UTF_8)
                        val headers = (
                            "HTTP/1.1 404 Not Found\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        out.write(headers)
                        out.write(body)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Ignore transient client socket read/write errors
            }
        }
    }

    private fun stopHttpServer() {
        httpRunning = false
        try {
            httpServerSocket?.close()
        } catch (_: Exception) {
        }
        httpServerSocket = null
        httpExecutor?.shutdownNow()
        httpExecutor = null
    }

    private fun startWebSocketServer() {
        try {
            val server = GamepadWebSocketServer(InetSocketAddress(WS_PORT)).apply {
                isReuseAddr = true
                connectionLostTimeout = 15
                start()
            }
            wsServer = server
        } catch (_: Exception) {
            HostBridgeState.update {
                it.copy(lastEventSummary = "WebSocket server failed to bind port $WS_PORT")
            }
        }
    }

    private fun stopWebSocketServer() {
        try {
            wsServer?.stop(500)
        } catch (_: Exception) {
        }
        wsServer = null
    }

    private fun startPingScheduler() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        pingScheduler = scheduler
        scheduler.scheduleAtFixedRate({
            val ts = SystemClock.elapsedRealtime()
            val payload = """{"type":"ping","ts":$ts}"""
            val p1: WebSocket?
            val p2: WebSocket?
            synchronized(slotLock) {
                p1 = player1Conn
                p2 = player2Conn
            }
            try {
                if (p1 != null && p1.isOpen) p1.send(payload)
            } catch (_: Exception) {
            }
            try {
                if (p2 != null && p2.isOpen) p2.send(payload)
            } catch (_: Exception) {
            }
        }, 1000L, 2000L, TimeUnit.MILLISECONDS)
    }

    private fun stopPingScheduler() {
        pingScheduler?.shutdownNow()
        pingScheduler = null
    }

    private inner class GamepadWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onStart() {
            // Ready to accept P1 and P2 connections
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            val assignedPlayer = synchronized(slotLock) {
                when {
                    player1Conn == null -> {
                        player1Conn = conn
                        1
                    }
                    player2Conn == null -> {
                        player2Conn = conn
                        2
                    }
                    else -> null
                }
            }

            if (assignedPlayer == null) {
                conn.send("""{"type":"full"}""")
                conn.close(1008, "Both player slots are occupied")
                return
            }

            val remoteIp = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
            conn.send("""{"type":"assigned","player":$assignedPlayer}""")

            HostBridgeState.update { state ->
                if (assignedPlayer == 1) {
                    state.copy(
                        player1Connected = true,
                        player1Address = remoteIp,
                        lastEventSummary = "Player 1 connected ($remoteIp)"
                    )
                } else {
                    state.copy(
                        player2Connected = true,
                        player2Address = remoteIp,
                        lastEventSummary = "Player 2 connected ($remoteIp)"
                    )
                }
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            releaseConnection(conn)
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            if (conn != null) {
                releaseConnection(conn)
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val player = synchronized(slotLock) {
                when (conn) {
                    player1Conn -> 1
                    player2Conn -> 2
                    else -> return
                }
            }
            handleControllerMessage(player, message)
        }
    }

    private fun releaseConnection(conn: WebSocket) {
        val freedPlayer = synchronized(slotLock) {
            when (conn) {
                player1Conn -> {
                    player1Conn = null
                    1
                }
                player2Conn -> {
                    player2Conn = null
                    2
                }
                else -> null
            }
        } ?: return

        resetPlayerInputs(freedPlayer)
        HostBridgeState.update { state ->
            if (freedPlayer == 1) {
                state.copy(
                    player1Connected = false,
                    player1Address = null,
                    player1PingMs = null,
                    lastEventSummary = "Player 1 disconnected (inputs reset)"
                )
            } else {
                state.copy(
                    player2Connected = false,
                    player2Address = null,
                    player2PingMs = null,
                    lastEventSummary = "Player 2 disconnected (inputs reset)"
                )
            }
        }
    }

    private fun handleControllerMessage(player: Int, rawJson: String) {
        val json = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return
        }

        when (json.optString("type")) {
            "pong" -> {
                val sentTs = json.optLong("ts", -1L)
                if (sentTs >= 0L) {
                    val rttMs = (SystemClock.elapsedRealtime() - sentTs).coerceAtLeast(0L)
                    HostBridgeState.update { state ->
                        if (player == 1) {
                            state.copy(player1PingMs = rttMs)
                        } else {
                            state.copy(player2PingMs = rttMs)
                        }
                    }
                }
            }
            "btn" -> {
                val button = json.optString("button").uppercase()
                val pressed = json.optBoolean("pressed", false)
                handleButtonInput(player, button, pressed)
            }
            "stick" -> {
                val x = json.optDouble("x", 0.0).coerceIn(-1.0, 1.0)
                val y = json.optDouble("y", 0.0).coerceIn(-1.0, 1.0)
                handleStickInput(player, x, y)
            }
        }
    }

    private fun keyMapFor(player: Int): Map<String, Int> {
        return if (player == 1) PLAYER_1_KEYMAP else PLAYER_2_KEYMAP
    }

    private fun handleButtonInput(player: Int, button: String, pressed: Boolean) {
        val keyCode = keyMapFor(player)[button] ?: return
        synchronized(slotLock) {
            val held = activeButtons.getOrPut(player) { mutableSetOf() }
            val changed = if (pressed) held.add(button) else held.remove(button)
            if (!changed) return
        }
        postKeyEvent(keyCode, pressed)
        HostBridgeState.update {
            it.copy(lastEventSummary = "P$player $button ${if (pressed) "DOWN" else "UP"}")
        }
    }

    private fun handleStickInput(player: Int, x: Double, y: Double) {
        val nextDirs = computeOctantDirections(x, y)
        val toRelease: Set<String>
        val toPress: Set<String>

        synchronized(slotLock) {
            val prevDirs = activeStickDirs[player] ?: emptySet()
            if (prevDirs == nextDirs) return
            toRelease = prevDirs - nextDirs
            toPress = nextDirs - prevDirs
            activeStickDirs[player] = nextDirs
        }

        val map = keyMapFor(player)
        for (dir in toRelease) {
            map[dir]?.let { postKeyEvent(it, false) }
        }
        for (dir in toPress) {
            map[dir]?.let { postKeyEvent(it, true) }
        }
    }

    private fun computeOctantDirections(x: Double, y: Double): Set<String> {
        val magnitude = hypot(x, y)
        if (magnitude < STICK_DEADZONE) {
            return emptySet()
        }

        // Convert (x, y) into 8-way octant sectors:
        // 0° = RIGHT, 45° = DOWN+RIGHT, 90° = DOWN, 135° = DOWN+LEFT,
        // 180° = LEFT, -135° = UP+LEFT, -90° = UP, -45° = UP+RIGHT
        val angleDeg = Math.toDegrees(atan2(y, x))
        val sector = (((angleDeg + 360.0 + 22.5) % 360.0) / 45.0).toInt() % 8

        return when (sector) {
            0 -> setOf("RIGHT")
            1 -> setOf("DOWN", "RIGHT")
            2 -> setOf("DOWN")
            3 -> setOf("DOWN", "LEFT")
            4 -> setOf("LEFT")
            5 -> setOf("UP", "LEFT")
            6 -> setOf("UP")
            7 -> setOf("UP", "RIGHT")
            else -> emptySet()
        }
    }

    private fun resetPlayerInputs(player: Int) {
        val map = keyMapFor(player)
        val releasedStick: Set<String>
        val releasedButtons: List<String>

        synchronized(slotLock) {
            releasedStick = activeStickDirs[player] ?: emptySet()
            activeStickDirs[player] = emptySet()

            val held = activeButtons[player] ?: mutableSetOf()
            releasedButtons = held.toList()
            held.clear()
        }

        for (dir in releasedStick) {
            map[dir]?.let { postKeyEvent(it, false) }
        }
        for (btn in releasedButtons) {
            map[btn]?.let { postKeyEvent(it, false) }
        }
    }

    private fun postKeyEvent(keyCode: Int, isDown: Boolean) {
        mainHandler.post {
            val ic = currentInputConnection ?: return@post
            val now = SystemClock.uptimeMillis()
            val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val event = KeyEvent(
                now,
                now,
                action,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_GAMEPAD
            )
            ic.sendKeyEvent(event)
        }
    }
}
