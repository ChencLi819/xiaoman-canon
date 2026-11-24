package cloud.micoten.camlink.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.micoten.camlink.R
import cloud.micoten.camlink.ble.CanonBle
import cloud.micoten.camlink.camera.CameraBattery
import cloud.micoten.camlink.camera.CameraCatalog
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.camera.CameraProfile
import cloud.micoten.camlink.camera.Choice
import cloud.micoten.camlink.camera.EosCamera
import cloud.micoten.camlink.camera.EosValues
import cloud.micoten.camlink.camera.ExposureMode
import cloud.micoten.camlink.camera.FocusOutcome
import cloud.micoten.camlink.camera.FocusPoint
import cloud.micoten.camlink.camera.ParamKind
import cloud.micoten.camlink.camera.PreviewCadence
import cloud.micoten.camlink.camera.PreviewIntervals
import cloud.micoten.camlink.camera.PreviewJpeg
import cloud.micoten.camlink.camera.PreviewPoll
import cloud.micoten.camlink.camera.StorageInfo
import cloud.micoten.camlink.net.CameraWifi
import cloud.micoten.camlink.ptpip.CameraEvent
import cloud.micoten.camlink.ptpip.PtpEvent
import cloud.micoten.camlink.ptpip.PtpException
import cloud.micoten.camlink.ptpip.PtpIpConnection
import cloud.micoten.camlink.ptpip.PtpProp
import cloud.micoten.camlink.ptpip.PtpRc
import cloud.micoten.camlink.ui.theme.AppAppearance
import cloud.micoten.camlink.util.AppLog
import cloud.micoten.camlink.util.Bitmaps
import cloud.micoten.camlink.util.MediaStoreSaver
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 链路状态。WORKING 覆盖了扫描热点到打开会话的全部中间态。 */
enum class Link {
    OFFLINE,
    WORKING,
    ONLINE,
}

/**
 * 两条互相独立的遥控通道。
 *
 * [WIFI] 是完整链路：取景、参数、拍摄、传图全走 PTP/IP； [BLE] 是佳能手机模式的低功耗链路，可遥控快门和读取热点， 好处是不用连热点、几十微安就能一直待机。取景只能在
 * Wi-Fi 上做。
 */
enum class RemoteMode {
    WIFI,
    BLE,
}

/**
 * AF 键的可见状态。
 *
 * 对焦完成以机身事件或取景包里的对焦状态为准。
 */
enum class AfState {
    IDLE,
    WORKING,
    OK,
    FAIL,
    POSITION_UNAVAILABLE,
    UNCONFIRMED,
    ERROR,
}

enum class ViewfinderPhase {
    OFF,
    STARTING,
    STREAMING,
    STOPPING,
    FAILED,
}

data class ViewfinderState(
    val phase: ViewfinderPhase = ViewfinderPhase.OFF,
    val error: String? = null,
    val retryStop: Boolean = false,
)

internal class LiveFrame(val jpeg: PreviewJpeg, val receivedAtNanos: Long)

enum class CapturePhase {
    IDLE,
    CAPTURING,
    SAVED,
    FAILED,
}

data class CaptureFeedback(val phase: CapturePhase = CapturePhase.IDLE, val message: String = "")

enum class TransferPhase {
    QUEUED,
    COPYING,
    SAVED,
    FAILED,
}

data class FileTransfer(
    val file: CameraFile,
    val phase: TransferPhase,
    val percent: Int? = null,
    val error: String? = null,
)

/** 从蓝牙读回来的相机热点凭据，界面据此一键切到 Wi-Fi 链路。 */
data class BleHotspot(val ssid: String?, val pass: String?)

/** 遥控面板要显示的全部数值，统一由 [CameraViewModel.syncParams] 刷新。 */
data class Params(
    val shutter: String = "—",
    val aperture: String = "—",
    val iso: String = "—",
    val ec: String = "—",
    val wb: String = "—",
    val shots: String = "—",
    val battery: CameraBattery = CameraBattery.UNKNOWN,
    val shutterCount: Int? = null,
    /* 「相机」页的只读信息。字符串类取不到时空串，界面据此显示占位。 */
    val drive: String = "—",
    val metering: String = "—",
    val imageFormat: String = "—",
    val lens: String = "",
    val serial: String = "",
    val owner: String = "",
    val nickname: String = "",
    val firmware: String = "",
    /* 「相机」页的存储与色彩信息。存储字段只在拉到 StorageInfo 时覆盖，平时保留旧值。 */
    val colorSpace: String = "—",
    val pictureStyle: String = "—",
    val storageType: String = "—",
    val storageLabel: String = "—",
    val storageTotal: String = "—",
    val storageFree: String = "—",
    val storageFreeImages: String = "—",
    val storageAccess: String = "—",
    val recordingSec: Int = 0,
    val options: Map<ParamKind, List<Choice>> = emptyMap(),
    val shutterCode: Int? = null,
    val apertureCode: Int? = null,
    val isoCode: Int? = null,
    val ecCode: Int? = null,
    val wbCode: Int? = null,
)

