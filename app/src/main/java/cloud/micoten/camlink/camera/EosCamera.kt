package cloud.micoten.camlink.camera

import android.os.SystemClock
import cloud.micoten.camlink.ptpip.CameraEvent
import cloud.micoten.camlink.ptpip.DataSink
import cloud.micoten.camlink.ptpip.EosEventParser
import cloud.micoten.camlink.ptpip.PtpException
import cloud.micoten.camlink.ptpip.PtpHandle
import cloud.micoten.camlink.ptpip.PtpIpConnection
import cloud.micoten.camlink.ptpip.PtpOp
import cloud.micoten.camlink.ptpip.PtpProp
import cloud.micoten.camlink.ptpip.PtpRc
import cloud.micoten.camlink.ptpip.PtpResult
import cloud.micoten.camlink.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * 相机上的一个文件条目（对应 `PTPCANONFolderEntry`）。
 *
 * @param time 相机本地时间，Unix 秒；0 表示没取到
 */
data class CameraFile(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val size: Long,
    val name: String,
    val time: Long = 0L,
    val readOnly: Boolean = false,
) {
    val isCr3: Boolean
        get() = format == 0xB108 || name.endsWith(".cr3", ignoreCase = true)

    val isRaw: Boolean
        get() = isCr3 || format == 0xB103 || name.endsWith(".cr2", ignoreCase = true)

    val isMovie: Boolean
        get() =
            when (format) {
                0x300D,
                0xB104,
                0xB105,
                0xB982 -> true
                else ->
                    name.endsWith(".mp4", ignoreCase = true) ||
                        name.endsWith(".mov", ignoreCase = true)
            }

    val isDirectory: Boolean
        get() = format == 0x3001
}

/**
 * 标准 PTP StorageInfo 数据集（ISO 15740 第 5.5 节）：一张存储卡的类型与容量。
 *
 * libgphoto2 在 EOS 机型上同样走标准 `GetStorageInfo 0x1005` （见 library.c `storage_info_func`），所以这里按标准布局解，
 * 不碰 EOS 私有的 0x9102 —— 真机对它的返回块没有公开文档。
 *
 * 「传输速度」机身不提供任何 PTP 属性，主流工具里的读写速率都是实测值， 这里如实留白，由界面说明。
 */
data class StorageInfo(
    val storageId: Int,
    /** 0x0003 = 可移动 RAM（SD 卡一类）。 */
    val storageType: Int,
    /** 0x0000 = 可读写，0x0001 = 只读。 */
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpaceInBytes: Long,
    val freeSpaceInImages: Long,
    val description: String,
    val volumeLabel: String,
)

/**
 * 间隔拍摄被用户停掉。
 *
 * 用来把「用户点了停止」和真故障分开：前者不该在界面上报红，也不该写成错误日志。 抛这个异常只为从 [EosCamera.capture] 的等待循环里跳出来。
 */
class IntervalCancelledException : RuntimeException("已停止间隔拍摄")

/**
 * 佳能 EOS 相机控制层。
 *
 * 只依赖 [PtpIpConnection] 的传输能力，把 EOS 私有扩展（0x91xx 操作码 + 0xD1xx 属性） 封成拍照 App
 * 需要的动作：初始化、参数读写、实时取景、快门、对焦、录像、图库、下载。
 *
 * 除 [conn] 之外全部状态都在这里自持，调用方（ViewModel）只需保证所有方法都在 IO 线程上串行调用。事件由事件通道线程通过工作线程回灌，和命令事务共用
 * [PtpIpConnection] 的锁，因此不会把一次事务截成两半。
 */
class EosCamera(private val conn: PtpIpConnection) {
    private data class CachedChoices(
        val profile: CameraProfile,
        val available: List<Long>,
        val choices: List<Choice>,
    )

    companion object {
        private const val TAG = "EosCamera"

        /**
         * 分块下载的块大小。
         *
         * libgphoto2 用的是标准 `GetPartialObject 0x101B` 而不是 EOS 私有的 0x9107， 且块大小取 1MB —— 注释里明确写了 EOS R
         * 之后不接受 5MB。
         */
        private const val BLOB_SIZE = 1 * 1024 * 1024

        /** 半按之后等 FocusInfo 的上限，与 libgphoto2 的 500ms 一致。 */
        private const val FOCUS_WAIT_MS = 500L

        /** 相机状态里的“忙”。`eos_camerastatus == 1` 时拍摄会被相机拒掉。 */
        private const val STATUS_BUSY = 1
        private const val CAPTURE_READY_TIMEOUT_MS = 2_500L
        private const val CAPTURE_TO_HOST = 4L
        private const val CAPTURE_TO_SD = 2L
        private const val EVENT_TIMEOUT_MS = 5_000
    }

    // ------------------------------------------------------------ 事件与缓存

    private val propValues = ConcurrentHashMap<Int, Long>()
    private val propTexts = ConcurrentHashMap<Int, String>()
    private var captureFiles: MutableMap<Int, CameraFile>? = null
    private val pressedButtons = LinkedHashSet<Int>(2)

    /** OLC 状态块的版本号（属性 0xD199），解 OLC 事件必须先用它。 */
    @Volatile
    var olcVersion: Int = 0
        private set

    @Volatile
    var cameraStatus: Int = 0
        private set

    @Volatile
    var liveViewActive: Boolean = false
        private set

    private var captureDestinations: List<Long>? = null
    private val exposureOptions = ConcurrentHashMap<Int, List<Long>>()
    private val cachedChoices = ConcurrentHashMap<ParamKind, CachedChoices>()
    var captureInProgress: Boolean = false
        private set

    var shutterSequenceActive: Boolean = false
        private set

    @Volatile private var modeProperty = PtpProp.AUTO_EXPOSURE_MODE

    /**
     * 录制已进行的秒数。
     *
     * 机身会周期性推 `RecordingTime 0xC195`，但间隔并不保证刚好一秒，网络抖一下 就会整块迟到，界面上的秒数跟着卡住不动。这里取「事件值」与「本地钟表」里
     * 较大的那个：事件先到就报事件值，事件断流则靠本地钟表接着走。
     */
    val recordingSeconds: Int
        get() {
            val since = recordingStartedAt
            if (since == 0L) return recordingEventSeconds
            val local = ((SystemClock.elapsedRealtime() - since) / 1000L).toInt()
            return maxOf(recordingEventSeconds, local)
        }

    @Volatile private var recordingEventSeconds: Int = 0

    /** 本次录制开始的本地时刻；0 表示没在录。 */
    @Volatile private var recordingStartedAt: Long = 0L

    var storageIds: List<Int> = emptyList()
        private set

