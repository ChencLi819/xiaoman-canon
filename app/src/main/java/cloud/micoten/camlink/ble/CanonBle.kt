package cloud.micoten.camlink.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cloud.micoten.camlink.util.AppLog
import java.util.ArrayDeque
import java.util.UUID

/** 佳能 Camera Connect 手机模式的 BLE 通道。GATT 状态和队列由主线程管理。 */
@SuppressLint("MissingPermission")
class CanonBle(context: Context) {
    companion object {
        private const val SUFFIX = "-0000-1000-0000-d8492fffa821"

        private fun uuid(value: String): UUID = UUID.fromString(value + SUFFIX)

        private val SVC_PAIR = uuid("00010000")
        private val PAIR_START = uuid("00010006")
        private val PAIR_FINISH = uuid("0001000a")
        private val SVC_WIFI = uuid("00020000")
        private val WIFI_CONTROL = uuid("00020002")
        private val WIFI_SSID = uuid("00020004")
        private val WIFI_PASSWORD = uuid("00020006")
        private val SVC_CORE = uuid("00030000")
        private val CORE_MODE = uuid("00030010")
        private val CORE_SHUTTER = uuid("00030030")
        private val CORE_STATUS = uuid("00030031")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "Xiaoman Canon"
    }

    data class Device(
        val name: String,
        val address: String,
        val rssi: Int,
        val looksLikeCamera: Boolean,
    )

    enum class Phase {
        OFF,
        SCANNING,
        HANDSHAKING,
        WAITING_CONFIRM,
        READY,
        ERROR,
    }

    var onDevices: (List<Device>) -> Unit = {}
    var onPhase: (Phase, String) -> Unit = { _, _ -> }
    var onLog: (String) -> Unit = {}
    var onShutter: (String) -> Unit = {}
    var onWifi: (ssid: String?, pass: String?) -> Unit = { _, _ -> }
    var onName: (String) -> Unit = {}

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("camlink", Context.MODE_PRIVATE)
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Device>()

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanListener: ScanCallback? = null
    private var phase = Phase.OFF
    private var pairingAddress: String? = null
    private var pairFinishing = false
    private var apStarting = false

    private enum class OpKind {
        READ,
        WRITE,
        SUBSCRIBE,
    }

    private class Op(
        val kind: OpKind,
        val characteristic: BluetoothGattCharacteristic,
        val label: String,
        val start: (BluetoothGatt) -> Boolean,
        val complete: (ByteArray) -> Unit,
    )

    private val queue = ArrayDeque<Op>()
    private var pending: Op? = null
    private val opTimeout = Runnable {
        pending?.let { fail("${it.label}超时，请重新连接蓝牙") }
    }
    private val pairTimeout = Runnable { fail("蓝牙配对超时，请在相机的「连接至智能手机」界面重试") }
    private val scanTimeout = Runnable { stopScan() }

    val isReady: Boolean
        get() = gatt != null && phase == Phase.READY