/**
 * 相机会话的唯一持有者。
 *
 * 三条约束：
 * 1. 所有 PTP 事务都在同一个单线程调度器 [cam] 上串行执行 —— PTP/IP 的命令通道 本来就不支持并发事务，靠这个调度器比在每个调用点加锁更省心；
 * 2. 取景循环也跑在 [cam] 上，但帧间用 `delay` 让出线程，参数调整不会被卡住；
 * 3. 佳能事件从同一个调度器轮询，取景、图库和事件按顺序使用命令通道。
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "CameraVM"
        private const val KEY_AUTO_TRANSFER = "auto_transfer"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_LOW_LATENCY_PREVIEW = "low_latency_preview"

        /** 手动指定的机型 id，空表示跟随相机自报的名字。 */
        private const val KEY_MODEL = "model_id"

        private const val MAX_PREVIEW_BYTES = 32 * 1024 * 1024
        private const val EVENT_POLL_INTERVAL_MS = 350L
        private const val PREVIEW_EVENT_MIN_INTERVAL_MS = 120L
    }

    private val prefs = app.getSharedPreferences("camlink", Application.MODE_PRIVATE)
    private val _appearance =
        MutableStateFlow(
            AppAppearance.entries.firstOrNull { it.name == prefs.getString(KEY_APPEARANCE, null) }
                ?: AppAppearance.NIGHT
        )
    val appearance = _appearance.asStateFlow()

    fun setAppearance(appearance: AppAppearance) {
        _appearance.value = appearance
        prefs.edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    private val clientGuid =
        (prefs.getString("ptpip_client_id", null)?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
                ?: UUID.randomUUID().also {
                    prefs.edit().putString("ptpip_client_id", it.toString()).apply()
                })
            .let {
                ByteBuffer.allocate(16)
                    .putLong(it.mostSignificantBits)
                    .putLong(it.leastSignificantBits)
                    .array()
            }

    private val cam =
        Executors.newSingleThreadExecutor { r -> Thread(r, "eos-cmd").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var conn: PtpIpConnection? = null

    @Volatile private var camera: EosCamera? = null

    @Volatile private var liveJob: Job? = null
    private var eventJob: Job? = null

    @Volatile private var afJob: Job? = null

    /** 从相机事件接收的新文件，在同一任务内顺序回传。 */
    private var transferJob: Job? = null
    private var filesLoadJob: Job? = null
    private var filesLoaded = false
    private val pendingTransfers = LinkedHashMap<Int, CameraFile>()
    private val thumbnails =
        object : LruCache<Int, ByteArray>(3 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: ByteArray): Int = value.size
        }

    /** 已回传过的对象句柄，防止命令返回与事件通知两条路径重复存图。 */
    private val autoSavedHandles = ConcurrentHashMap.newKeySet<Int>()

    // ------------------------------------------------------------------ 状态

    private val _link = MutableStateFlow(Link.OFFLINE)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** 相机自报的型号名（PTP/IP 握手时带回来），连接页与取景 HUD 用。 */
    private val _cameraName = MutableStateFlow("")
    val cameraName: StateFlow<String> = _cameraName.asStateFlow()

    /**
     * 当前机型的能力表。
     *
     * 界面上所有码表都从这里取，而不是直接读 [EosValues] 的并集 —— 200D II 没有 1/8000 快门、也没有 ISO 50，把并集摊给用户只会让他选到相机拒收的值。
     */
    private val _profile =
        MutableStateFlow(
            CameraCatalog.byId(prefs.getString(KEY_MODEL, null)) ?: CameraCatalog.DEFAULT
        )
    val profile: StateFlow<CameraProfile> = _profile.asStateFlow()

    /**
     * 手动指定的机型 id；null 表示跟着相机自报的名字走。
     *
     * 相机有时候只报一句 "Canon Camera" 之类，认不出具体型号， 这时允许用户在「我 → 相机」里自己挑一台，码表立刻按挑的那台裁。
     */
    private val _modelId = MutableStateFlow(prefs.getString(KEY_MODEL, null))
    val modelId: StateFlow<String?> = _modelId.asStateFlow()

    private val _frame = MutableStateFlow<LiveFrame?>(null)
    internal val frame: StateFlow<LiveFrame?> = _frame.asStateFlow()

    private val _params = MutableStateFlow(Params())
    val params: StateFlow<Params> = _params.asStateFlow()

    private val _files = MutableStateFlow<List<CameraFile>>(emptyList())
    val files: StateFlow<List<CameraFile>> = _files.asStateFlow()
    private val _filesLoading = MutableStateFlow(false)
    val filesLoading = _filesLoading.asStateFlow()
    private val _filesError = MutableStateFlow<String?>(null)
    val filesError = _filesError.asStateFlow()

    private val _viewfinder = MutableStateFlow(ViewfinderState())
    val viewfinder = _viewfinder.asStateFlow()
    private val liveStopping
        get() = _viewfinder.value.phase == ViewfinderPhase.STOPPING

    private val viewfinderVisible = MutableStateFlow(true)
    private val _lowLatencyPreview =
        MutableStateFlow(prefs.getBoolean(KEY_LOW_LATENCY_PREVIEW, true))
    val lowLatencyPreview = _lowLatencyPreview.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(viewfinderVisible, _viewfinder, _lowLatencyPreview) { visible, state, lowLatency
                    ->
                    visible && lowLatency && state.phase.isReceivingFrames()
                }
                .distinctUntilChanged()
                .collectLatest { prioritize ->
                    if (prioritize) CameraWifi.prioritizePreview(getApplication())
                }
        }
    }

    fun setLowLatencyPreview(on: Boolean) {
        _lowLatencyPreview.value = on
        prefs.edit().putBoolean(KEY_LOW_LATENCY_PREVIEW, on).apply()
    }

    private fun ViewfinderPhase.isReceivingFrames() =
        this == ViewfinderPhase.STARTING || this == ViewfinderPhase.STREAMING

    private fun isPreviewPrioritized() =
        viewfinderVisible.value &&
            _lowLatencyPreview.value &&
            _viewfinder.value.phase.isReceivingFrames()

    fun setViewfinderVisible(visible: Boolean) {
        viewfinderVisible.value = visible
    }

    private val _captureFeedback = MutableStateFlow(CaptureFeedback())
    val captureFeedback = _captureFeedback.asStateFlow()

    private val _recentPhoto = MutableStateFlow<CameraFile?>(null)
    val recentPhoto = _recentPhoto.asStateFlow()

    private val _intervalProgress = MutableStateFlow<EosCamera.IntervalProgress?>(null)
    val intervalProgress = _intervalProgress.asStateFlow()

    private val _transfers = MutableStateFlow<List<FileTransfer>>(emptyList())
    val transfers = _transfers.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /**
     * 录制期间把计时推给界面。
     *
     * 秒数本身由 [EosCamera.recordingSeconds] 结合本地钟表算，但界面只会在有事件 抵达时刷新；机身推 `RecordingTime`
     * 并不保证一秒一条，光等事件计时会看着停住。
     */
    init {
        viewModelScope.launch {
            _recording.collectLatest { on ->
                while (on && isActive) {
                    val c = camera
                    if (c != null) {
                        val s = c.recordingSeconds
                        _params.update {
                            if (it.recordingSec != s) it.copy(recordingSec = s) else it
                        }
                    }
                    delay(400)
                }
            }
        }
    }

    private val _bulbOpen = MutableStateFlow(false)
    val bulbOpen: StateFlow<Boolean> = _bulbOpen.asStateFlow()

    private val _intervalRunning = MutableStateFlow(false)
    val intervalRunning: StateFlow<Boolean> = _intervalRunning.asStateFlow()
    private val _intervalStopping = MutableStateFlow(false)
    val intervalStopping = _intervalStopping.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _af = MutableStateFlow(AfState.IDLE)
    val af: StateFlow<AfState> = _af.asStateFlow()

    /** AF 结果序号：每收到一条机身回报自增，界面用它判断「这是新的一条」。 */
    private val _afSeq = MutableStateFlow(0L)
    val afSeq: StateFlow<Long> = _afSeq.asStateFlow()

    /**
     * 机身拨盘停在哪一档。
     *
     * 来源是 `0xD105 AutoExposureMode`，缺失时退到 `0xD138 AEModeDial`。 这两个属性在 EOS 机身上都是只读的 ——
     * 所以这里是「镜像」，不是「设置」。
     */
    private val _exposureMode = MutableStateFlow(ExposureMode.UNKNOWN)
    val exposureMode: StateFlow<ExposureMode> = _exposureMode.asStateFlow()

    /*
     * 对焦方式（0xD108）：0 单次AF / 1 伺服AF / 2 智能AF，3 是镜头拨到了 MF。
     * 机身只负责回报，App 只做镜像；null 表示还没读到。
     */
    private val _focusMode = MutableStateFlow<Int?>(null)
    val focusMode: StateFlow<Int?> = _focusMode.asStateFlow()

    private val _autoTransfer = MutableStateFlow(prefs.getBoolean(KEY_AUTO_TRANSFER, true))
    val autoTransfer: StateFlow<Boolean> = _autoTransfer.asStateFlow()

    // -------------------------------------------------------------- 蓝牙通道

    private val _mode = MutableStateFlow(RemoteMode.WIFI)
    val mode: StateFlow<RemoteMode> = _mode.asStateFlow()

    private val _bleDevices = MutableStateFlow<List<CanonBle.Device>>(emptyList())
    val bleDevices: StateFlow<List<CanonBle.Device>> = _bleDevices.asStateFlow()

    private val _blePhase = MutableStateFlow(CanonBle.Phase.OFF)
    val blePhase: StateFlow<CanonBle.Phase> = _blePhase.asStateFlow()

    /** 蓝牙侧的状态文案，与 Wi-Fi 的 [status] 分开，两页各显示各的。 */
    private val _bleMessage = MutableStateFlow("")
    val bleMessage: StateFlow<String> = _bleMessage.asStateFlow()

    /** 蓝牙通道自己的流水，取景页/日志页排错时看。 */
    private val _bleLog = MutableStateFlow<List<String>>(emptyList())
    val bleLog: StateFlow<List<String>> = _bleLog.asStateFlow()

    private val _bleName = MutableStateFlow("")
    val bleName: StateFlow<String> = _bleName.asStateFlow()

    private val _bleHotspot = MutableStateFlow<BleHotspot?>(null)
    val bleHotspot: StateFlow<BleHotspot?> = _bleHotspot.asStateFlow()

    /** 蓝牙不可用的原因（没适配器 / 没开 / 没权限），为空表示可用。 */
    fun bleUnavailable(): String? = ble.unavailableReason()

    private val ble: CanonBle =
        CanonBle(app).also { b ->
            // 回调都在 GATT 线程上抛，统一切回主线程再写 StateFlow
            b.onDevices = { list -> main.post { _bleDevices.value = list } }
            b.onPhase = { p, msg ->
                main.post {
                    _blePhase.value = p
                    if (msg.isNotBlank()) _bleMessage.value = msg
                    if (p == CanonBle.Phase.READY) _mode.value = RemoteMode.BLE
                }
            }
            b.onLog = { line -> main.post { _bleLog.update { old -> (old + line).takeLast(120) } } }
            b.onName = { n -> main.post { _bleName.value = n } }
            b.onWifi = { ssid, pass -> main.post { _bleHotspot.value = BleHotspot(ssid, pass) } }
            b.onShutter = { what ->
                main.post {
                    _bleMessage.value =
                        when (what) {
                            "focused" -> "已合焦"
                            "shot" -> "拍摄完成"
                            "rec-start" -> "开始录像"
                            "rec-stop" -> "停止录像"
                            else -> what
                        }
                }
            }
        }

    private fun str(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    // -------------------------------------------------------------- 连接管理

    /**
     * 型号名与机型能力表永远一起变，别让它们有机会对不上。
     *
     * 用户手动指定过机型就听用户的，否则按相机自报的名字识别 —— 自报名字认不出来时 [CameraCatalog.detect] 自己会退回默认机型。
     */
    private fun bindProfile(name: String) {
        _cameraName.value = name
        _profile.value = CameraCatalog.byId(_modelId.value) ?: CameraCatalog.detect(name)
    }

    /**
     * 手动指定机型。[id] 传 null 表示恢复「按相机自报名字自动识别」。
     *
     * 改完立刻按新码表重解一遍参数，不用重连相机 —— 用户挑机型就是为了马上看到档位变化。
     */
    fun selectModel(id: String?) {
        if (id != null && CameraCatalog.byId(id) == null) return
        _modelId.value = id
        prefs.edit().putString(KEY_MODEL, id).apply()
        bindProfile(_cameraName.value)
        syncParams()
        AppLog.i(TAG, "机型切到 ${_profile.value.model}")
    }

    fun connect(ssid: String?, passphrase: String) {
        if (_link.value != Link.OFFLINE) return
        _link.value = Link.WORKING
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                var target = ssid?.trim().orEmpty()
                if (target.isEmpty()) {
                    val current = CameraWifi.wifiNetwork(ctx)
                    if (current != null) {
                        _status.value = str(R.string.st_locating)
                        val hosts = CameraWifi.scanHosts(ctx, current, fallback = false)
                        if (hosts.isNotEmpty()) {
                            attachDiscovered(current, hosts)
                            return@launch
                        }
                    }
                    _status.value = str(R.string.st_scanning)
                    val list = CameraWifi.scan(ctx)
                    target = list.firstOrNull { it.looksLikeCamera }?.ssid ?: ""
                }
                if (target.isEmpty()) {
                    fail(str(R.string.msg_no_host))
                    return@launch
                }
                _status.value = str(R.string.st_connecting_wifi, target)
                AppLog.i(TAG, "请求 Android 接入相机热点")
                val network =
                    CameraWifi.connect(ctx, target, passphrase) { lost ->
                        main.post {
                            if (sessionNetwork == lost && _link.value == Link.ONLINE)
                                onLinkLost("相机 Wi-Fi 已断开")
                        }
                    }

                _status.value = str(R.string.st_locating)
                attachDiscovered(network, CameraWifi.scanHosts(ctx, network))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                AppLog.e(TAG, "连接失败", t)
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun connectDirect(address: String) {
        if (_link.value != Link.OFFLINE) return
        val raw = address.trim()
        val host = raw.substringBefore(':')
        val port =
            if (':' in raw) raw.substringAfter(':').toIntOrNull() else PtpIpConnection.DEFAULT_PORT
        if (host.isBlank() || port == null || port !in 1..65535) {
            _status.value = "请输入相机 IP 地址，可附加端口"
            return
        }
        _link.value = Link.WORKING
        viewModelScope.launch {
            try {
                val network =
                    CameraWifi.wifiNetwork(getApplication())
                        ?: error("请先在手机的 Wi-Fi 设置中连接相机热点或相机所在的 Wi-Fi")
                attach(network, host, port)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                AppLog.e(TAG, "直连失败", t)
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private var sessionNetwork: Network? = null

    fun connectCurrentWifi() {
        if (_link.value != Link.OFFLINE) return
        _link.value = Link.WORKING
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val network = CameraWifi.wifiNetwork(ctx) ?: error("请先在手机设置中连接相机热点或相机所在的 Wi-Fi")
                _status.value = str(R.string.st_locating)
                attachDiscovered(network, CameraWifi.scanHosts(ctx, network))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "系统 Wi-Fi 相机连接失败", e)
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 上次自动探测的时间，防止反复切前台时频繁扫网。 */
    private var lastAutoProbeMs = 0L
    private var lastAutoNetwork: Network? = null

    /** 回到前台时，仅在发现一台发布 Canon SSDP 公告的设备时自动连接。 */
    fun autoConnectIfPossible() {
        if (_link.value != Link.OFFLINE) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            var connecting = false
            try {
                val network = CameraWifi.wifiNetwork(ctx) ?: return@launch
                if (_link.value != Link.OFFLINE) return@launch
                val now = SystemClock.elapsedRealtime()
                if (network == lastAutoNetwork && now - lastAutoProbeMs < 15_000) return@launch
                lastAutoNetwork = network
                lastAutoProbeMs = now
                val hits = CameraWifi.scanHosts(ctx, network, fallback = false)
                if (
                    _link.value != Link.OFFLINE ||
                        network != CameraWifi.wifiNetwork(ctx) ||
                        hits.size != 1
                ) {
                    return@launch
                }
                connecting = true
                _link.value = Link.WORKING
                attachDiscovered(network, hits)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                AppLog.w(TAG, "自动探测失败：${t.message}")
                if (connecting) fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun attachDiscovered(network: Network, hosts: List<String>) {
        check(hosts.isNotEmpty()) {
            "未找到相机。请在机身选择「遥控（EOS Utility）」并确认「开始设备配对」"
        }
        check(hosts.size == 1) { "发现多台设备，请在「扫描本网」结果中选择相机" }
        attach(network, hosts.single(), PtpIpConnection.DEFAULT_PORT)
    }

    /** 建立 PTP/IP 会话并拉起相机对象，成功后同步参数与图库。 */
    private suspend fun attach(network: Network, host: String, port: Int) {
        val ctx = getApplication<Application>()
        val eventWake = Channel<Unit>(Channel.CONFLATED)
        withContext(cam) {
            val c =
                PtpIpConnection(
                    host,
                    port,
                    clientGuid,
                    createSocket = { CameraWifi.openSocket(ctx, network) },
                )
            c.onEvent = { code, _ ->
                if (code == PtpEvent.REQUEST_GET_EVENT) eventWake.trySend(Unit)
            }
            c.onDisconnected = { reason ->
                AppLog.w(TAG, "链路断开：$reason")
                main.post { if (conn === c && _link.value == Link.ONLINE) onLinkLost(reason) }
            }
            val eos = EosCamera(c)
            try {
                c.connect(onStage = { _status.value = it })
                _status.value = "配对握手已完成，正在打开遥控会话"
                eos.open()
                _status.value = "正在确认机身事件通道"
                withTimeoutOrNull(300L) { eventWake.receive() }
                eos.initializeEvents()
                coroutineContext.ensureActive()
                if (!c.isConnected) throw PtpException("相机会话尚未建立就断开，请重试")
            } catch (e: Exception) {
                c.close()
                eos.close()
                throw CameraWifi.explainConnectionError(ctx, network, e)
            }
            c.onEvent = { code, params ->
                when (code) {
                    PtpEvent.REQUEST_GET_EVENT -> eventWake.trySend(Unit)
                    PtpEvent.AF_RESULT -> {
                        params.firstOrNull()?.let(eos::receiveAfResult)
                        eventWake.trySend(Unit)
                    }
                    else -> Unit
                }
            }
            eos.onEvents = { events, parametersChanged ->
                if (conn === c) handleEvents(events, parametersChanged)
            }
            pendingTransfers.clear()
            thumbnails.evictAll()
            _transfers.value = emptyList()
            conn = c
            camera = eos
        }
        sessionNetwork = network
        autoSavedHandles.clear()
        _link.value = Link.ONLINE
        if (conn?.isConnected != true) {
            onLinkLost("相机会话尚未建立就断开，请重试")
            return
        }
        bindProfile(conn?.cameraName.orEmpty())
        _status.value = conn?.cameraName?.takeIf { it.isNotBlank() } ?: str(R.string.status_ready)
        syncParams()
        val connectedCamera = camera
        eventJob =
            viewModelScope.launch(cam) {
                if (connectedCamera == null) return@launch
                var olcRequested = false
                while (isActive && camera === connectedCamera && conn?.isConnected == true) {
                    withTimeoutOrNull(EVENT_POLL_INTERVAL_MS) { eventWake.receive() }
                    if (camera !== connectedCamera || conn?.isConnected != true) break
                    if (connectedCamera.shutterSequenceActive) {
                        delay(50)
                        continue
                    }
                    connectedCamera.drainEvents(maxBatches = 1)
                    if (
                        !olcRequested && connectedCamera.olcVersion > 0 && conn?.isConnected == true
                    ) {
                        olcRequested = true
                        runCatching { connectedCamera.enableOlc() }
                            .onFailure {
                                AppLog.w(TAG, "订阅取景状态失败：${it.message}")
                            }
                    }
                    val urgent = connectedCamera.isFocusing || _busy.value || _intervalRunning.value
                    delay(
                        if (isPreviewPrioritized() && !urgent) PREVIEW_EVENT_MIN_INTERVAL_MS
                        else 50L
                    )
                }
            }
    }

    fun disconnect() {
        if (_link.value != Link.ONLINE) return
        _link.value = Link.WORKING
        _status.value = "正在断开相机"
        val closing = resetSession()
        viewModelScope.launch {
            withContext(cam + NonCancellable) { closing?.close() }
            CameraWifi.release(getApplication())
            _status.value = str(R.string.st_offline)
            _link.value = Link.OFFLINE
        }
    }

    private suspend fun fail(message: String) {
        val closing = resetSession()
        withContext(cam + NonCancellable) { closing?.close() }
        CameraWifi.release(getApplication())
        _status.value = message
        _link.value = Link.OFFLINE
        AppLog.w(TAG, "连接中止：$message")
    }

    private fun onLinkLost(reason: String) {
        if (_link.value == Link.OFFLINE) return
        val closing = resetSession()
        CameraWifi.release(getApplication())
        viewModelScope.launch(cam) { closing?.close() }
        _status.value = reason
        _link.value = Link.OFFLINE
    }

    private fun resetSession(): EosCamera? {
        val closing = camera
        camera = null
        conn = null
        sessionNetwork = null
        closing?.cancelInterval()
        cancelAf()
        liveJob?.cancel()
        liveJob = null
        eventJob?.cancel()
        eventJob = null
        transferJob?.cancel()
        transferJob = null
        filesLoadJob?.cancel()
        filesLoadJob = null
        filesLoaded = false
        autoSavedHandles.clear()
        _viewfinder.value = ViewfinderState()
        _captureFeedback.value = CaptureFeedback()
        _recentPhoto.value = null
        _intervalProgress.value = null
        _transfers.update { list ->
            list.map {
                if (it.phase == TransferPhase.QUEUED || it.phase == TransferPhase.COPYING) {
                    it.copy(phase = TransferPhase.FAILED, error = "相机已断开")
                } else it
            }
        }
        _frame.value = null
        _recording.value = false
        _bulbOpen.value = false
        _busy.value = false
        _intervalRunning.value = false
        _intervalStopping.value = false
        _params.value = Params()
        _files.value = emptyList()
        _filesError.value = null
        _filesLoading.value = false
        _af.value = AfState.IDLE
        _exposureMode.value = ExposureMode.UNKNOWN
        _focusMode.value = null
        bindProfile("")
        return closing
    }

    // ------------------------------------------------------------ 事件与参数

    private fun handleEvents(events: List<CameraEvent>, parametersChanged: Boolean) {
        var added: MutableList<CameraEvent.ObjectAdded>? = null
        for (event in events) {
            when (event) {
                is CameraEvent.ObjectAdded -> {
                    val files = added ?: ArrayList<CameraEvent.ObjectAdded>(2).also { added = it }
                    files.add(event)
                }
                else -> Unit
            }
        }
        if (parametersChanged) syncParams()
        added?.let { refreshAfterShot(it) }
    }

    /** EOS 对象事件已经带句柄、名称和大小。直接更新图库，避免每张照片都重扫存储卡。 */
    private fun refreshAfterShot(added: List<CameraEvent.ObjectAdded>) {
        val c = camera ?: return
        val fresh =
            added
                .map { CameraFile(it.handle, it.storageId, it.format, it.size, it.name) }
                .filter { it.handle != 0 && it.name.isNotBlank() && !it.isDirectory }
        if (fresh.isEmpty()) return
        _files.update { files ->
            (files + fresh).distinctBy { it.handle }.sortedByDescending { it.name }
        }
        val recent = fresh.lastOrNull { !it.isRaw && !it.isMovie } ?: fresh.last()
        val previous = _recentPhoto.value
        if (
            previous == null ||
                previous.name.substringBeforeLast('.') != recent.name.substringBeforeLast('.') ||
                previous.isRaw ||
                !recent.isRaw
        ) {
            _recentPhoto.value = recent
        }
        if (_autoTransfer.value) enqueueOnCamera(c, fresh)
    }

    /** 从相机缓存里重新拼一份 [Params]。可以在任意线程调用。 */
    private fun syncParams(storage: StorageInfo? = null) {
        val c = camera ?: return
        val shutter = c.value(PtpProp.SHUTTER_SPEED)?.toInt()
        val aperture = c.value(PtpProp.APERTURE)?.toInt()
        val iso = c.value(PtpProp.ISO_SPEED)?.toInt()
        val ec = c.value(PtpProp.EXPOSURE_COMPENSATION)?.toInt()
        val wb = c.value(PtpProp.WHITE_BALANCE)?.toInt()
        val shots = c.value(PtpProp.AVAILABLE_SHOTS)
        val battery = c.value(PtpProp.BATTERY_POWER)
        val shutterCount = c.value(PtpProp.SHUTTER_COUNTER)?.toInt()
        val drive = c.value(PtpProp.DRIVE_MODE)?.toInt()
        val metering = c.value(PtpProp.METERING_MODE)?.toInt()
        val imageFormat = c.value(PtpProp.IMAGE_FORMAT)?.toInt()
        val colorSpace = c.value(PtpProp.COLOR_SPACE)?.toInt()
        val pictureStyle = c.value(PtpProp.PICTURE_STYLE)?.toInt()

        val prof = _profile.value
        _params.update {
            it.copy(
                shutter = shutter?.let { v -> prof.label(ParamKind.SHUTTER, v) } ?: "—",
                aperture = aperture?.let { v -> prof.label(ParamKind.APERTURE, v) } ?: "—",
                iso = iso?.let { v -> prof.label(ParamKind.ISO, v) } ?: "—",
                ec = ec?.let { v -> prof.label(ParamKind.EXPOSURE_COMP, v) } ?: "—",
                wb = wb?.let { v -> prof.label(ParamKind.WHITE_BALANCE, v) } ?: "—",
                shots = shots?.let { v -> EosValues.shotsText(v) } ?: "—",
                battery = CameraBattery.fromCode(battery),
                shutterCount = shutterCount,
                drive = drive?.let { v -> EosValues.label(EosValues.DRIVE, v) } ?: "—",
                metering = metering?.let { v -> EosValues.label(EosValues.METERING, v) } ?: "—",
                imageFormat = imageFormat?.let { v -> EosValues.imageFormatText(v) } ?: "—",
                colorSpace =
                    colorSpace?.let { v -> EosValues.label(EosValues.COLOR_SPACE, v) } ?: "—",
                pictureStyle =
                    pictureStyle?.let { v -> EosValues.label(EosValues.PICTURE_STYLE, v) } ?: "—",
                lens = c.text(PtpProp.LENS_NAME) ?: "",
                serial = c.text(PtpProp.SERIAL_NUMBER) ?: "",
                owner = c.text(PtpProp.OWNER) ?: "",
                nickname = c.text(PtpProp.CAMERA_NICKNAME) ?: "",
                firmware =
                    c.value(PtpProp.FIRMWARE_VERSION)?.let { EosValues.firmwareText(it) } ?: "",
                /* 存储信息不随事件刷新，只在拉到 StorageInfo 时覆盖，否则保留旧值 */
                storageType =
                    storage?.let { s -> EosValues.storageTypeText(s.storageType) }
                        ?: it.storageType,
                storageLabel =
                    storage?.let { s ->
                        s.description.takeIf { t -> t.isNotBlank() }
                            ?: s.volumeLabel.takeIf { t -> t.isNotBlank() }
                            ?: "—"
                    } ?: it.storageLabel,
                storageTotal =
                    storage?.let { s -> EosValues.bytesText(s.maxCapacity) } ?: it.storageTotal,
                storageFree =
                    storage?.let { s -> EosValues.bytesText(s.freeSpaceInBytes) } ?: it.storageFree,
                storageFreeImages =
                    storage?.let { s ->
                        if (s.freeSpaceInImages > 0) s.freeSpaceInImages.toString() else "—"
                    } ?: it.storageFreeImages,
                storageAccess =
                    storage?.let { s -> EosValues.accessText(s.accessCapability) }
                        ?: it.storageAccess,
                recordingSec = c.recordingSeconds,
                options = ParamKind.entries.associateWith { kind -> c.choices(kind, prof) },
                shutterCode = shutter,
                apertureCode = aperture,
                isoCode = iso,
                ecCode = ec,
                wbCode = wb,
            )
        }
        _recording.value = c.isRecording
        _exposureMode.value = c.shootingMode
        if (c.isManualFocus && _focusMode.value != c.focusMode) publishAf(AfState.IDLE)
        _focusMode.value = c.focusMode
    }

    /** 先收取机身已推送的属性，再读取 SD 卡容量。 */
    fun refreshDeviceInfo() {
        val c = camera ?: return
        viewModelScope.launch(cam) {
            awaitShutterTurn(c)
            var storage: StorageInfo? = null
            runCatching {
                c.drainEvents(maxBatches = 2)
                if (camera === c && conn?.isConnected == true) storage = c.storageInfo()
            }
            if (camera === c) syncParams(storage)
        }
    }

    // ---------------------------------------------------------------- 参数写

    suspend fun applyParameter(kind: ParamKind, code: Int): String? {
        val c = camera ?: return "相机未连接"
        return withContext(cam) {
            if (camera !== c) return@withContext "相机连接已变化"
            if (
                _busy.value ||
                    _intervalRunning.value ||
                    _bulbOpen.value ||
                    c.shutterSequenceActive ||
                    c.isFocusing
            ) {
                return@withContext "相机正在对焦或拍摄"
            }
            val profile = _profile.value
            val mode = c.shootingMode
            if (!profile.isEditable(kind, mode)) return@withContext "当前机身模式无法调整${kind.label}"
            if (c.choices(kind, profile).none { it.code == code }) {
                return@withContext "机身当前不支持所选数值"
            }
            try {
                c.setParameter(kind, code)
                if (camera !== c) return@withContext "相机已断开"
                syncParams()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "设置${kind.label}失败：${e.message}")
                if (e is PtpException && e.ptpCode == PtpRc.DEVICE_BUSY) {
                    "相机正忙，请稍后重试"
                } else {
                    e.message ?: "相机拒绝了参数设置"
                }
            }
        }
    }

    // ---------------------------------------------------------------- 实时取景

    fun startLive() {
        val c = camera ?: return
        if (liveJob != null || liveStopping || _viewfinder.value.retryStop) return
        _viewfinder.value = ViewfinderState(ViewfinderPhase.STARTING)
        val job =
            viewModelScope.launch(cam, start = CoroutineStart.LAZY) {
                try {
                    if (camera !== c) return@launch
                    _status.value = str(R.string.st_lv_waiting)
                    var preferSmall = _lowLatencyPreview.value
                    changeViewfinderOutput(c, retryDelayMs = 160) { c.startLiveView(preferSmall) }
                    var cadence = PreviewCadence()
                    var missedSince: Long? = null
                    var waitingForFrameSince: Long? = null
                    var lastLog = SystemClock.elapsedRealtime()
                    var frameCount = 0
                    var receiveMs = 0L
                    val freshIntervals = PreviewIntervals()
                    var duplicateFrames = 0
                    var busyReplies = 0
                    var notReadyReplies = 0
                    fun resetStatistics(at: Long) {
                        lastLog = at
                        frameCount = 0
                        receiveMs = 0
                        duplicateFrames = 0
                        busyReplies = 0
                        notReadyReplies = 0
                    }
                    while (isActive && camera === c && !liveStopping) {
                        if (c.shutterSequenceActive) {
                            missedSince = null
                            waitingForFrameSince = null
                            delay(20)
                            continue
                        }
                        if (!viewfinderVisible.value) {
                            _frame.value = null
                            missedSince = null
                            waitingForFrameSince = null
                            if (
                                withTimeoutOrNull(5_000L) { viewfinderVisible.first { it } } ==
                                    null && camera === c && !c.shutterSequenceActive
                            )
                                c.keepPreviewSessionAlive()
                            resetStatistics(SystemClock.elapsedRealtime())
                            freshIntervals.clear()
                            continue
                        }
                        val desired = _lowLatencyPreview.value
                        if (desired != preferSmall && !_recording.value && !c.isFocusing) {
                            preferSmall = desired
                            try {
                                c.configurePreviewOutput(preferSmall)
                                _frame.value = null
                                missedSince = null
                                waitingForFrameSince = null
                                cadence = PreviewCadence()
                            } catch (e: PtpException) {
                                if (e.ptpCode == 0) throw e
                                AppLog.w(TAG, "机身暂未接受取景画质切换：${e.message}")
                            }
                        }
                        val starting = _viewfinder.value.phase == ViewfinderPhase.STARTING
                        val requestAt = SystemClock.elapsedRealtime()
                        val poll = c.grabFrame(timeoutMs = if (starting) 8_000 else 3_000)
                        val receivedAt = SystemClock.elapsedRealtimeNanos()
                        coroutineContext.ensureActive()
                        if (liveStopping || camera !== c) break
                        if (!viewfinderVisible.value) continue
                        val now = SystemClock.elapsedRealtime()
                        if (poll is PreviewPoll.Frame) {
                            val jpeg = poll.jpeg
                            missedSince = null
                            waitingForFrameSince = null
                            val previous = _frame.value?.jpeg
                            val fresh = previous == null || !previous.image.matches(jpeg.image)
                            if (previous == null || fresh) {
                                _frame.value = LiveFrame(jpeg, receivedAt)
                                freshIntervals.record(receivedAt)
                            } else {
                                duplicateFrames++
                                if (!previous.hasSameFocus(jpeg)) {
                                    _frame.value =
                                        LiveFrame(jpeg.copy(image = previous.image), receivedAt)
                                }
                            }
                            if (
                                starting &&
                                    _viewfinder.compareAndSet(
                                        ViewfinderState(ViewfinderPhase.STARTING),
                                        ViewfinderState(ViewfinderPhase.STREAMING),
                                    )
                            ) {
                                _status.value = str(R.string.st_lv_on)
                            }
                            frameCount++
                            receiveMs += now - requestAt
                            if (now - lastLog >= 5_000L) {
                                val freshFps =
                                    (frameCount - duplicateFrames) * 1000L / (now - lastLog)
                                AppLog.d(
                                    TAG,
                                    "相机取景：新帧 ${freshFps}fps，平均命令 ${receiveMs / frameCount}ms，" +
                                        "JPEG ${jpeg.image.length / 1024}KiB，" +
                                        "重复 $duplicateFrames，忙 $busyReplies，未就绪 $notReadyReplies，" +
                                        freshIntervals.report(),
                                )
                                resetStatistics(now)
                            }
                            delay(
                                cadence.afterFrame(
                                    SystemClock.elapsedRealtime() - requestAt,
                                    preferSmall,
                                    fresh,
                                )
                            )
                        } else if (poll is PreviewPoll.Miss) {
                            val since = missedSince ?: now.also { missedSince = it }
                            if (poll == PreviewPoll.Miss.BUSY) {
                                busyReplies++
                                waitingForFrameSince = null
                            } else {
                                notReadyReplies++
                                if (waitingForFrameSince == null) waitingForFrameSince = now
                            }
                            val waiting = waitingForFrameSince
                            if (
                                waiting != null &&
                                    now - waiting >= 1_500L &&
                                    !_recording.value &&
                                    !c.isFocusing
                            ) {
                                try {
                                    if (c.selectNextPreviewOutput()) {
                                        missedSince = null
                                        waitingForFrameSince = null
                                        cadence = PreviewCadence()
                                        continue
                                    }
                                } catch (e: PtpException) {
                                    if (e.ptpCode != PtpRc.DEVICE_BUSY) throw e
                                }
                            }
                            if (now - since >= 8_000L) {
                                throw PtpException(if (starting) "相机尚未返回取景首帧" else "取景画面中断")
                            }
                            delay(cadence.afterMiss(poll, preferSmall))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "取景失败", e)
                    val failedState = _viewfinder.value
                    if (camera === c && failedState.phase.isReceivingFrames()) {
                        val reason = e.message ?: e.javaClass.simpleName
                        _status.value = "取景失败：$reason"
                        _frame.value = null
                        cancelAf()
                        val cleanup = runCatching {
                            awaitShutterTurn(c)
                            c.stopLiveView()
                        }
                        if (camera === c) {
                            _viewfinder.compareAndSet(
                                failedState,
                                ViewfinderState(
                                    ViewfinderPhase.FAILED,
                                    reason,
                                    retryStop = cleanup.isFailure,
                                ),
                            )
                        }
                    }
                } finally {
                    if (liveJob === coroutineContext[Job]) liveJob = null
                }
            }
        liveJob = job
        job.start()
    }

    fun stopLive() {
        val c = camera ?: return
        val state = _viewfinder.value
        if (!state.phase.isReceivingFrames() && !state.retryStop) return
        _viewfinder.value = ViewfinderState(ViewfinderPhase.STOPPING)
        cancelAf()
        liveJob?.cancel()
        _frame.value = null
        viewModelScope.launch {
            try {
                withContext(cam) {
                    changeViewfinderOutput(c, retryDelayMs = 100) { c.stopLiveView() }
                }
                if (camera === c) _viewfinder.value = ViewfinderState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "结束取景失败：${e.message}")
                if (camera === c)
                    _viewfinder.value =
                        ViewfinderState(
                            ViewfinderPhase.FAILED,
                            "关闭取景失败：${e.message.orEmpty()}",
                            retryStop = true,
                        )
            } finally {
                if (camera === c) liveJob = null
            }
        }
    }

    private suspend fun changeViewfinderOutput(
        c: EosCamera,
        retryDelayMs: Long,
        command: () -> Unit,
    ) {
        for (attempt in 1..4) {
            awaitShutterTurn(c)
            try {
                command()
                return
            } catch (e: PtpException) {
                if (e.ptpCode != PtpRc.DEVICE_BUSY || attempt == 4) throw e
                delay(retryDelayMs * attempt)
            }
        }
    }

    // ------------------------------------------------------------------ 拍摄

    fun pressShutter() {
        val c = camera ?: return
        if (_intervalRunning.value || !_busy.compareAndSet(false, true)) return
        cancelAf()
        _captureFeedback.value = CaptureFeedback(CapturePhase.CAPTURING, "正在发送拍摄指令")
        viewModelScope.launch(cam) {
            try {
                awaitShutterTurn(c)
                c.drainEvents(maxBatches = 1)
                if (camera !== c) return@launch
                when {
                    _bulbOpen.value -> {
                        c.bulbEnd()
                        if (camera === c) {
                            _bulbOpen.value = false
                            _captureFeedback.value = CaptureFeedback()
                        }
                        return@launch
                    }
                    c.isMovieMode -> {
                        record(c)
                        return@launch
                    }
                    c.usesBulb -> {
                        c.bulbStart()
                        if (camera === c) {
                            _bulbOpen.value = true
                            _captureFeedback.value = CaptureFeedback()
                        }
                        return@launch
                    }
                }
                _captureFeedback.value = CaptureFeedback(CapturePhase.CAPTURING, "正在拍照")
                _status.value = str(R.string.st_shooting)
                val shots = c.capture {
                    if (camera === c) {
                        _captureFeedback.value =
                            CaptureFeedback(CapturePhase.CAPTURING, "快门已触发，等待照片落盘")
                    }
                }
                if (camera !== c) return@launch
                _status.value = str(R.string.msg_shot_done, shots.size)
                _captureFeedback.value = CaptureFeedback(CapturePhase.SAVED, "已保存到相机")
                syncParams()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                AppLog.e(TAG, "拍摄失败", t)
                if (camera === c) {
                    val reason = t.message ?: "拍摄失败"
                    _status.value = reason
                    _captureFeedback.value = CaptureFeedback(CapturePhase.FAILED, reason)
                }
            } finally {
                if (camera === c) _busy.value = false
            }
        }
    }

    fun afDrive() = runAf(null)

    internal fun touchAf(target: FocusPoint?) {
        if (target == null) publishAf(AfState.POSITION_UNAVAILABLE) else runAf(target)
    }

    private fun runAf(target: FocusPoint?) {
        val c = camera ?: return
        if (afJob != null || _busy.value || _intervalRunning.value || _bulbOpen.value) return
        if (!c.isAutoFocus || liveStopping) return
        _af.value = AfState.WORKING
        val job =
            viewModelScope.launch(cam, start = CoroutineStart.LAZY) {
                val request = coroutineContext[Job]
                try {
                    if (camera !== c) return@launch
                    val state =
                        when (c.focus(target)) {
                            FocusOutcome.FOCUSED -> AfState.OK
                            FocusOutcome.FAILED -> AfState.FAIL
                            FocusOutcome.CANCELLED -> AfState.IDLE
                            FocusOutcome.UNCONFIRMED -> AfState.UNCONFIRMED
                        }
                    if (camera === c && afJob === request) publishAf(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (camera === c && afJob === request) {
                        AppLog.w(TAG, "自动对焦操作失败：${e.message ?: e.javaClass.simpleName}")
                        publishAf(AfState.ERROR)
                    }
                } finally {
                    if (afJob === request) afJob = null
                }
            }
        afJob = job
        job.start()
    }

    private fun publishAf(state: AfState) {
        _af.value = state
        _afSeq.update { it + 1L }
    }

    private fun cancelAf() {
        val job = afJob ?: return
        afJob = null
        job.cancel()
        if (_af.value == AfState.WORKING) publishAf(AfState.IDLE)
    }

    private suspend fun record(c: EosCamera) {
        val starting = !c.isRecording
        _captureFeedback.value =
            CaptureFeedback(CapturePhase.CAPTURING, if (starting) "正在开始录像" else "正在停止录像")
        c.setRecording(starting)
        if (camera !== c) return
        val confirmed = withTimeoutOrNull(3_000L) { _recording.first { it == starting } } != null
        if (camera === c) {
            _captureFeedback.value =
                if (confirmed) CaptureFeedback()
                else CaptureFeedback(CapturePhase.FAILED, "未收到机身录像状态，请确认相机指示")
        }
    }

    // ---------------------------------------------------------------- 间隔拍摄

    fun startInterval(count: Int, intervalSec: Int, delaySec: Int, halfPressAf: Boolean) {
        val c = camera ?: return
        if (
            _intervalRunning.value ||
                _busy.value ||
                _bulbOpen.value ||
                _exposureMode.value == ExposureMode.MOVIE
        )
            return
        cancelAf()
        _intervalStopping.value = false
        _intervalRunning.value = true
        _captureFeedback.value = CaptureFeedback()
        viewModelScope.launch(cam) {
            try {
                if (camera !== c) return@launch
                if (_intervalStopping.value) return@launch
                val plan =
                    EosCamera.IntervalPlan(
                        count = count.coerceAtLeast(0),
                        intervalMs = intervalSec.coerceAtLeast(1) * 1000L,
                        startDelayMs = delaySec.coerceAtLeast(0) * 1000L,
                        halfPressAf = halfPressAf,
                    )
                val done =
                    c.runInterval(
                        plan,
                        onProgress = { p ->
                            if (camera !== c) throw CancellationException("相机会话已关闭")
                            if (_intervalStopping.value) c.cancelInterval()
                            _intervalProgress.value = p
                            _status.value =
                                if (p.shooting) {
                                    str(R.string.st_shooting)
                                } else {
                                    "${p.done}/${p.total} · ${p.waitMs / 1000}s"
                                }
                        },
                    )
                if (camera === c) {
                    _status.value =
                        if (done == plan.count && done > 0) {
                            "间隔拍摄完成 · $done 张"
                        } else {
                            "间隔拍摄已停止 · $done 张"
                        }
                    _captureFeedback.value = CaptureFeedback(CapturePhase.SAVED, _status.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.e(TAG, "间隔拍摄失败", t)
                if (camera === c) {
                    _status.value = t.message ?: "间隔拍摄失败"
                    _captureFeedback.value = CaptureFeedback(CapturePhase.FAILED, _status.value)
                }
            } finally {
                if (camera === c) {
                    _intervalRunning.value = false
                    _intervalStopping.value = false
                    _intervalProgress.value = null
                    syncParams()
                }
            }
        }
    }

    /** 只置取消标志，不能排进 [cam]，否则会排在拍摄循环后面永远轮不到。 */
    fun stopInterval() {
        if (!_intervalRunning.value) return
        _intervalStopping.value = true
        camera?.cancelInterval()
        _status.value = str(R.string.btn_interval_stop)
    }

    // ------------------------------------------------------------------ 图库

    fun reloadFiles(onlyIfNeeded: Boolean = false) {
        val c = camera ?: return
        if (_link.value != Link.ONLINE || _filesLoading.value || (onlyIfNeeded && filesLoaded))
            return
        _filesLoading.value = true
        _filesError.value = null
        val beforeScan = _files.value
        filesLoadJob = viewModelScope.launch {
            try {
                val list = withContext(cam) { c.listFolder { awaitShutterTurn(c) } }
                if (camera !== c) return@launch
                _files.update { current ->
                    if (current === beforeScan) list
                    else {
                        val priorHandles = beforeScan.mapTo(HashSet()) { it.handle }
                        val arrivals = current.filter { it.handle !in priorHandles }
                        (list + arrivals).distinctBy { it.handle }.sortedByDescending { it.name }
                    }
                }
                filesLoaded = true
                AppLog.i(TAG, "相机目录已读取：${list.size} 个文件")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "列目录失败", e)
                if (camera === c) _filesError.value = e.message ?: e.javaClass.simpleName
            } finally {
                if (camera === c) {
                    filesLoadJob = null
                    _filesLoading.value = false
                }
            }
        }
    }

    suspend fun thumb(file: CameraFile): ByteArray? {
        val c = camera ?: return null
        return withContext(cam) {
            if (camera !== c) return@withContext null
            awaitReadTurn(c)
            thumbnails.get(file.handle)?.let {
                return@withContext it
            }
            val bytes = c.loadThumb(file.handle) ?: return@withContext null
            if (camera !== c) return@withContext null
            thumbnails.put(file.handle, bytes)
            bytes
        }
    }

    fun canPreviewFull(file: CameraFile): Boolean =
        !file.isRaw && !file.isMovie && file.size in 1L..MAX_PREVIEW_BYTES.toLong()

    suspend fun loadPreview(file: CameraFile, full: Boolean, onProgress: (Int) -> Unit): Bitmap? {
        val c = camera ?: return null
        val bytes =
            if (full) {
                if (!canPreviewFull(file)) return null
                withContext(cam) {
                    if (camera !== c) return@withContext null
                    val out =
                        object : ByteArrayOutputStream() {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                if (len > MAX_PREVIEW_BYTES - size())
                                    throw PtpException("预览文件超出大小限制")
                                super.write(b, off, len)
                            }
                        }
                    var lastPercent = -1
                    c.downloadTo(
                        file,
                        out,
                        beforeRead = { awaitReadTurn(c) },
                        completeTransfer = false,
                    ) { done, total ->
                        val percent =
                            if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    out.toByteArray()
                }
            } else {
                thumb(file)
            }
        if (bytes == null || camera !== c) return null
        return withContext(Dispatchers.Default) { Bitmaps.decode(bytes, if (full) 2048 else 1024) }
    }

    fun enqueueTransfers(files: List<CameraFile>) {
        val c = camera ?: return
        viewModelScope.launch(cam) {
            if (camera !== c) return@launch
            val current = _files.value.associateBy { it.handle }
            enqueueOnCamera(
                c,
                files.mapNotNull { file ->
                    current[file.handle]?.takeIf { it.name == file.name && it.size == file.size }
                },
            )
        }
    }

    private fun enqueueOnCamera(c: EosCamera, files: List<CameraFile>) {
        val current = _transfers.value.associateBy { it.file.handle }
        for (file in files) {
            val phase = current[file.handle]?.phase
            if (
                file.handle in autoSavedHandles ||
                    phase == TransferPhase.QUEUED ||
                    phase == TransferPhase.COPYING
            )
                continue
            pendingTransfers[file.handle] = file
            updateTransfer(c, FileTransfer(file, TransferPhase.QUEUED))
        }
        if (transferJob?.isActive == true || pendingTransfers.isEmpty()) return
        transferJob =
            viewModelScope.launch(cam) {
                delay(120)
                while (camera === c) {
                    val file = pendingTransfers.values.firstOrNull() ?: break
                    awaitReadTurn(c, transferHandle = file.handle)
                    pendingTransfers.remove(file.handle)
                    saveTransfer(c, file)
                }
            }
    }

    private suspend fun awaitReadTurn(c: EosCamera, transferHandle: Int? = null) {
        while (
            camera === c &&
                (_busy.value ||
                    c.captureInProgress ||
                    _bulbOpen.value ||
                    _recording.value ||
                    c.isFocusing ||
                    c.shutterSequenceActive ||
                    (transferHandle != null &&
                        isPreviewPrioritized() &&
                        !c.isHostTransfer(transferHandle)))
        ) {
            delay(50)
        }
        if (camera !== c) throw CancellationException("相机会话已关闭")
    }

    private suspend fun awaitShutterTurn(c: EosCamera) {
        while (camera === c && (c.shutterSequenceActive || c.isFocusing)) delay(20)
        if (camera !== c) throw CancellationException("相机会话已关闭")
    }

    private suspend fun saveTransfer(c: EosCamera, file: CameraFile) {
        val ctx = getApplication<Application>()
        updateTransfer(c, FileTransfer(file, TransferPhase.COPYING))
        try {
            withContext(Dispatchers.IO) {
                MediaStoreSaver.save(ctx, file.name, MediaStoreSaver.mimeOf(file), file.size) { out
                    ->
                    withContext(cam) {
                        var lastPercent = -1
                        c.downloadTo(
                            file,
                            out,
                            beforeRead = { awaitReadTurn(c, transferHandle = file.handle) },
                            previewPriority = ::isPreviewPrioritized,
                        ) { done, total ->
                            val percent =
                                if (total > 0L && done >= 0L)
                                    (done * 100 / total).toInt().coerceIn(0, 100)
                                else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateTransfer(
                                    c,
                                    FileTransfer(file, TransferPhase.COPYING, percent),
                                )
                            }
                        }
                    }
                }
            }
            if (camera !== c) return
            autoSavedHandles += file.handle
            updateTransfer(c, FileTransfer(file, TransferPhase.SAVED, 100))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "回传 ${file.name} 失败：${e.message}")
            updateTransfer(c, FileTransfer(file, TransferPhase.FAILED, error = e.message ?: "传输失败"))
        }
    }

    private fun updateTransfer(c: EosCamera, item: FileTransfer) {
        _transfers.update { current ->
            if (camera !== c) return@update current
            val existing = current.indexOfFirst { it.file.handle == item.file.handle }
            val list = current.toMutableList()
            if (existing < 0) list.add(0, item) else list[existing] = item
            if (list.size > 100) {
                val oldSaved = list.indexOfLast {
                    it.phase == TransferPhase.SAVED && it.file.handle != item.file.handle
                }
                if (oldSaved >= 0) list.removeAt(oldSaved)
            }
            list
        }
    }

    fun setAutoTransfer(on: Boolean) {
        _autoTransfer.value = on
        prefs.edit().putBoolean(KEY_AUTO_TRANSFER, on).apply()
    }

    // ---------------------------------------------------------------- 蓝牙遥控

    /** 蓝牙链路不接受取景请求，所以它只做四件事：找相机、配对、按快门、把热点要回来。 每条动作前都先看 [CanonBle.isReady]，没就绪就把原因丢到提示里，不要静默失败。 */
    fun setRemoteMode(m: RemoteMode) {
        _mode.value = m
    }

    fun bleScan() {
        _bleDevices.value = emptyList()
        ble.startScan()
    }

    fun bleStopScan() = ble.stopScan()

    fun bleConnect(device: CanonBle.Device) {
        _bleMessage.value = "正在连接 ${device.name}"
        ble.connect(device)
    }

    fun bleDisconnect() {
        ble.disconnect()
        _bleHotspot.value = null
        _bleName.value = ""
        _bleMessage.value = "蓝牙已断开"
    }

    fun bleShoot() = bleGuard { ble.release() }

    fun bleFocus() = bleGuard { ble.focus() }

    fun bleRecord(start: Boolean) = bleGuard { ble.record(start) }

    fun bleWake() = bleGuard { ble.wake() }

    fun bleSleep() = bleGuard { ble.sleep() }

    /** 让相机切到拍摄/回放模式；蓝牙按下快门没反应，十有八九是停在回放模式。 */
    fun bleSetMode(shooting: Boolean) = bleGuard { ble.setMode(if (shooting) 0x02 else 0x01) }

    fun bleReadWifi() {
        _bleHotspot.value = null
        bleGuard { ble.readWifi() }
    }

    /** 用蓝牙读回的凭据直接接管 Wi-Fi 链路，省掉手输热点密码。 */
    fun connectViaBleHotspot() {
        val h = _bleHotspot.value ?: return
        val ssid = h.ssid?.takeIf { it.isNotBlank() } ?: return
        val pass = h.pass ?: return
        if (_link.value != Link.OFFLINE) return
        bleGuard { ble.openCameraAp { connect(ssid, pass) } }
    }

    private fun bleGuard(block: () -> Unit) {
        if (!ble.isReady) {
            _bleMessage.value =
                when (_blePhase.value) {
                    CanonBle.Phase.OFF -> "蓝牙未连接"
                    CanonBle.Phase.SCANNING -> "正在搜索遥控器"
                    CanonBle.Phase.WAITING_CONFIRM -> "先在相机屏幕上点「确定」完成配对"
                    CanonBle.Phase.ERROR -> _bleMessage.value.ifBlank { "蓝牙通道异常" }
                    else -> "蓝牙正在建立连接"
                }
            return
        }
        block()
    }

    override fun onCleared() {
        val closing = resetSession()
        cam.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            try {
                closing?.close()
            } finally {
                cam.close()
            }
        }
        runCatching { ble.disconnect() }
        CameraWifi.release(getApplication())
        super.onCleared()
    }
}