    /** 事件回调。在事件工作线程上触发，界面自己切主线程。 */
    @Volatile
    var onEvents: ((events: List<CameraEvent>, parametersChanged: Boolean) -> Unit)? = null

    private val transferRequests = ConcurrentHashMap.newKeySet<Int>()
    private var previousLiveOutput: Long? = null
    private var restoreEvfMode = false
    private val previewFallbacks = ArrayDeque<Long>()
    private var evfOutputs: List<Long>? = null
    private var recordingTargets: List<Long>? = null
    private var nativeAfActive = false
    private var lastLiveKeepAlive = 0L
    private var bulbOpen = false

    // ---------------------------------------------------------------- 生命周期

    /** 配对完成后只执行连接所需的命令。存储卡与取景属性在对应页面读取。 */
    fun open() =
        conn.withCommandTimeout(8_000) {
            try {
                conn.openSession()
            } catch (e: java.io.IOException) {
                throw PtpException("无法打开 PTP 会话：${e.message}", cause = e)
            }
            AppLog.i(TAG, "进入远程控制模式")
            conn.command(PtpOp.EOS_SET_REMOTE_MODE, 1).requireOk()
            conn.command(PtpOp.EOS_SET_EVENT_MODE, 1).requireOk()
        }

    suspend fun initializeEvents() {
        repeat(4) { attempt ->
            try {
                readEventBatch()
                AppLog.i(TAG, "机身事件通道已就绪")
                return
            } catch (e: PtpException) {
                if (
                    attempt == 3 ||
                        (e.ptpCode != PtpRc.DEVICE_BUSY && e.ptpCode != PtpRc.GENERAL_ERROR)
                )
                    throw e
                delay((attempt + 1) * 120L)
            }
        }
    }

    fun enableOlc() {
        val olc =
            conn.withCommandTimeout(EVENT_TIMEOUT_MS) {
                conn.command(PtpOp.EOS_SET_REQUEST_OLC_INFO_GROUP, 0x00001FFF)
            }
        if (!olc.ok) AppLog.w(TAG, "SetRequestOLCInfoGroup 被拒：${olc.describe()}")
    }

    fun close() {
        runCatching { if (bulbOpen && conn.isConnected) bulbEnd() }
        runCatching { if (conn.isConnected) finishShutterRelease() }
        runCatching { stopLiveView() }
        runCatching { if (conn.isConnected) conn.command(PtpOp.CLOSE_SESSION) }
        runCatching { conn.close() }
    }

    fun keepAlive() {
        runCatching { conn.command(PtpOp.EOS_KEEP_DEVICE_ON) }
    }

    // -------------------------------------------------------------- 事件处理

    /**
     * 把相机侧积压的事件全部取空并解析。
     *
     * `GetEvent 0x9116` 不带参数，返回一坨紧凑 TLV；取到空块（只剩终止项）即停。
     */
    fun drainEvents(maxBatches: Int = 64): List<CameraEvent> {
        val all = ArrayList<CameraEvent>(16)
        repeat(maxBatches) {
            val events =
                try {
                    readEventBatch()
                } catch (e: PtpException) {
                    if (e.ptpCode != PtpRc.DEVICE_BUSY) AppLog.w(TAG, "GetEvent 失败：${e.message}")
                    return all
                }
            if (events.isEmpty()) return all
            all += events
        }
        return all
    }

    private fun readEventBatch(): List<CameraEvent> {
        val r =
            conn.withCommandTimeout(EVENT_TIMEOUT_MS) { conn.commandWithData(PtpOp.EOS_GET_EVENT) }
        if (!r.ok) throw PtpException("读取机身事件失败：${r.describe()}", r.code)
        val events =
            try {
                EosEventParser.parse(r.data, olcVersion)
            } catch (e: Exception) {
                throw PtpException("相机事件解析失败", cause = e)
            }
        if (events.isEmpty()) return events
        if (events.any { it !is CameraEvent.Olc }) {
            AppLog.v(
                TAG,
                "事件块 ${r.data?.size}B：" +
                    events.joinToString { e ->
                        when (e) {
                            is CameraEvent.ObjectAdded ->
                                "ObjectAdded(${e.name}/h=0x%08X)".format(e.handle)
                            is CameraEvent.PropChanged ->
                                "Prop(0x%04X=0x%X)".format(e.propCode, e.value)
                            is CameraEvent.CameraStatus -> "Status(${e.status})"
                            is CameraEvent.Raw -> "Raw(0x%04X/${e.size}B)".format(e.eventCode)
                            else -> e.javaClass.simpleName
                        }
                    },
            )
        }
        applyEvents(events)
        return events
    }

    private fun applyEvents(events: List<CameraEvent>) {
        val previousMode = shootingMode
        var dialChanged = false
        var exposureModeChanged = false
        var parametersChanged = false
        for (e in events) {
            when (e) {
                is CameraEvent.PropChanged -> {
                    val previous = propValues.put(e.propCode, e.value)
                    if (previous != e.value) {
                        parametersChanged = true
                        when (e.propCode) {
                            PtpProp.AE_MODE_DIAL -> dialChanged = true
                            PtpProp.AUTO_EXPOSURE_MODE -> {
                                exposureModeChanged =
                                    previous != null ||
                                        value(PtpProp.AE_MODE_DIAL) == null ||
                                        e.value == ExposureMode.BULB.code.toLong() ||
                                        e.value == ExposureMode.MOVIE.code.toLong()
                            }
                        }
                    }
                    e.text?.let { text ->
                        if (propTexts.put(e.propCode, text) != text) parametersChanged = true
                    }
                    if (e.propCode == PtpProp.OLC_INFO_VERSION && e.value > 0) {
                        olcVersion = e.value.toInt()
                    }
                }
                is CameraEvent.Olc -> {
                    if (updateExposureFromOlc(PtpProp.SHUTTER_SPEED, e.shutterSpeed))
                        parametersChanged = true
                    if (updateExposureFromOlc(PtpProp.APERTURE, e.aperture))
                        parametersChanged = true
                    if (updateExposureFromOlc(PtpProp.ISO_SPEED, e.iso)) parametersChanged = true
                }
                is CameraEvent.CameraStatus -> cameraStatus = e.status
                is CameraEvent.AvailListChanged ->
                    e.values?.let { values ->
                        when (e.propCode) {
                            PtpProp.CAPTURE_DESTINATION -> captureDestinations = values
                            PtpProp.EVF_OUTPUT_DEVICE -> evfOutputs = values
                            PtpProp.EVF_RECORD_STATUS -> recordingTargets = values
                            else -> Unit
                        }
                    }
                is CameraEvent.RecordingTime -> {
                    if (recordingEventSeconds != e.seconds) {
                        recordingEventSeconds = e.seconds
                        parametersChanged = true
                    }
                }
                is CameraEvent.ObjectAdded -> {
                    val files = captureFiles
                    if (files != null && e.handle != 0 && e.name.isNotBlank()) {
                        val file = CameraFile(e.handle, e.storageId, e.format, e.size, e.name)
                        if (!file.isDirectory && !file.isMovie) files.putIfAbsent(file.handle, file)
                    }
                }
                is CameraEvent.TransferRequested ->
                    if (e.handle != 0) transferRequests.add(e.handle)
                else -> Unit
            }
        }
        when {
            exposureModeChanged && exposureModeIsBulb() -> modeProperty = PtpProp.AUTO_EXPOSURE_MODE
            exposureModeChanged &&
                value(PtpProp.AUTO_EXPOSURE_MODE) == ExposureMode.MOVIE.code.toLong() ->
                modeProperty = PtpProp.AUTO_EXPOSURE_MODE
            dialChanged -> modeProperty = PtpProp.AE_MODE_DIAL
            exposureModeChanged -> modeProperty = PtpProp.AUTO_EXPOSURE_MODE
        }
        if (shootingMode != previousMode) {
            exposureOptions.clear()
            parametersChanged = true
        }
        for (e in events) {
            if (e is CameraEvent.AvailListChanged && e.propCode in PtpProp.exposureControls) {
                val values = e.values ?: continue
                if (values.isNotEmpty() && exposureOptions[e.propCode] != values) {
                    exposureOptions[e.propCode] = values
                    parametersChanged = true
                }
            }
        }
        syncRecordingClock()
        onEvents?.invoke(events, parametersChanged)
    }