    fun unavailableReason(): String? {
        val adapter = manager?.adapter ?: return "这台手机没有蓝牙适配器"
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        if (
            permissions.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "请在应用权限中允许「附近的设备」"
            } else {
                "扫描蓝牙需要精确位置权限，并开启手机的位置服务"
            }
        }
        return if (adapter.isEnabled) null else "请先打开手机蓝牙"
    }

    fun startScan() {
        unavailableReason()?.let {
            setPhase(Phase.ERROR, it)
            return
        }
        if (scanListener != null) return
        val activeScanner = manager?.adapter?.bluetoothLeScanner
        if (activeScanner == null) {
            setPhase(Phase.ERROR, "手机蓝牙扫描暂不可用")
            return
        }
        closeGatt()
        found.clear()
        onDevices(emptyList())
        val listener =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    main.post {
                        if (scanListener === this) addResult(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if (scanListener !== this) return@post
                        stopScan()
                        setPhase(Phase.ERROR, "蓝牙扫描失败（$errorCode），请重新搜索")
                    }
                }
            }
        scanner = activeScanner
        scanListener = listener
        try {
            activeScanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                listener,
            )
            setPhase(Phase.SCANNING, "正在搜索相机")
            main.postDelayed(scanTimeout, 15_000)
        } catch (e: Exception) {
            stopScan()
            setPhase(Phase.ERROR, "蓝牙扫描失败：${e.message}")
        }
    }

    private fun addResult(result: ScanResult) {
        val device = result.device
        val name =
            result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: runCatching { device.name }.getOrNull().orEmpty()
        val advertised = result.scanRecord?.serviceUuids.orEmpty().any { it.uuid == SVC_PAIR }
        val upperName = name.uppercase()
        val camera = advertised || listOf("EOS", "CANON", "KISS", "REBEL").any { it in upperName }
        if (!camera) return
        val candidate = Device(name.ifBlank { "佳能相机" }, device.address, result.rssi, true)
        val previous = found[device.address]
        if (previous?.name == candidate.name && previous.rssi >= candidate.rssi - 3) return
        found[device.address] = candidate
        onDevices(found.values.sortedByDescending { it.rssi })
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        val listener = scanListener ?: return
        scanListener = null
        runCatching { scanner?.stopScan(listener) }
        scanner = null
        if (phase == Phase.SCANNING) setPhase(Phase.OFF, if (found.isEmpty()) "未搜到佳能相机" else "")
    }

    fun connect(device: Device) {
        unavailableReason()?.let {
            setPhase(Phase.ERROR, it)
            return
        }
        stopScan()
        closeGatt()
        try {
            val target = manager?.adapter?.getRemoteDevice(device.address) ?: error("相机不可用")
            pairingAddress = target.address
            onName(device.name)
            setPhase(Phase.HANDSHAKING, "正在连接相机蓝牙")
            gatt =
                target.connectGatt(ctx, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                    ?: error("手机未能建立 GATT 连接")
            main.postDelayed(pairTimeout, 60_000)
        } catch (e: Exception) {
            fail("蓝牙连接失败：${e.message}")
        }
    }

    fun disconnect() {
        stopScan()
        closeGatt()
        setPhase(Phase.OFF, "")
    }

    private fun closeGatt() {
        main.removeCallbacks(pairTimeout)
        main.removeCallbacks(opTimeout)
        queue.clear()
        pending = null
        pairFinishing = false
        apStarting = false
        pairingAddress = null
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    private val callbacks =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(link: BluetoothGatt, status: Int, state: Int) {
                main.post {
                    if (gatt !== link) return@post
                    when {
                        state == BluetoothProfile.STATE_DISCONNECTED && apStarting -> {
                            closeGatt()
                            setPhase(Phase.OFF, "相机已切换到 Wi-Fi")
                        }
                        status != BluetoothGatt.GATT_SUCCESS -> fail("蓝牙连接失败（GATT $status）")
                        state == BluetoothProfile.STATE_CONNECTED -> {
                            setPhase(Phase.HANDSHAKING, "正在查找佳能蓝牙服务")
                            if (!link.discoverServices()) fail("无法查找佳能蓝牙服务")
                        }
                        state == BluetoothProfile.STATE_DISCONNECTED -> fail("相机蓝牙已断开")
                    }
                }
            }

            override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
                main.post {
                    if (gatt !== link) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("发现蓝牙服务失败（GATT $status）")
                    } else {
                        startPairing(link)
                    }
                }
            }

            override fun onDescriptorWrite(
                link: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                main.post { complete(link, OpKind.SUBSCRIBE, descriptor.characteristic, status) }
            }

            override fun onCharacteristicWrite(
                link: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.post { complete(link, OpKind.WRITE, ch, status) }
            }

            override fun onCharacteristicRead(
                link: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                val bytes = value.copyOf()
                main.post { complete(link, OpKind.READ, ch, status, bytes) }
            }

            @Deprecated("Used on Android 12 and older")
            override fun onCharacteristicRead(
                link: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                @Suppress("DEPRECATION") val bytes = ch.value?.copyOf() ?: byteArrayOf()
                main.post { complete(link, OpKind.READ, ch, status, bytes) }
            }

            override fun onCharacteristicChanged(
                link: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                val bytes = value.copyOf()
                main.post { if (link === gatt) notification(ch.uuid, bytes) }
            }

            @Deprecated("Used on Android 12 and older")
            override fun onCharacteristicChanged(
                link: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION") val bytes = ch.value?.copyOf() ?: return
                main.post { if (link === gatt) notification(ch.uuid, bytes) }
            }
        }

    private fun startPairing(link: BluetoothGatt) {
        val handshake = link.getService(SVC_PAIR)
        val start = handshake?.getCharacteristic(PAIR_START)
        val finish = handshake?.getCharacteristic(PAIR_FINISH)
        if (start == null || finish == null) {
            fail("未找到佳能手机配对服务，请在相机选「连接至智能手机」并添加设备")
            return
        }
        if (link.device.bondState == BluetoothDevice.BOND_NONE) link.device.createBond()
        waitForBond(link, start)
    }

    private fun waitForBond(link: BluetoothGatt, start: BluetoothGattCharacteristic) {
        if (gatt !== link) return
        if (link.device.bondState == BluetoothDevice.BOND_BONDING) {
            main.postDelayed({ waitForBond(link, start) }, 400)
            return
        }
        val saved = prefs.getString("ble_paired_address", null)
        if (saved == pairingAddress && link.device.bondState == BluetoothDevice.BOND_BONDED) {
            finishPairing(newPair = false)
            return
        }
        subscribe(start) {
            setPhase(Phase.WAITING_CONFIRM, "请在相机屏幕上确认 Xiaoman Canon")
            write(start, byteArrayOf(1) + DEVICE_NAME.toByteArray(), "发送配对请求")
        }
    }

    private fun notification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            PAIR_START ->
                when (value.firstOrNull()?.toInt()) {
                    2 -> if (phase == Phase.WAITING_CONFIRM) finishPairing(newPair = true)
                    3 -> fail("相机拒绝了配对，请在机身上重新添加设备")
                }
            CORE_STATUS -> {
                if (value.size < 3) return
                val code =
                    ((value[0].toInt() and 0xFF) shl 16) or
                        ((value[1].toInt() and 0xFF) shl 8) or
                        (value[2].toInt() and 0xFF)
                when (code) {
                    0x010201 -> onShutter("focused")
                    0x010102 -> onShutter("rec-start")
                    0x010101 -> log("相机结束本次快门操作")
                }
            }
        }
    }

    private fun finishPairing(newPair: Boolean) {
        if (pairFinishing) return
        val finish = gatt?.getService(SVC_PAIR)?.getCharacteristic(PAIR_FINISH) ?: return
        pairFinishing = true
        setPhase(Phase.HANDSHAKING, "正在完成佳能配对")
        if (newPair) {
            write(finish, byteArrayOf(3) + deviceId(), "登记设备编号")
            write(finish, byteArrayOf(4) + DEVICE_NAME.toByteArray(), "登记设备名称")
            write(finish, byteArrayOf(5, 2), "登记手机类型")
        }
        write(finish, byteArrayOf(1), "完成配对") {
            val status = core(CORE_STATUS)
            if (status != null && status.getDescriptor(CCCD) != null) {
                subscribe(status) { paired() }
            } else {
                paired()
            }
        }
    }

    private fun paired() {
        prefs.edit().putString("ble_paired_address", pairingAddress).apply()
        main.removeCallbacks(pairTimeout)
        setPhase(Phase.READY, "佳能蓝牙已连接")
    }

    fun release() = shutter(380)

    fun focus() = shutter(600)

    private fun shutter(holdMs: Long) {
        val link = gatt ?: return
        val ch = core(CORE_SHUTTER) ?: return unsupported("蓝牙快门")
        write(ch, byteArrayOf(0, 1), "按下快门") {
            main.postDelayed(
                {
                    if (gatt === link) write(ch, byteArrayOf(0, 2), "松开快门")
                },
                holdMs,
            )
        }
    }

    fun record(start: Boolean) {
        val ch = core(CORE_SHUTTER) ?: return unsupported("蓝牙录像")
        write(ch, byteArrayOf(0, if (start) 0x10 else 0x11), if (start) "录像" else "停止录像")
    }

    fun setMode(mode: Int) {
        val ch = core(CORE_MODE) ?: return unsupported("蓝牙切换拍摄或回放")
        write(ch, byteArrayOf(mode.toByte()), "切换拍摄或回放")
    }

    fun wake() {
        val ch = core(CORE_MODE) ?: return unsupported("蓝牙唤醒")
        write(ch, byteArrayOf(3), "唤醒")
        write(ch, byteArrayOf(4), "亮屏")
        write(ch, byteArrayOf(3), "唤醒")
    }

    fun sleep() {
        val ch = core(CORE_MODE) ?: return unsupported("蓝牙休眠")
        write(ch, byteArrayOf(5), "休眠")
    }

    fun readWifi() {
        val service = gatt?.getService(SVC_WIFI)
        val control = service?.getCharacteristic(WIFI_CONTROL)
        val ssid = service?.getCharacteristic(WIFI_SSID)
        val password = service?.getCharacteristic(WIFI_PASSWORD)
        if (control == null || ssid == null || password == null) return unsupported("蓝牙读取热点")
        write(control, byteArrayOf(0x0A), "初始化热点信息") {
            read(ssid, "读取热点名称") { name ->
                read(password, "读取热点密码") { pass ->
                    val network = readText(name)
                    val key = readText(pass)
                    if (network.isBlank() || (key.isNotEmpty() && key.length !in 8..63)) {
                        unsupported("读取完整热点信息")
                    } else {
                        onWifi(network, key)
                    }
                }
            }
        }
    }

    fun openCameraAp(onStarted: () -> Unit) {
        val ch =
            gatt?.getService(SVC_WIFI)?.getCharacteristic(WIFI_CONTROL)
                ?: return unsupported("蓝牙启动热点")
        write(ch, byteArrayOf(1), "启动相机热点") {
            apStarting = true
            onStarted()
        }
    }

    private fun readText(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')

    private fun core(id: UUID) = gatt?.getService(SVC_CORE)?.getCharacteristic(id)

    private fun subscribe(ch: BluetoothGattCharacteristic, done: () -> Unit) {
        val descriptor = ch.getDescriptor(CCCD)
        if (descriptor == null) {
            fail("相机蓝牙特征缺少通知描述符")
            return
        }
        val value =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
        enqueue(
            Op(
                OpKind.SUBSCRIBE,
                ch,
                "注册相机通知",
                { link ->
                    link.setCharacteristicNotification(ch, true) &&
                        if (Build.VERSION.SDK_INT >= 33) {
                            link.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                descriptor.value = value
                                link.writeDescriptor(descriptor)
                            }
                        }
                },
                { done() },
            )
        )
    }

    private fun write(
        ch: BluetoothGattCharacteristic,
        data: ByteArray,
        label: String,
        done: () -> Unit = {},
    ) {
        val type =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        enqueue(
            Op(
                OpKind.WRITE,
                ch,
                label,
                { link ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        link.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            ch.writeType = type
                            ch.value = data
                            link.writeCharacteristic(ch)
                        }
                    }
                },
                { done() },
            )
        )
    }

    private fun read(ch: BluetoothGattCharacteristic, label: String, done: (ByteArray) -> Unit) {
        enqueue(Op(OpKind.READ, ch, label, { it.readCharacteristic(ch) }, done))
    }

    private fun enqueue(op: Op) {
        if (gatt == null) return
        queue.addLast(op)
        pump()
    }

    private fun pump() {
        val link = gatt ?: return
        if (pending != null) return
        val op = queue.pollFirst() ?: return
        pending = op
        if (runCatching { op.start(link) }.getOrDefault(false)) {
            main.postDelayed(opTimeout, 12_000)
        } else {
            fail("未能提交蓝牙操作：${op.label}")
        }
    }

    private fun complete(
        link: BluetoothGatt,
        kind: OpKind,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        value: ByteArray = byteArrayOf(),
    ) {
        val op = pending ?: return
        if (link !== gatt || kind != op.kind || characteristic.uuid != op.characteristic.uuid)
            return
        main.removeCallbacks(opTimeout)
        pending = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("${op.label}失败（GATT $status）")
            return
        }
        op.complete(value)
        pump()
    }

    private fun deviceId(): ByteArray {
        val stored = prefs.getString("ble_device_id", null)?.takeIf { it.length == 32 }
        val parsed = stored?.let {
            runCatching { it.chunked(2).map { hex -> hex.toInt(16).toByte() }.toByteArray() }
                .getOrNull()
        }
        return parsed
            ?: ByteArray(16).also { id ->
                java.security.SecureRandom().nextBytes(id)
                prefs
                    .edit()
                    .putString("ble_device_id", id.joinToString("") { "%02x".format(it) })
                    .apply()
            }
    }

    private fun unsupported(action: String) = setPhase(Phase.READY, "当前蓝牙连接无法执行：$action")

    private fun fail(message: String) {
        closeGatt()
        setPhase(Phase.ERROR, message)
    }

    private fun setPhase(value: Phase, message: String) {
        phase = value
        if (message.isNotEmpty()) log(message)
        onPhase(value, message)
    }

    private fun log(message: String) {
        AppLog.i("CanonBle", message)
        onLog(message)
    }
}
