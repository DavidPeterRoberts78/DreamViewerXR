package com.dreamshell.vr.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Talks to the Daydream View motion controller over Bluetooth LE.
 *
 *   service        0000fe55-0000-1000-8000-00805f9b34fb
 *   characteristic 00000001-1000-1000-8000-00805f9b34fb  (notify, 20 bytes)
 *
 * The controller only streams over an encrypted link, so the phone must be bonded
 * (paired) with it before notifications are turned on. daydream2hid does the same
 * by raising the link to security level 2 before subscribing. Without a bond the
 * subscription is accepted but no packets ever arrive.
 *
 * Sequence: connect -> bond (system "Pair?" prompt the first time) -> discover services
 * -> write the notification descriptor and wait for its result -> wait for the first
 * packet. Each step is checked, retried, and logged to [log] for the start screen.
 *
 * A process-wide singleton so the link survives moving between the launcher and the
 * VR screen. Listener callbacks arrive on the main thread.
 */
@SuppressLint("MissingPermission") // callers check permissions via hasPermissions()
object DaydreamController {

    enum class Status {
        DISCONNECTED, BLUETOOTH_OFF, SCANNING, CONNECTING, PAIRING, SUBSCRIBING,
        /** Subscribed, waiting for the first packet. */
        CONNECTED,
        /** Packets are arriving. */
        STREAMING,
        NOT_FOUND,
    }

    fun interface StateListener { fun onState(state: ControllerState) }
    fun interface StatusListener { fun onStatus(status: Status) }
    fun interface LogListener { fun onLog(lines: List<String>) }

    private const val TAG = "DaydreamController"
    private val SERVICE_UUID: UUID = UUID.fromString("0000fe55-0000-1000-8000-00805f9b34fb")
    private val DATA_UUID: UUID = UUID.fromString("00000001-1000-1000-8000-00805f9b34fb")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val SCAN_TIMEOUT_MS = 20_000L
    private const val FIRST_PACKET_TIMEOUT_MS = 2_500L
    private const val STALL_TIMEOUT_MS = 3_000L
    private const val MAX_SUBSCRIBE_ATTEMPTS = 4

    // GATT status codes Android reports when the link isn't encrypted/authenticated.
    private val AUTH_ERRORS = setOf(5, 8, 15, 137)

    private val main = Handler(Looper.getMainLooper())
    private val stateListeners = LinkedHashSet<StateListener>()
    private val statusListeners = LinkedHashSet<StatusListener>()
    private val logListeners = LinkedHashSet<LogListener>()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    private var appContext: Context? = null
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var wantConnected = false
    private var bondReceiverRegistered = false
    private var subscribeAttempts = 0
    private var servicesDiscovered = false

    @Volatile var status: Status = Status.DISCONNECTED
        private set
    @Volatile var latest: ControllerState = ControllerState()
        private set
    @Volatile var lastPacketAt = 0L
        private set
    @Volatile var packetsPerSecond = 0
        private set
    @Volatile var totalPackets = 0L
        private set
    private var packetsThisSecond = 0

    val requiredPermissions = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun hasPermissions(ctx: Context) = requiredPermissions.all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** True while packets have arrived in the last second. */
    val isStreaming: Boolean get() = SystemClock.uptimeMillis() - lastPacketAt < 1000

    fun addStateListener(l: StateListener) { stateListeners += l }
    fun removeStateListener(l: StateListener) { stateListeners -= l }
    fun addStatusListener(l: StatusListener) { statusListeners += l; l.onStatus(status) }
    fun removeStatusListener(l: StatusListener) { statusListeners -= l }
    fun addLogListener(l: LogListener) { logListeners += l; l.onLog(logLines.toList()) }
    fun removeLogListener(l: LogListener) { logListeners -= l }
    val log: List<String> get() = logLines.toList()

    // ---- Public control -------------------------------------------------------------------

    /** Find the controller (paired or advertising) and connect. Wake it with a Home-button press. */
    fun connect(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!hasPermissions(ctx)) { log("Bluetooth permission not granted"); return }
        if (status != Status.DISCONNECTED && status != Status.NOT_FOUND && status != Status.BLUETOOTH_OFF) return
        wantConnected = true
        registerBondReceiver(ctx)

        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth is off"); setStatus(Status.BLUETOOTH_OFF); return }