    private fun updateExposureFromOlc(code: Int, value: Int?): Boolean {
        val reported = value?.toLong() ?: return false
        return propValues.put(code, reported) != reported
    }

    /** 按 `EVFRecordStatus` 启停本地录制计时，事件断流时界面才不会停在原地。 */
    private fun syncRecordingClock() {
        if (isRecording) {
            if (recordingStartedAt == 0L) {
                recordingStartedAt = SystemClock.elapsedRealtime()
                recordingEventSeconds = 0
            }
        } else {
            recordingStartedAt = 0L
            recordingEventSeconds = 0
        }
    }

    // ---------------------------------------------------------------- 属性

    fun value(code: Int): Long? = propValues[code]

    fun choices(kind: ParamKind, profile: CameraProfile): List<Choice> {
        val available = exposureOptions[kind.propertyCode] ?: return profile.table(kind)
        val cached = cachedChoices[kind]
        if (cached != null && cached.profile === profile && cached.available == available) {
            return cached.choices
        }

        val supportedCodes = available.toHashSet()
        val aliasesByLabel = HashMap<String, Choice>()
        for (choice in EosValues.of(kind)) {
            if (choice.code.toLong() in supportedCodes)
                aliasesByLabel.putIfAbsent(choice.label, choice)
        }
        val choices =
            profile
                .table(kind)
                .mapNotNull { choice ->
                    if (choice.code.toLong() in supportedCodes) choice
                    else aliasesByLabel[choice.label]
                }
                .distinctBy { it.code }
        cachedChoices[kind] = CachedChoices(profile, available, choices)
        return choices
    }

    fun text(code: Int): String? = propTexts[code]

    /**
     * 写数值属性。
     *
     * 数据格式（`ptp_canon_eos_setdevicepropvalue`）：8 字节头 `[u32 size][u32 propcode]`
     * + 值。数值型属性的 size 恒为 12，u8/u16 也只占前 1/2 字节，后面补零； 操作本身**不带任何操作参数**，属性码完全在数据里。
     */
    private fun setProp(code: Int, v: Long, cache: Boolean = true) {
        require(
            code != PtpProp.AUTO_EXPOSURE_MODE &&
                code != PtpProp.AE_MODE_DIAL &&
                code != PtpProp.FIXED_MOVIE &&
                code != PtpProp.FOCUS_MODE
        ) {
            "机身模式是只读状态"
        }
        val width = PtpProp.valueWidth(code)
        val data = ByteArray(12)
        putLe32(data, 0, 12)
        putLe32(data, 4, code)
        when (width) {
            1 -> data[8] = (v and 0xFF).toByte()
            2 -> putLe16(data, 8, (v and 0xFFFF).toInt())
            else -> putLe32(data, 8, v.toInt())
        }
        val r = conn.commandWithPayload(PtpOp.EOS_SET_DEVICE_PROP_VALUE_EX, data)
        if (!r.ok) {
            throw PtpException("设置属性 0x%04X=0x%X 失败：%s".format(code, v, r.describe()), r.code)
        }
        if (cache) propValues[code] = v
    }

    // ---- 参数快捷入口（码值必须来自 EosValues 的表，不能自己算） ----

    fun setParameter(kind: ParamKind, code: Int) = setProp(kind.propertyCode, code.toLong())

    // ------------------------------------------------------------- 实时取景

    fun startLiveView(preferSmall: Boolean) {
        if (liveViewActive) return

        drainEvents(maxBatches = 2)
        val mode = value(PtpProp.EVF_MODE)
        if (mode != 1L) {
            try {
                setProp(PtpProp.EVF_MODE, 1)
                if (mode == 0L && !isMovieMode) restoreEvfMode = true
            } catch (e: PtpException) {
                if (
                    e.ptpCode != PtpRc.DEVICE_BUSY &&
                        e.ptpCode != PtpRc.OPERATION_NOT_SUPPORTED &&
                        e.ptpCode != PtpRc.DEVICE_PROP_NOT_SUPPORTED &&
                        e.ptpCode != PtpRc.GENERAL_ERROR
                )
                    throw e
                AppLog.w(TAG, "机身暂不接受 EVFMode：${e.message}")
            }
        }
        val output = value(PtpProp.EVF_OUTPUT_DEVICE) ?: 0L
        if (previousLiveOutput == null) previousLiveOutput = output and PREVIEW_OUTPUT_MASK.inv()
        configurePreviewOutput(preferSmall)
        liveViewActive = true
        lastLiveKeepAlive = SystemClock.elapsedRealtime()
    }

    fun configurePreviewOutput(preferSmall: Boolean) {
        val current = value(PtpProp.EVF_OUTPUT_DEVICE) ?: 0L
        val bodyOutput = previousLiveOutput ?: current
        previewFallbacks.clear()
        previewFallbacks.addAll(
            previewOutputCandidates(bodyOutput, evfOutputs, preferSmall && !isMovieMode)
        )
        selectNextPreviewOutput()
    }

