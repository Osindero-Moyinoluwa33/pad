package com.pswebpad.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var tvHotspotUrl: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvImeStatus: TextView
    private lateinit var tvP1Status: TextView
    private lateinit var tvP1Detail: TextView
    private lateinit var tvP2Status: TextView
    private lateinit var tvP2Detail: TextView
    private lateinit var btnCopyUrl: Button
    private lateinit var btnRefreshIp: Button
    private lateinit var btnEnableIme: Button
    private lateinit var btnSelectIme: Button

    private var currentUrl: String = "http://192.168.43.1:8080"

    private val bridgeListener: (BridgeSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            renderSnapshot(snapshot)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHotspotUrl = findViewById(R.id.tvHotspotUrl)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvImeStatus = findViewById(R.id.tvImeStatus)
        tvP1Status = findViewById(R.id.tvP1Status)
        tvP1Detail = findViewById(R.id.tvP1Detail)
        tvP2Status = findViewById(R.id.tvP2Status)
        tvP2Detail = findViewById(R.id.tvP2Detail)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        btnRefreshIp = findViewById(R.id.btnRefreshIp)
        btnEnableIme = findViewById(R.id.btnEnableIme)
        btnSelectIme = findViewById(R.id.btnSelectIme)

        btnCopyUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PS WebPad URL", currentUrl))
            Toast.makeText(this, "Copied $currentUrl", Toast.LENGTH_SHORT).show()
        }

        btnRefreshIp.setOnClickListener {
            refreshNetworkUrl()
            refreshImeStatus()
        }

        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelectIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        refreshNetworkUrl()
        refreshImeStatus()
    }

    override fun onStart() {
        super.onStart()
        HostBridgeState.addListener(bridgeListener)
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkUrl()
        refreshImeStatus()
    }

    override fun onStop() {
        HostBridgeState.removeListener(bridgeListener)
        super.onStop()
    }

    private fun refreshNetworkUrl() {
        val ip = findLocalIpv4Address() ?: "192.168.43.1"
        currentUrl = "http://$ip:${GamepadImeService.HTTP_PORT}"
        tvHotspotUrl.text = currentUrl
    }

    private fun refreshImeStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val expectedComponent = ComponentName(this, GamepadImeService::class.java)
        val expectedId = expectedComponent.flattenToShortString()
        val expectedFullId = expectedComponent.flattenToString()

        val isEnabled = imm.enabledInputMethodList.any {
            it.id == expectedId || it.id == expectedFullId
        }
        val currentDefaultIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val isSelected = currentDefaultIme == expectedId || currentDefaultIme == expectedFullId

        tvImeStatus.text = when {
            isSelected -> "PS WebPad Bridge is enabled and selected as active IME"
            isEnabled -> "PS WebPad Bridge is enabled — tap \"2. Select Bridge IME\" to activate"
            else -> "PS WebPad Bridge is disabled — tap \"1. Enable Bridge IME\" first"
        }
        tvImeStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    isSelected -> R.color.status_connected
                    isEnabled -> R.color.status_waiting
                    else -> R.color.text_secondary
                }
            )
        )
    }

    private fun renderSnapshot(snapshot: BridgeSnapshot) {
        val stateLabel = if (snapshot.serviceRunning) "Bridge Running" else "Bridge Idle (Select IME)"
        tvServerStatus.text = "HTTP :${snapshot.httpPort} · WS :${snapshot.wsPort} · $stateLabel"

        if (snapshot.player1Connected) {
            val pingLabel = snapshot.player1PingMs?.let { "${it} ms · ${stabilityLabel(it)}" } ?: "measuring..."
            tvP1Status.text = "Connected"
            tvP1Status.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            tvP1Detail.text = "${snapshot.player1Address ?: "local"} · $pingLabel"
        } else {
            tvP1Status.text = "Waiting for phone"
            tvP1Status.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
            tvP1Detail.text = "DPAD + BUTTON_A..SELECT"
        }

        if (snapshot.player2Connected) {
            val pingLabel = snapshot.player2PingMs?.let { "${it} ms · ${stabilityLabel(it)}" } ?: "measuring..."
            tvP2Status.text = "Connected"
            tvP2Status.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            tvP2Detail.text = "${snapshot.player2Address ?: "local"} · $pingLabel"
        } else {
            tvP2Status.text = "Waiting for phone"
            tvP2Status.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
            tvP2Detail.text = "BUTTON_1 .. BUTTON_14"
        }
    }

    private fun stabilityLabel(pingMs: Long): String {
        return when {
            pingMs < 15L -> "Excellent"
            pingMs < 40L -> "Good"
            pingMs < 80L -> "Fair"
            else -> "Unstable"
        }
    }

    private fun findLocalIpv4Address(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            ip.startsWith("192.168.") && (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) -> 0
                            ip.startsWith("192.168.") -> 1
                            name.contains("wlan") || name.contains("ap") || name.contains("arc") -> 2
                            addr.isSiteLocalAddress -> 3
                            else -> 4
                        }
                        candidates.add(priority to ip)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
