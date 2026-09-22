package com.rico.virtualgamepad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    @Volatile private var fastButtons: Int = 0
    @Volatile private var fastLx = 0.0
    @Volatile private var fastLy = 0.0
    @Volatile private var fastRx = 0.0
    @Volatile private var fastRy = 0.0
    @Volatile private var fastLt = 0.0
    @Volatile private var fastRt = 0.0
    private val report = ByteArray(8)
    private val reportLock = Any()
    private val hidSender: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        configureWebView()
        hidSender.scheduleAtFixedRate({ sendLatestHidReport() }, 0, 8, TimeUnit.MILLISECONDS)
        AlertDialog.Builder(this)
            .setTitle("Choose mode")
            .setItems(arrayOf("Bluetooth (direct gamepad)", "Android TV over Wi-Fi")) { _, which ->
                if (which == 0) startBluetoothMode() else startAndroidTvMode()
            }
            .setCancelable(false)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(false)
        webView.settings.offscreenPreRaster = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.webViewClient = WebViewClient()
    }

    private fun loadController() {
        webView.loadUrl("file:///android_asset/controller.html")
    }

    private fun startBluetoothMode() {
        webView.addJavascriptInterface(BtBridgeToJs(), "AndroidBT")
        loadController()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val advertise = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!connect || !advertise) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 100)
                return
            }
        }
        setupBluetooth()
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupBluetooth()
        } else if (requestCode == 100) {
            notifyStatus("Bluetooth permission needed", "")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            notifyStatus("Turn Bluetooth on, then reopen the app", "")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        adapter.getProfileProxy(applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApp()
            }
            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
                hostDevice = null
                notifyStatus("Disconnected", "")
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "vz-hub", "Phone touch controller", "vz-hub",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD, GamepadHid.DESCRIPTOR
        )
        hidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                notifyStatus(if (registered) "Ready — pair from TV/PC Bluetooth settings" else "Bluetooth gamepad registration failed", "")
            }
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                hostDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> notifyStatus("Connected", "connected")
                    BluetoothProfile.STATE_CONNECTING -> notifyStatus("Connecting…", "connecting")
                    else -> notifyStatus("Disconnected", "")
                }
            }
            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                hidDevice?.replyReport(device, type, id, report)
            }
        })
    }

    inner class BtBridgeToJs {
        @JavascriptInterface
        fun onFastState(lx: Double, ly: Double, rx: Double, ry: Double, lt: Double, rt: Double, buttons: Int) {
            fastLx = lx
            fastLy = ly
            fastRx = rx
            fastRy = ry
            fastLt = lt
            fastRt = rt
            fastButtons = buttons
        }

        @JavascriptInterface
        fun onState(json: String) {
            try {
                val s = JSONObject(json)
                val mask = s.optInt("b", 0)
                var b0 = 0
                var b1 = 0
                if (mask and 0x1000 != 0) b0 = b0 or 0x01
                if (mask and 0x2000 != 0) b0 = b0 or 0x02
                if (mask and 0x4000 != 0) b0 = b0 or 0x04
                if (mask and 0x8000 != 0) b0 = b0 or 0x08
                if (mask and 0x0100 != 0) b0 = b0 or 0x10
                if (mask and 0x0200 != 0) b0 = b0 or 0x20
                if (mask and 0x0020 != 0) b0 = b0 or 0x40
                if (mask and 0x0010 != 0) b0 = b0 or 0x80
                if (mask and 0x0040 != 0) b1 = b1 or 0x01
                if (mask and 0x0080 != 0) b1 = b1 or 0x02
                if (mask and 0x0001 != 0) b1 = b1 or 0x04
                if (mask and 0x0002 != 0) b1 = b1 or 0x08
                if (mask and 0x0004 != 0) b1 = b1 or 0x10
                if (mask and 0x0008 != 0) b1 = b1 or 0x20
                synchronized(reportLock) {
                    report[0] = b0.toByte()
                    report[1] = b1.toByte()
                    report[2] = axisByte(s.optDouble("lx", 0.0))
                    report[3] = axisByte(s.optDouble("ly", 0.0))
                    report[4] = axisByte(s.optDouble("rx", 0.0))
                    report[5] = axisByte(s.optDouble("ry", 0.0))
                    report[6] = triggerByte(s.optDouble("lt", 0.0))
                    report[7] = triggerByte(s.optDouble("rt", 0.0))
                }
            } catch (_: Exception) {}
        }
        private fun axisByte(v: Double): Byte = (((v.coerceIn(-1.0, 1.0) + 1.0) / 2.0 * 255.0).toInt()).coerceIn(0, 255).toByte()
        private fun triggerByte(v: Double): Byte = ((v.coerceIn(0.0, 1.0) * 255.0).toInt()).coerceIn(0, 255).toByte()
    }

    private fun startAndroidTvMode() {
        webView.addJavascriptInterface(TvBridgeToJs(), "AndroidTV")
        loadController()
    }

    inner class TvBridgeToJs {
        @Volatile private var socket: Socket? = null
        @Volatile private var writer: java.io.BufferedWriter? = null
        @Volatile private var discoverySocket: DatagramSocket? = null

        init {
            notifyStatus("Looking for the TV on Wi-Fi…", "connecting")
            startDiscovery()
        }

        private fun startDiscovery() {
            thread(start = true) {
                try {
                    val ds = DatagramSocket(null)
                    ds.reuseAddress = true
                    ds.broadcast = true
                    ds.bind(InetSocketAddress(GamepadImeService.DISCOVERY_PORT))
                    discoverySocket = ds
                    val buffer = ByteArray(256)
                    while (socket == null) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        ds.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith("GAMEPAD_TV:")) {
                            val port = text.substringAfter(":").trim().toIntOrNull() ?: continue
                            val ip = packet.address?.hostAddress ?: continue
                            connectTo(ip, port)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    discoverySocket?.close()
                }
            }
        }

        private fun connectTo(ip: String, port: Int) {
            try {
                val s = Socket(ip, port)
                socket = s
                writer = s.outputStream.bufferedWriter()
                notifyStatus("Connected to TV", "connected")
            } catch (_: Exception) {
                notifyStatus("Found TV but connection failed", "")
            }
        }

        @JavascriptInterface
        fun onState(json: String) {
            val w = writer ?: return
            try {
                w.write(json)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
                socket = null
                writer = null
                notifyStatus("Disconnected", "")
            }
        }
    }

    private fun axisByte(v: Double): Byte =
        (((v.coerceIn(-1.0, 1.0) + 1.0) / 2.0 * 255.0).toInt()).coerceIn(0, 255).toByte()

    private fun triggerByte(v: Double): Byte =
        ((v.coerceIn(0.0, 1.0) * 255.0).toInt()).coerceIn(0, 255).toByte()

    @SuppressLint("MissingPermission")
    private fun sendLatestHidReport() {
        val device = hostDevice ?: return
        val hid = hidDevice ?: return
        val mask = fastButtons
        var b0 = 0
        var b1 = 0
        if (mask and 0x1000 != 0) b0 = b0 or 0x01
        if (mask and 0x2000 != 0) b0 = b0 or 0x02
        if (mask and 0x4000 != 0) b0 = b0 or 0x04
        if (mask and 0x8000 != 0) b0 = b0 or 0x08
        if (mask and 0x0100 != 0) b0 = b0 or 0x10
        if (mask and 0x0200 != 0) b0 = b0 or 0x20
        if (mask and 0x0020 != 0) b0 = b0 or 0x40
        if (mask and 0x0010 != 0) b0 = b0 or 0x80
        if (mask and 0x0040 != 0) b1 = b1 or 0x01
        if (mask and 0x0080 != 0) b1 = b1 or 0x02
        if (mask and 0x0001 != 0) b1 = b1 or 0x04
        if (mask and 0x0002 != 0) b1 = b1 or 0x08
        if (mask and 0x0004 != 0) b1 = b1 or 0x10
        if (mask and 0x0008 != 0) b1 = b1 or 0x20
        val snapshot = byteArrayOf(
            b0.toByte(), b1.toByte(),
            axisByte(fastLx), axisByte(fastLy),
            axisByte(fastRx), axisByte(fastRy),
            triggerByte(fastLt), triggerByte(fastRt)
        )
        try { hid.sendReport(device, REPORT_ID, snapshot) } catch (_: Exception) {}
    }

    private fun notifyStatus(text: String, cls: String) {
        val jsText = JSONObject.quote(text)
        val jsCls = JSONObject.quote(cls)
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript("window.__onNativeStatus && window.__onNativeStatus($jsText,$jsCls);", null)
        }
    }

    override fun onDestroy() {
        try { hidSender.shutdownNow() } catch (_: Exception) {}
        try { hidDevice?.unregisterApp() } catch (_: Exception) {}
        try { hidDevice?.let { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object { private const val REPORT_ID = 1 }
}

object GamepadHid {
    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05,0x01,0x09,0x05,0xA1.toByte(),0x01,0x85.toByte(),0x01,
        0x05,0x09,0x19,0x01,0x29,0x10,0x15,0x00,0x25,0x01,0x75,0x01,0x95.toByte(),0x10,0x81.toByte(),0x02,
        0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x33,0x09,0x34,0x09,0x32,0x09,0x35,
        0x15,0x00,0x26,0xFF.toByte(),0x00,0x75,0x08,0x95.toByte(),0x06,0x81.toByte(),0x02,0xC0.toByte()
    )
}