    fun selectNextPreviewOutput(): Boolean {
        while (previewFallbacks.isNotEmpty()) {
            val candidate = previewFallbacks.first()
            try {
                if (value(PtpProp.EVF_OUTPUT_DEVICE) != candidate)
                    setProp(PtpProp.EVF_OUTPUT_DEVICE, candidate)
                previewFallbacks.removeFirst()
                AppLog.i(TAG, "取景输出 0x%X，等待画面".format(candidate))
                return true
            } catch (e: PtpException) {
                if (
                    previewFallbacks.size == 1 ||
                        !conn.isConnected ||
                        (e.ptpCode != PtpRc.INVALID_DEVICE_PROP_VALUE &&
                            e.ptpCode != PtpRc.GENERAL_ERROR &&
                            e.ptpCode != PtpRc.PARAMETER_NOT_SUPPORTED)
                )
                    throw e
                previewFallbacks.removeFirst()
            }
        }
        return false
    }

    fun stopLiveView() {
        if (!liveViewActive && previousLiveOutput == null && !restoreEvfMode) return
        liveViewActive = false
        val output = previousLiveOutput
        if (output != null && conn.isConnected) {
            clearRemoteAf()
            setProp(PtpProp.EVF_OUTPUT_DEVICE, output)
        }
        previousLiveOutput = null
        previewFallbacks.clear()
        if (restoreEvfMode && conn.isConnected && !isMovieMode) setProp(PtpProp.EVF_MODE, 0)
        restoreEvfMode = false
        AppLog.i(TAG, "实时取景已关闭")
    }

    /** 定期保活，避免每帧额外占用一次命令通道。 */
    fun keepPreviewSessionAlive() {
        val now = SystemClock.elapsedRealtime()
        if (lastLiveKeepAlive == 0L || now - lastLiveKeepAlive >= 10_000L) {
            keepAlive()
            lastLiveKeepAlive = now
        }
    }

    /** 保留机身的未就绪和 Busy 两种响应，让调用方分别调整取景节奏。 */
    internal fun grabFrame(timeoutMs: Int): PreviewPoll {
        if (!liveViewActive) return PreviewPoll.Miss.EMPTY
        keepPreviewSessionAlive()
        val r =
            conn.withCommandTimeout(timeoutMs) {
                conn.commandWithData(PtpOp.EOS_GET_VIEWFINDER_DATA, 0x00200000, 0, 0)
            }
        if (!r.ok) {
            if (r.code == PtpRc.EOS_OBJECT_NOT_READY) return PreviewPoll.Miss.NOT_READY
            if (r.code == PtpRc.DEVICE_BUSY) return PreviewPoll.Miss.BUSY
            throw PtpException("取景帧获取失败：${r.describe()}", r.code)
        }
        return parsePreviewJpeg(r.data)?.let { PreviewPoll.Frame(it) } ?: PreviewPoll.Miss.EMPTY
    }

    // --------------------------------------------------------------- 快门

    /**
     * 拍一张。
     *
     * 时序照 `camera_trigger_canon_eos_capture`： RemoteReleaseOn(1) → 等 FocusInfo（≤500ms）→
     * RemoteReleaseOn(2) → RemoteReleaseOff(2) → RemoteReleaseOff(1) 然后持续排空事件直到 `ObjectAddedEx`
     * 出现，或者超过本次曝光的等待时限。
     *
     * @param halfPressAf 自动对焦时是否先半按等合焦。关掉时走直按快门，不发自动对焦请求。
     * @return 新落盘的文件（RAW+JPG 时会拿到两条，但相机可能只先报第一条）
     */
    suspend fun capture(
        halfPressAf: Boolean = true,
        onAccepted: () -> Unit = {},
    ): List<CameraFile> {
        while (shutterSequenceActive && conn.isConnected) delay(20)
        check(!captureInProgress) { "已有拍摄任务" }
        captureInProgress = true
        shutterSequenceActive = true
        try {
            return captureStill(halfPressAf, onAccepted)
        } finally {
            captureFiles = null
            shutterSequenceActive = false
            captureInProgress = false
        }
    }

    private suspend fun captureStill(
        halfPressAf: Boolean,
        onAccepted: () -> Unit,
    ): List<CameraFile> {
        finishShutterRelease()
        drainEvents(maxBatches = 2)
        if (cameraStatus == STATUS_BUSY) {
            delay(100)
            drainEvents(maxBatches = 2)
        }
        val files = LinkedHashMap<Int, CameraFile>()
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_READY_TIMEOUT_MS
        var busyReplies = 0
        var lastBusy: PtpException? = null
        while (true) {
            if (lastBusy != null && SystemClock.elapsedRealtime() >= deadline) {
                throw PtpException("相机仍忙，拍摄未开始：${lastBusy.message}", PtpRc.DEVICE_BUSY, lastBusy)
            }
            if (isMovieMode) {
                throw PtpException("短片模式下不能拍照")
            }
            if (bulbOpen || usesBulb) throw PtpException("B 门模式请使用 B 门快门")
            var fullPressed = false
            val requestAf = halfPressAf && !isManualFocus
            try {
                clearRemoteAf()
                prepareCaptureDestination()
                if (requestAf) {
                    releaseOn(1)
                    waitFocus()
                    if (isMovieMode || usesBulb) throw PtpException("机身已切换拍摄模式，请重新按快门")
                }
                releaseOn(if (requestAf) 2 else 3, autofocus = requestAf)
                captureFiles = files
                fullPressed = true
            } catch (e: PtpException) {
                if (e.ptpCode != PtpRc.DEVICE_BUSY) throw e
                busyReplies++
                lastBusy = e
                AppLog.d(TAG, "等待机身就绪后重试快门：${e.message}")
            } finally {
                finishShutterRelease(fullPressed)
            }
            if (fullPressed) {
                shutterSequenceActive = false
                onAccepted()
                val timeout =
                    stillCaptureTimeoutMs(
                        value(PtpProp.SHUTTER_SPEED)?.toInt(),
                        value(PtpProp.DRIVE_MODE)?.toInt(),
                    )
                return waitForNewObjects(files, timeout)
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) continue
            delay(minOf(busyReplies * 60L, 240L, remaining))
            drainEvents(maxBatches = 2)
        }
    }

    private fun prepareCaptureDestination() {
        if (value(PtpProp.CAPTURE_DESTINATION) != CAPTURE_TO_HOST) return
        val destinations = captureDestinations
        val card =
            if (destinations.isNullOrEmpty()) CAPTURE_TO_SD
            else {
                destinations.firstOrNull { it != 0L && it != CAPTURE_TO_HOST }
                    ?: throw PtpException("相机未提供 SD 卡拍摄目标，请检查存储卡")
            }
        setProp(PtpProp.CAPTURE_DESTINATION, card)
        drainEvents(maxBatches = 2)
    }

