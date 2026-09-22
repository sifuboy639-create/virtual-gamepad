package com.rico.virtualgamepad

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class GamepadImeService : InputMethodService() {
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var beaconSocket: DatagramSocket? = null
    private val heldKeys = mutableSetOf<Int>()

    companion object {
        const val TCP_PORT = 58585
        const val DISCOVERY_PORT = 58586
        const val DPAD_DEADZONE = 0.5
    }

    override fun onCreateInputView(): View = View(this)
    override fun onEvaluateInputViewShown(): Boolean = true
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        running = true
        startBeacon()
        startServer()
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { beaconSocket?.close() } catch (_: Exception) {}
        releaseAll()
        super.onDestroy()
    }

    private fun startBeacon() {
        thread(start = true) {
            try {
                val sock = DatagramSocket()
                sock.broadcast = true
                beaconSocket = sock
                val message = "GAMEPAD_TV:$TCP_PORT".toByteArray()
                while (running) {
                    try {
                        sock.send(DatagramPacket(message, message.size, InetSocketAddress("255.255.255.255", DISCOVERY_PORT)))
                    } catch (_: Exception) {}
                    Thread.sleep(1500)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startServer() {
        thread(start = true) {
            try {
                val server = ServerSocket(TCP_PORT)
                serverSocket = server
                while (running) handleClient(server.accept())
            } catch (_: Exception) {}
        }
    }

    private fun handleClient(client: Socket) {
        thread(start = true) {
            try {
                val reader = client.getInputStream().bufferedReader()
                while (running) {
                    val line = reader.readLine() ?: break
                    applyState(line)
                }
            } catch (_: Exception) {
            } finally {
                releaseAll()
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun applyState(json: String) {
        try {
            val s = JSONObject(json)
            val mask = s.optInt("b", 0)
            val lx = s.optDouble("lx", 0.0)
            val ly = s.optDouble("ly", 0.0)
            val lt = s.optDouble("lt", 0.0)
            val rt = s.optDouble("rt", 0.0)
            val wanted = mutableSetOf<Int>()

            if (mask and 0x1000 != 0) wanted += KeyEvent.KEYCODE_BUTTON_A
            if (mask and 0x2000 != 0) wanted += KeyEvent.KEYCODE_BUTTON_B
            if (mask and 0x4000 != 0) wanted += KeyEvent.KEYCODE_BUTTON_X
            if (mask and 0x8000 != 0) wanted += KeyEvent.KEYCODE_BUTTON_Y
            if (mask and 0x0100 != 0) wanted += KeyEvent.KEYCODE_BUTTON_L1
            if (mask and 0x0200 != 0) wanted += KeyEvent.KEYCODE_BUTTON_R1
            if (mask and 0x0010 != 0) wanted += KeyEvent.KEYCODE_BUTTON_START
            if (mask and 0x0020 != 0) wanted += KeyEvent.KEYCODE_BUTTON_SELECT
            if (mask and 0x0040 != 0) wanted += KeyEvent.KEYCODE_BUTTON_THUMBL
            if (mask and 0x0080 != 0) wanted += KeyEvent.KEYCODE_BUTTON_THUMBR
            if (lt > 0.5) wanted += KeyEvent.KEYCODE_BUTTON_L2
            if (rt > 0.5) wanted += KeyEvent.KEYCODE_BUTTON_R2

            var up = mask and 0x0001 != 0
            var down = mask and 0x0002 != 0
            var left = mask and 0x0004 != 0
            var right = mask and 0x0008 != 0
            if (ly > DPAD_DEADZONE) up = true
            if (ly < -DPAD_DEADZONE) down = true
            if (lx < -DPAD_DEADZONE) left = true
            if (lx > DPAD_DEADZONE) right = true
            if (up) wanted += KeyEvent.KEYCODE_DPAD_UP
            if (down) wanted += KeyEvent.KEYCODE_DPAD_DOWN
            if (left) wanted += KeyEvent.KEYCODE_DPAD_LEFT
            if (right) wanted += KeyEvent.KEYCODE_DPAD_RIGHT

            syncKeys(wanted)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun syncKeys(wanted: Set<Int>) {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        for (code in wanted) {
            if (heldKeys.add(code)) {
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            }
        }
        for (code in heldKeys.filter { it !in wanted }) {
            heldKeys.remove(code)
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

    @Synchronized
    private fun releaseAll() {
        syncKeys(emptySet())
    }
}