        adapter.bondedDevices?.firstOrNull { isDaydream(it.name) }?.let {
            log("Using paired controller ${it.address}")
            connectDevice(it)
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run { setStatus(Status.BLUETOOTH_OFF); return }
        scanning = true
        setStatus(Status.SCANNING)
        log("Scanning; hold Home on the controller")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun disconnect() {
        wantConnected = false
        stopScan()
        teardownGatt()
        setStatus(Status.DISCONNECTED)
        log("Disconnected by user")
    }

    /**
     * Forget the pairing and pair again from scratch. Returns false if Android wouldn't let
     * the app remove the old pairing; the user then has to tap "Forget" in Bluetooth settings.
     */
    fun repair(context: Context): Boolean {
        val d = device ?: context.applicationContext.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bondedDevices?.firstOrNull { isDaydream(it.name) }
        disconnect()
        if (d == null || d.bondState == BluetoothDevice.BOND_NONE) {
            log("No existing pairing; connecting fresh")
            main.postDelayed({ connect(context) }, 500)
            return true
        }
        return try {
            // Hidden API; restricted on recent Android, so this may be refused.
            val ok = d.javaClass.getMethod("removeBond").invoke(d) as? Boolean ?: false
            if (ok) {
                log("Removed old pairing; pairing again")
                main.postDelayed({ connect(context) }, 1500)
            } else log("Android refused to remove the pairing")
            ok
        } catch (e: Exception) {
            log("Can't remove the pairing from the app: forget \"Daydream controller\" in Bluetooth settings")
            false
        }
    }

    // ---- Scanning -------------------------------------------------------------------------

    private val scanTimeout = Runnable {
        if (scanning) { stopScan(); log("No controller found"); setStatus(Status.NOT_FOUND) }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanTimeout)
        val adapter = appContext?.getSystemService(BluetoothManager::class.java)?.adapter
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { Log.w(TAG, "stopScan", e) }
    }

    private fun isDaydream(name: String?) = name?.contains("Daydream", ignoreCase = true) == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val hasService = record?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
            if (hasService || isDaydream(record?.deviceName) || isDaydream(result.device.name)) {
                main.post {
                    if (scanning) {
                        stopScan()
                        log("Found ${result.device.address}")
                        connectDevice(result.device)
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; log("Scan failed ($errorCode)"); setStatus(Status.NOT_FOUND) }
        }
    }

    // ---- Connection state machine (all on main thread) -----------------------------------

    private fun connectDevice(d: BluetoothDevice) {
        val ctx = appContext ?: return
        device = d
        subscribeAttempts = 0
        servicesDiscovered = false
        setStatus(Status.CONNECTING)
        log("Connecting (bond state: ${bondName(d.bondState)})")
        gatt?.close()
        gatt = d.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun onLinkUp(g: BluetoothGatt) {
        val d = g.device
        when (d.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                log("Link up, already paired")
                // Give the stack a moment to restore encryption before discovery.
                main.postDelayed({ if (gatt === g) discover(g) }, 400)
            }
            BluetoothDevice.BOND_BONDING -> {
                setStatus(Status.PAIRING)
                log("Pairing in progress…")
            }
            else -> {
                setStatus(Status.PAIRING)
                log("Link up, not paired: requesting pairing (accept the prompt on the phone)")
                if (!d.createBond()) {
                    log("createBond() refused; trying to subscribe anyway")
                    main.postDelayed({ if (gatt === g) discover(g) }, 400)
                }
                // Otherwise the bond receiver continues once pairing completes.
            }
        }
    }