    private fun finishShutterRelease(fullPressAccepted: Boolean = false) {
        var failure: PtpException? = null
        for (kind in pressedButtons.toList().asReversed()) {
            try {
                releaseOff(kind)
                pressedButtons.remove(kind)
            } catch (e: PtpException) {
                if (failure == null) failure = e else failure.addSuppressed(e)
                if (!conn.isConnected) break
            }
        }
        if (failure != null) {
            val status = if (fullPressAccepted) "机身已接受拍摄，快门释放未确认" else "未确认快门已释放，已暂停继续拍摄"
            throw PtpException("$status：${failure.message}", cause = failure)
        }
    }

    private fun releaseOn(kind: Int, autofocus: Boolean = true) {
        val r = conn.command(PtpOp.EOS_REMOTE_RELEASE_ON, kind, if (autofocus) 0 else 1)
        if (!r.ok) throw PtpException("快门按下(kind=$kind)失败：${r.describe()}", r.code)
        pressedButtons.add(kind)
    }

    private fun releaseOff(kind: Int) {
        repeat(3) { attempt ->
            val r = conn.command(PtpOp.EOS_REMOTE_RELEASE_OFF, kind)
            if (r.ok) return
            if (r.code != PtpRc.DEVICE_BUSY || attempt == 2) {
                throw PtpException("快门释放(kind=$kind)失败：${r.describe()}", r.code)
            }
            drainEvents(maxBatches = 1)
            Thread.sleep(40L * (attempt + 1))
        }
    }

