package com.pswebpad.host

import java.util.concurrent.CopyOnWriteArraySet

data class BridgeSnapshot(
    val serviceRunning: Boolean = false,
    val httpPort: Int = 8080,
    val wsPort: Int = 8081,
    val player1Connected: Boolean = false,
    val player1Address: String? = null,
    val player1PingMs: Long? = null,
    val player2Connected: Boolean = false,
    val player2Address: String? = null,
    val player2PingMs: Long? = null,
    val lastEventSummary: String = "Waiting for virtual gamepad input"
)

object HostBridgeState {
    @Volatile
    var snapshot: BridgeSnapshot = BridgeSnapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(BridgeSnapshot) -> Unit>()

    fun addListener(listener: (BridgeSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (BridgeSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun update(transform: (BridgeSnapshot) -> BridgeSnapshot) {
        val next = transform(snapshot)
        snapshot = next
        for (listener in listeners) {
            listener(next)
        }
    }
}