    private fun discover(g: BluetoothGatt) {
        if (servicesDiscovered) { subscribe(g); return }
        setStatus(Status.SUBSCRIBING)
        log("Discovering services")
        if (!g.discoverServices()) {
            log("discoverServices() refused, retrying")
            main.postDelayed({ if (gatt === g) discover(g) }, 500)
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val ch = dataChar ?: return
        subscribeAttempts++
        if (subscribeAttempts > MAX_SUBSCRIBE_ATTEMPTS) {
            log("Controller still silent after $MAX_SUBSCRIBE_ATTEMPTS tries: reconnecting. " +
                "If this repeats, tap Re-pair (the controller may be paired to another phone).")
            reconnectSoon(fullReset = true)
            return
        }
        setStatus(Status.SUBSCRIBING)
        if (!g.setCharacteristicNotification(ch, true)) log("setCharacteristicNotification() returned false")
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // Unusual, but some stacks still deliver notifications without a CCCD write.
            log("No notification descriptor; waiting for data")
            onSubscribed()
            return
        }
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            log("Notification write not sent (code $rc), retrying")
            main.postDelayed({ if (gatt === g) subscribe(g) }, 300L * subscribeAttempts)
        } else {
            log("Enabling notifications (attempt $subscribeAttempts)")
        }
    }

    private fun onSubscribed() {
        setStatus(Status.CONNECTED)
        log("Subscribed; waiting for data")
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        main.removeCallbacks(firstPacketWatchdog)
        main.postDelayed(firstPacketWatchdog, FIRST_PACKET_TIMEOUT_MS)
    }

    /** No packet after subscribing: the link is probably unencrypted. Pair, then try again. */
    private val firstPacketWatchdog = Runnable {
        val g = gatt ?: return@Runnable
        if (totalPacketsSinceSubscribe > 0) return@Runnable
        val d = g.device
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            log("No data and not paired: requesting pairing")
            setStatus(Status.PAIRING)
            d.createBond()
        } else {
            log("No data yet: re-subscribing")
            g.setCharacteristicNotification(dataChar, false)
            dataChar?.getDescriptor(CCCD_UUID)?.let {
                g.writeDescriptor(it, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
            main.postDelayed({ if (gatt === g) subscribe(g) }, 300)
        }
    }

    /** Packets stopped mid-stream (controller asleep or out of range). */
    private val stallWatchdog = object : Runnable {
        override fun run() {
            if (status == Status.STREAMING && SystemClock.uptimeMillis() - lastPacketAt > STALL_TIMEOUT_MS) {
                log("Data stopped; waiting for controller (press Home to wake it)")
                setStatus(Status.CONNECTED)
            }
            // Packet rate, once a second.
            packetsPerSecond = packetsThisSecond
            packetsThisSecond = 0
            if (gatt != null) main.postDelayed(this, 1000)
        }
    }

    private var totalPacketsSinceSubscribe = 0

    private fun reconnectSoon(fullReset: Boolean) {
        val ctx = appContext ?: return
        teardownGatt()
        setStatus(Status.DISCONNECTED)
        if (!wantConnected) return
        val delay = if (fullReset) 2000L else 1500L
        main.postDelayed({ if (wantConnected) connect(ctx) }, delay)
    }

    private fun teardownGatt() {
        main.removeCallbacks(firstPacketWatchdog)
        main.removeCallbacks(stallWatchdog)
        gatt?.let { try { it.disconnect() } catch (_: Exception) {}; it.close() }
        gatt = null
        dataChar = null
        servicesDiscovered = false
    }

    // ---- Bonding --------------------------------------------------------------------------

    private fun registerBondReceiver(ctx: Context) {
        if (bondReceiverRegistered) return
        ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_EXPORTED)
        bondReceiverRegistered = true
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (d.address != device?.address) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            log("Pairing: ${bondName(state)}")
            val g = gatt
            when (state) {
                BluetoothDevice.BOND_BONDED -> if (g != null) {
                    subscribeAttempts = 0
                    main.postDelayed({ if (gatt === g) discover(g) }, 500)
                }
                BluetoothDevice.BOND_NONE -> if (status == Status.PAIRING) {
                    log("Pairing was declined or failed; tap Connect to try again")
                    wantConnected = false
                    teardownGatt()
                    setStatus(Status.DISCONNECTED)
                }
            }
        }
    }

    private fun bondName(s: Int) = when (s) {
        BluetoothDevice.BOND_BONDED -> "paired"
        BluetoothDevice.BOND_BONDING -> "pairing"
        else -> "not paired"
    }

    // ---- GATT callbacks (binder threads; hop to main) -------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (gatt !== g) { g.close(); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(stallWatchdog)
                    main.postDelayed(stallWatchdog, 1000)
                    onLinkUp(g)
                } else {
                    log(if (newState == BluetoothProfile.STATE_CONNECTED) "Connect failed (status $st)"
                        else "Link lost (status $st)")
                    reconnectSoon(fullReset = false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (gatt !== g) return@post
                val ch = g.getService(SERVICE_UUID)?.getCharacteristic(DATA_UUID)
                if (st != BluetoothGatt.GATT_SUCCESS || ch == null) {
                    log("Daydream service not found (status $st); reconnecting")
                    reconnectSoon(fullReset = true)
                    return@post
                }
                servicesDiscovered = true
                dataChar = ch
                log("Found controller data channel")
                subscribe(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, st: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            main.post {
                if (gatt !== g) return@post
                when {
                    st == BluetoothGatt.GATT_SUCCESS -> {
                        // A disable write (from the watchdog) also lands here; ignore it.
                        if (status == Status.SUBSCRIBING || status == Status.PAIRING) {
                            totalPacketsSinceSubscribe = 0
                            onSubscribed()
                        }
                    }
                    st in AUTH_ERRORS -> {
                        log("Controller wants an encrypted link (status $st): pairing")
                        setStatus(Status.PAIRING)
                        if (g.device.bondState != BluetoothDevice.BOND_BONDED) g.device.createBond()
                        else main.postDelayed({ if (gatt === g) subscribe(g) }, 800)
                    }
                    else -> {
                        log("Notification write failed (status $st), retrying")
                        main.postDelayed({ if (gatt === g) subscribe(g) }, 500)
                    }
                }
            }
        }

        // Android 13+ delivers here.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onPacket(ch, value)
        }

        // Older delivery path, kept in case a vendor stack still uses it.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { onPacket(ch, it.copyOf()) }
        }
    }

    private fun onPacket(ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (ch.uuid != DATA_UUID) return
        val s = ControllerState.decode(value) ?: return
        main.post {
            latest = s
            lastPacketAt = SystemClock.uptimeMillis()
            totalPackets++
            totalPacketsSinceSubscribe++
            packetsThisSecond++
            if (status != Status.STREAMING) {
                setStatus(Status.STREAMING)
                log("Receiving data")
                main.removeCallbacks(firstPacketWatchdog)
            }
            for (l in stateListeners.toList()) l.onState(s)
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        for (l in statusListeners.toList()) l.onStatus(s)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${timeFmt.format(Date())}  $msg"
        val post = {
            logLines.addLast(line)
            while (logLines.size > 60) logLines.removeFirst()
            val snapshot = logLines.toList()
            for (l in logListeners.toList()) l.onLog(snapshot)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) post() else main.post(post)
    }
}