    /**
     * 半按后等合焦。
     *
     * OLC 的对焦字节是 6 字节十六进制串：`000000000101` 表示合焦， `000000000200` / `000000000000` 表示 AF
     * 失败。手动对焦模式下没有这套逻辑，直接放行。
     */
    private suspend fun waitFocus(): Boolean {
        if (isManualFocus) return true
        val deadline = SystemClock.elapsedRealtime() + FOCUS_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val events = drainEvents(maxBatches = 2)
            if (events.any(::isFocusOk)) return true
            // Let the camera apply its own AF release-priority setting on full press.
            if (events.any { it is CameraEvent.AfResult && it.result != 0 }) return false
            delay(20)
        }
        AppLog.d(TAG, "机身未回报合焦结果")
        return false
    }

    private fun isFocusOk(e: CameraEvent): Boolean =
        when (e) {
            is CameraEvent.Olc -> e.focusInfo?.endsWith("0101") == true
            is CameraEvent.FocusPointEx -> e.selected > 0
            is CameraEvent.AfResult -> e.result == 0
            else -> false
        }

    private suspend fun waitForNewObjects(
        files: Map<Int, CameraFile>,
        timeoutMs: Long,
    ): List<CameraFile> {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (intervalRunning && intervalCancelled) throw IntervalCancelledException()
            if (!conn.isConnected) throw PtpException("机身已接受快门，连接断开前未确认照片落盘")
            drainEvents(maxBatches = 2)
            if (files.isNotEmpty()) {
                // 短暂收集同一张照片的 RAW 和 JPEG，同时让取景继续。
                val settle = SystemClock.elapsedRealtime() + 250
                while (SystemClock.elapsedRealtime() < settle) {
                    delay(40)
                    drainEvents(maxBatches = 1)
                }
                AppLog.i(TAG, "落盘：${files.values.joinToString { "${it.name} ${it.size}B" }}")
                return files.values.toList()
            }
            delay(80)
        }
        throw PtpException("机身已接受快门，${timeoutMs / 1000} 秒内未确认照片落盘，请查看相机存储卡")
    }

    // --------------------------------------------------------------- B 门

    fun bulbStart() {
        if (!usesBulb) throw PtpException("请先在 M 档将快门速度设为 Bulb")
        if (bulbOpen) return
        conn.command(PtpOp.EOS_BULB_START).requireOk()
        bulbOpen = true
        AppLog.i(TAG, "B 门开始")
    }

    fun bulbEnd() {
        if (!bulbOpen) return
        conn.command(PtpOp.EOS_BULB_END).requireOk()
        bulbOpen = false
        AppLog.i(TAG, "B 门结束")
    }

    // ---------------------------------------------------------------- 对焦

    /** 立刻自动对焦一次（`DoAf 0x9154`，无参）。 */
    suspend fun afDrive(): Boolean? {
        finishShutterRelease()
        if (liveViewActive) {
            val r = conn.command(PtpOp.EOS_DO_AF)
            if (r.ok) {
                nativeAfActive = true
                return null
            }
            if (r.code != PtpRc.OPERATION_NOT_SUPPORTED) {
                throw PtpException("自动对焦失败：${r.describe()}", r.code)
            }
        }
        shutterSequenceActive = true
        return try {
            drainEvents(maxBatches = 1)
            releaseOn(1)
            waitFocus().takeIf { it }
        } finally {
            try {
                finishShutterRelease()
            } finally {
                shutterSequenceActive = false
            }
        }
    }

    /** 点选对焦。[x] / [y] 是取景画面内的坐标，需要按相机的取景分辨率给。 `type` 传 1 表示立即执行 AF。 */
    fun touchAf(x: Int, y: Int) {
        finishShutterRelease()
        val r = conn.command(PtpOp.EOS_TOUCH_AF_POSITION, 1, x, y)
        if (!r.ok) throw PtpException("点选对焦失败：${r.describe()}", r.code)
        nativeAfActive = true
    }

    private fun clearRemoteAf() {
        if (!nativeAfActive) return
        val result = conn.command(PtpOp.EOS_AF_CANCEL)
        if (result.code == PtpRc.DEVICE_BUSY) {
            throw PtpException("机身正在结束自动对焦：${result.describe()}", result.code)
        }
        if (!result.ok) AppLog.d(TAG, "结束自动对焦：${result.describe()}")
        nativeAfActive = false
    }

    // ------------------------------------------------------------ 拍摄模式

    /**
     * 当前曝光模式（`0xD105 AutoExposureMode`）。
     *
     * 这个属性在 EOS 机身上是**只读**的：模式由机身旋钮决定，主机写它会被回 `0x2019 DeviceBusy`。所以界面只能镜像它，不能假装能拧旋钮。
     */
    val exposureMode: Int?
        get() {
            val effective = value(PtpProp.AUTO_EXPOSURE_MODE)?.toInt()
            val dial = value(PtpProp.AE_MODE_DIAL)?.toInt()
            val (preferred, alternate) =
                if (modeProperty == PtpProp.AE_MODE_DIAL) dial to effective else effective to dial
            return preferred.takeIf { ExposureMode.from(it) != ExposureMode.UNKNOWN }
                ?: alternate
                ?: preferred
        }

    private fun exposureModeIsBulb(): Boolean =
        value(PtpProp.AE_MODE_DIAL) == ExposureMode.M.code.toLong() &&
            value(PtpProp.AUTO_EXPOSURE_MODE) == ExposureMode.BULB.code.toLong()

    val usesBulb: Boolean
        get() = ExposureMode.from(exposureMode).usesBulb(value(PtpProp.SHUTTER_SPEED)?.toInt())

    val isMovieMode: Boolean
        get() =
            isRecording ||
                when (value(PtpProp.FIXED_MOVIE)) {
                    0L -> false
                    1L -> true
                    else -> ExposureMode.from(exposureMode) == ExposureMode.MOVIE
                }

    val shootingMode: ExposureMode
        get() {
            if (isMovieMode) return ExposureMode.MOVIE
            val mode = ExposureMode.from(exposureMode)
            return if (mode == ExposureMode.MOVIE)
                ExposureMode.from(value(PtpProp.AE_MODE_DIAL)?.toInt()).takeUnless {
                    it == ExposureMode.MOVIE
                } ?: ExposureMode.UNKNOWN
            else mode
        }

    /**
     * 对焦方式（`0xD108 FocusMode`，u32）：0 单次 / 1 伺服 / 2 智能 / 3 手动。
     *
     * 前三档是机身的 AF 操作模式，第 3 档由镜头上的 AF/MF 物理开关决定—— 开关拨到 MF 时机身就把这个值推成 3。软件写它属于改机身菜单，
     * 这里只照单回显（镜面镜像），不发任何写请求。
     */
    val focusMode: Int?
        get() = propValues[PtpProp.FOCUS_MODE]?.toInt()

    /** 手动对焦：AF 电机不工作，按键对焦不会有结果。 */
    val isManualFocus: Boolean
        get() = focusMode == 3

    val isAutoFocus: Boolean
        get() =
            when (focusMode) {
                0,
                1,
                2 -> true
                else -> false
            }

    fun setRecording(start: Boolean) {
        if (!isMovieMode) throw PtpException("请将机身电源开关拨至短片档")
        if (start == isRecording) return
        val target = if (start) 4L else 0L
        if (recordingTargets?.let { it.isNotEmpty() && target !in it } == true) {
            throw PtpException("机身当前没有提供此录像操作")
        }
        if (start) prepareCaptureDestination()
        // The UI changes only after a real C189 property notification.
        setProp(PtpProp.EVF_RECORD_STATUS, target, cache = false)
        drainEvents(maxBatches = 1)
    }

    val isRecording: Boolean
        get() = value(PtpProp.EVF_RECORD_STATUS) == 4L

    // ------------------------------------------------------- 间隔拍摄 / 延时

    /**
     * 间隔拍摄（延时摄影）参数。
     *
     * @param count 总张数，0 表示不限张数直到手动停止
     * @param intervalMs 两次拍摄之间的间隔，从“上一张落盘”开始计
     * @param startDelayMs 启动前等待，留给用户把手机放稳
     * @param halfPressAf 是否每次先半按对焦。固定机位延时建议关掉，避免每张都重拉风箱
     */
    data class IntervalPlan(
        val count: Int,
        val intervalMs: Long,
        val startDelayMs: Long = 0L,
        val halfPressAf: Boolean = true,
    )

    /** 间隔拍摄的进度回调参数。 */
    data class IntervalProgress(
        /** 已完成的张数。 */
        val done: Int,
        /** 计划总张数，0 = 不限。 */
        val total: Int,
        /** 距下一张还有多少毫秒，拍摄中为 0。 */
        val waitMs: Long,
        /** 正在曝光。 */
        val shooting: Boolean,
    )

    @Volatile private var intervalCancelled = false

    @Volatile
    var intervalRunning: Boolean = false
        private set

    /**
     * 停止间隔拍摄。
     *
     * 只是置标志位，循环会在下一次切片（≤100ms）退出；正在曝光的那一张不打断， 保证不会把相机的快门时序切一半。
     */
    fun cancelInterval() {
        intervalCancelled = true
    }

    /**
     * 跑一轮间隔拍摄。
     *
     * 这是阻塞调用，必须在 IO 线程上跑（ViewModel 用 withContext(Dispatchers.IO)）。 间隔期间持续
     * [keepAlive]，否则相机的自动关机计时会在最后一分钟掐断链路。
     *
     * @return 实际完成的张数
     */
    suspend fun runInterval(
        plan: IntervalPlan,
        onProgress: ((IntervalProgress) -> Unit)? = null,
    ): Int {
        if (intervalRunning) throw PtpException("已有间隔拍摄任务在跑")
        /* 短片模式下全按快门是开关录制，一张照片也不会落盘，开了只会一路空转 */
        if (isMovieMode) {
            throw PtpException("短片模式下不能间隔拍摄")
        }
        intervalCancelled = false
        intervalRunning = true
        var done = 0
        try {
            if (plan.startDelayMs > 0) {
                onProgress?.invoke(IntervalProgress(0, plan.count, plan.startDelayMs, false))
                if (!waitCancellable(plan.startDelayMs)) return 0
            }
            while (!intervalCancelled && (plan.count <= 0 || done < plan.count)) {
                onProgress?.invoke(IntervalProgress(done, plan.count, 0, true))
                if (intervalCancelled) break
                /* 停止时 capture 会从等待落盘里抛出来，这张不计入已完成 */
                try {
                    capture(plan.halfPressAf)
                } catch (e: IntervalCancelledException) {
                    break
                }
                done++

                if (intervalCancelled) break
                if (plan.count > 0 && done >= plan.count) break

                // 用“上一张落盘”当间隔起点：`waitForNewObjects` 返回时片子已经在卡上了，
                // 这样实际节奏更接近用户设的间隔，而不是间隔 + 曝光时间 + 传输时间。
                onProgress?.invoke(IntervalProgress(done, plan.count, plan.intervalMs, false))
                if (!waitCancellable(plan.intervalMs)) break
            }
        } finally {
            intervalRunning = false
        }
        if (intervalCancelled) {
            /* 用户按的停止：报「跑完了」会让人以为后面几张被吞了 */
            AppLog.i(TAG, "间隔拍摄已停止，完成 $done 张")
        } else {
            AppLog.i(TAG, "间隔拍摄结束，共 $done 张")
        }
        return done
    }

    /** 让取景和事件轮询在两次拍摄之间继续工作。 */
    private suspend fun waitCancellable(totalMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + totalMs
        var nextKeepAlive = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            if (intervalCancelled) return false
            if (!conn.isConnected) throw PtpException("间隔拍摄时连接已断开")
            val now = SystemClock.elapsedRealtime()
            if (now >= end) return true
            if (!liveViewActive && now >= nextKeepAlive) {
                keepAlive()
                nextKeepAlive = now + 10_000L
            }
            delay(minOf(100L, end - now))
        }
    }

    // ---------------------------------------------------------------- 图库

    fun reloadStorages() {
        val eos = conn.commandWithData(PtpOp.EOS_GET_STORAGE_IDS)
        val response =
            if (eos.code == PtpRc.OPERATION_NOT_SUPPORTED) {
                conn.commandWithData(PtpOp.GET_STORAGE_IDS)
            } else eos
        storageIds = readHandleList(response, "读取存储卡").filter { it != 0 && it != PtpHandle.SPECIAL }
        AppLog.i(TAG, "存储卡：${storageIds.joinToString { "0x%08X".format(it) }}")
    }

    /**
     * 读一张卡的标准 StorageInfo（0x1005）。
     *
     * 布局按 ISO 15740：`u16 类型 + u16 文件系统 + u16 访问能力 + u64 容量 + u64 剩余 + u32 剩余可拍 + 卡描述 + 卷标`。gphoto2
     * 在 EOS 上也走这条标准路， 所以真机与模拟器行为一致。读不到返回 null，界面据此显示占位。
     */
    fun storageInfo(storageId: Int? = null): StorageInfo? {
        if (storageIds.isEmpty()) reloadStorages()
        val id = storageId ?: storageIds.firstOrNull() ?: return null
        val r = conn.commandWithData(PtpOp.GET_STORAGE_INFO, id)
        if (!r.ok) {
            AppLog.w(TAG, "GetStorageInfo 失败：${r.describe()}")
            return null
        }
        val d = r.data ?: return null
        /* 26 字节定长头 + 至少两个「单字符数 0」的空字符串 */
        if (d.size < 26 + 2) {
            AppLog.w(TAG, "StorageInfo 数据过短：${d.size}B")
            return null
        }
        val storageType = le16(d, 0)
        val access = le16(d, 4)
        val maxCap = le64(d, 6)
        val freeBytes = le64(d, 14)
        val freeImages = le32(d, 22).toLong()
        var off = 26
        val desc = readPtpString(d, off)?.also { off += it.second }?.first ?: ""
        val label = readPtpString(d, off)?.first ?: ""
        return StorageInfo(
            storageId = id,
            storageType = storageType,
            accessCapability = access,
            maxCapacity = maxCap,
            freeSpaceInBytes = freeBytes,
            freeSpaceInImages = freeImages,
            description = desc,
            volumeLabel = label,
        )
    }

    /** Canon API 每次只列一个目录。递归进入 DCIM 等目录，图库只展示文件。 */
    suspend fun listFolder(beforeRead: suspend () -> Unit): List<CameraFile> {
        beforeRead()
        if (storageIds.isEmpty()) reloadStorages()
        val files = LinkedHashMap<Int, CameraFile>()
        for (storageId in storageIds) {
            val pending = ArrayDeque<Int>()
            val visited = mutableSetOf<Int>()
            pending.add(PtpHandle.SPECIAL)
            while (pending.isNotEmpty()) {
                val parent = pending.removeFirst()
                if (!visited.add(parent)) continue
                for (entry in readDirectory(storageId, parent, beforeRead)) {
                    if (entry.handle == 0 || entry.handle == PtpHandle.SPECIAL) continue
                    if (entry.isDirectory) pending.add(entry.handle)
                    else if (entry.name.isNotBlank()) files[entry.handle] = entry
                }
            }
        }
        return files.values.sortedByDescending { it.name }
    }

    private suspend fun readDirectory(
        storageId: Int,
        parent: Int,
        beforeRead: suspend () -> Unit,
    ): List<CameraFile> {
        yield()
        beforeRead()
        val r =
            conn.commandWithData(
                PtpOp.EOS_GET_OBJECT_INFO_EX,
                storageId,
                parent,
                0x100000,
            )
        if (r.code == PtpRc.OPERATION_NOT_SUPPORTED) {
            val handles = conn.commandWithData(PtpOp.GET_OBJECT_HANDLES, storageId, 0, parent)
            return readHandleList(handles, "读取目录句柄").map { handle ->
                yield()
                beforeRead()
                readStandardObjectInfo(handle)
            }
        }
        val data = r.requireOk().data ?: throw PtpException("相机没有返回目录数据")
        return parseDirectory(data, storageId)
    }

    private fun parseDirectory(data: ByteArray, storageId: Int): List<CameraFile> {
        if (data.size < 4) throw PtpException("相机目录过短")
        val count = le32(data, 0)
        if (count !in 0..((data.size - 4) / 56)) throw PtpException("相机目录条目数异常")
        var off = 4
        return List(count) {
            val entrySize = le32(data, off)
            if (entrySize !in 56..(data.size - off)) throw PtpException("相机目录条目不完整")
            val entry = unpackFolderEntry(data, off + 4, storageId)
            off += entrySize
            entry
        }
    }

    private fun unpackFolderEntry(b: ByteArray, base: Int, storageId: Int): CameraFile {
        val handle = le32(b, base)
        val ofc = le16(b, base + 8)
        val readOnly = (b[base + 16].toInt() and 0x01) != 0
        val size = le32(b, base + 20).toLong() and 0xFFFFFFFFL
        val name = decodeAscii(b, base + 32, 16)
        val time = le32(b, base + 48).toLong() and 0xFFFFFFFFL
        return CameraFile(handle, storageId, ofc, size, name, time, readOnly)
    }

    private fun readHandleList(response: PtpResult, operation: String): List<Int> {
        val data = response.requireOk().data ?: throw PtpException("$operation：相机没有返回数据")
        if (data.size < 4) throw PtpException("$operation：数据过短")
        val count = le32(data, 0)
        if (count !in 0..((data.size - 4) / 4)) throw PtpException("$operation：句柄列表不完整")
        return List(count) { le32(data, 4 + it * 4) }
    }

    private fun readStandardObjectInfo(handle: Int): CameraFile {
        val data =
            conn.commandWithData(PtpOp.GET_OBJECT_INFO, handle).requireOk().data
                ?: throw PtpException("相机没有返回文件信息")
        val name = readPtpString(data, 52)?.first ?: throw PtpException("相机文件信息不完整")
        return CameraFile(
            handle,
            le32(data, 0),
            le16(data, 4),
            le32(data, 8).toLong() and 0xFFFFFFFFL,
            name,
            readOnly = le16(data, 6) == 1,
        )
    }

    /** 缩略图。优先 `GetThumbEx 0x910A`，不行退回标准 `GetThumb 0x100A`。 */
    fun loadThumb(handle: Int): ByteArray? {
        val ex = runCatching { conn.commandWithData(PtpOp.EOS_GET_THUMB_EX, handle) }.getOrNull()
        if (ex != null && ex.ok && ex.data?.isNotEmpty() == true) return ex.data
        val std = runCatching { conn.commandWithData(PtpOp.GET_THUMB, handle) }.getOrNull()
        return if (std != null && std.ok) std.data else null
    }

    /**
     * 分块下载一个对象。
     *
     * 普通照片分块读取，只有机身主动请求传输的对象需要发 TransferComplete。
     */
    suspend fun downloadTo(
        file: CameraFile,
        target: File,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Long {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        FileOutputStream(target).use { fos ->
            return downloadTo(file, fos, onProgress = onProgress)
        }
    }

    /** 直接往输出流写。存到 MediaStore 时用这个，避免先落一份临时文件再拷贝。 输出流的打开/关闭由调用方负责。 */
    suspend fun downloadTo(
        file: CameraFile,
        out: java.io.OutputStream,
        beforeRead: suspend () -> Unit = {},
        completeTransfer: Boolean = true,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Long {
        var written = 0L
        // 32 位对象大小的全 1 是未知或超出范围的标记，走整对象流式读取。
        val total = file.size.takeIf { it in 1L until 0xFFFFFFFFL }
        val sink = DataSink { buf, off, n ->
            out.write(buf, off, n)
            written += n
        }
        fun read(op: Int, vararg params: Int): PtpResult =
            conn.execute(
                op,
                params,
                receiveData = true,
                sink = sink,
                onProgress = { _, declared ->
                    onProgress?.invoke(written, total ?: declared)
                },
            ) {
                it
            }

        val requested = file.handle in transferRequests
        var partialOp = if (requested) PtpOp.EOS_GET_PARTIAL_OBJECT else PtpOp.GET_PARTIAL_OBJECT
        if (total != null) {
            while (written < total) {
                beforeRead()
                val offset = written
                val blockSize = if (liveViewActive) BLOB_SIZE / 4 else BLOB_SIZE
                val size = minOf(total - offset, blockSize.toLong()).toInt()
                var result = read(partialOp, file.handle, offset.toInt(), size)
                if (
                    written == 0L &&
                        result.code == PtpRc.OPERATION_NOT_SUPPORTED &&
                        partialOp == PtpOp.GET_PARTIAL_OBJECT
                ) {
                    partialOp = PtpOp.EOS_GET_PARTIAL_OBJECT
                    result = read(partialOp, file.handle, 0, size)
                }
                if (written == 0L && result.code == PtpRc.OPERATION_NOT_SUPPORTED) break
                result.requireOk()
                if (written - offset !in 1L..size.toLong()) throw PtpException("相机返回的下载分块长度异常")
                if (written < total) yield()
            }
        }
        if (written == 0L) {
            beforeRead()
            val op = if (requested) PtpOp.EOS_GET_OBJECT else PtpOp.GET_OBJECT
            val result = read(op, file.handle)
            if (
                op == PtpOp.GET_OBJECT &&
                    written == 0L &&
                    result.code == PtpRc.OPERATION_NOT_SUPPORTED
            ) {
                read(PtpOp.EOS_GET_OBJECT, file.handle).requireOk()
            } else result.requireOk()
        }
        if (written <= 0 || (total != null && written != total)) {
            throw PtpException("相机文件传输不完整（已收 $written 字节）")
        }
        out.flush()
        if (requested && completeTransfer) {
            runCatching { conn.command(PtpOp.EOS_TRANSFER_COMPLETE, file.handle) }
                .onSuccess { result ->
                    if (result.ok) transferRequests.remove(file.handle)
                    else AppLog.w(TAG, "相机未确认传输已结束：${result.describe()}")
                }
                .onFailure { AppLog.w(TAG, "文件已收齐，发送传输确认失败：${it.message}") }
        }
        onProgress?.invoke(written, total ?: written)
        return written
    }

    fun isHostTransfer(handle: Int): Boolean = handle in transferRequests
}

// ------------------------------------------------------------------ 小工具

internal fun stillCaptureTimeoutMs(shutterCode: Int?, driveMode: Int?): Long {
    val exposureSeconds =
        EosValues.SHUTTER.firstOrNull { it.code == shutterCode }
            ?.label
            ?.removeSuffix("\"")
            ?.toDoubleOrNull() ?: 0.0
    val timerMs =
        when (driveMode) {
            0x10 -> 10_000L
            0x11 -> 2_000L
            else -> 0L
        }
    // 长时间曝光降噪可能在正式曝光后继续占用一次曝光时间。
    val exposureAndProcessingMs = (exposureSeconds * 2_000).toLong()
    return maxOf(25_000L, exposureAndProcessingMs + timerMs + 10_000L)
}

private fun le16(b: ByteArray, o: Int): Int =
    if (o + 2 <= b.size) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else 0

internal fun le32(b: ByteArray, o: Int): Int =
    if (o + 4 <= b.size) {
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
    } else 0

private fun le64(b: ByteArray, o: Int): Long {
    if (o + 8 > b.size) return 0L
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
    return v
}

/** PTP 字符串：`u8 字符数（含结尾空字符）` + UTF-16LE + 0x0000。 返回「内容 → 消费的字节数」，越界返回 null。 */
private fun readPtpString(b: ByteArray, off: Int): Pair<String, Int>? {
    if (off >= b.size) return null
    val n = b[off].toInt() and 0xFF
    if (n == 0) return "" to 1
    if (off + 1 + n * 2 > b.size) return null
    val sb = StringBuilder(n)
    for (i in 0 until n - 1) {
        val c =
            ((b[off + 1 + i * 2].toInt() and 0xFF) or ((b[off + 2 + i * 2].toInt() and 0xFF) shl 8))
        sb.append(c.toChar())
    }
    return sb.toString() to (1 + n * 2)
}

private fun putLe16(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v shr 8) and 0xFF).toByte()
}

private fun putLe32(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
    b[o + 1] = ((v shr 8) and 0xFF).toByte()
    b[o + 2] = ((v shr 16) and 0xFF).toByte()
    b[o + 3] = ((v shr 24) and 0xFF).toByte()
}

/** 目录条目里的文件名是定长 ASCII 字段，不是 PTP 字符串（没有长度前缀）。 */
private fun decodeAscii(b: ByteArray, offset: Int, maxLen: Int): String {
    var end = offset
    val limit = minOf(b.size, offset + maxLen)
    while (end < limit && b[end].toInt() != 0) end++
    return String(b, offset, end - offset, Charsets.US_ASCII)
}
