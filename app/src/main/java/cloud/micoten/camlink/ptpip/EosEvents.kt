package cloud.micoten.camlink.ptpip

/**
 * 相机侧主动上报的事件（已解包）。
 *
 * 佳能的 `GetEvent 0x9116` 返回的是一坨紧凑的 TLV 数组，每一项是 `u32 size` + `u32 eventCode` + 事件体。这里把它拆成 Kotlin 对象，
 * 上层（EosCamera / ViewModel）只关心语义。
 */
sealed class CameraEvent {

    /** 某个设备属性变了。数值属性看 [value]，字符串属性看 [text]。 */
    data class PropChanged(
        val propCode: Int,
        val value: Long,
        val text: String? = null,
    ) : CameraEvent()

    /**
     * OLC（不知道全称，佳能内部的“操作层状态”广播）里打包上报的状态。
     *
     * 这一条事件同时携带快门 / 光圈 / ISO / 测光指示 / 对焦状态， 是遥控界面上参数显示最实时、最省事的数据来源 —— 比逐个属性
     * `RequestDevicePropValue` 再等事件要快得多。
     */
    data class Olc(
        val shutterSpeed: Int? = null,
        val aperture: Int? = null,
        val iso: Int? = null,
        /** 测光指示，单位 0.1 EV，带符号。 */
        val exposureIndicator: Int? = null,
        /** 对焦检测字节的十六进制串，例如 "000000000101"。 */
        val focusInfo: String? = null,
        val focusMask: String? = null,
        val button: Int? = null,
    ) : CameraEvent()

    /** 自动对焦结果。 */
    data class AfResult(val result: Int) : CameraEvent()

    /**
     * 对焦点信息（属性 0xD1D3 的二进制结构）。
     *
     * libgphoto2 把这块数据渲染成 `{x,y,w,h},...` 文本，然后判断是否为 `"{}"` 来确认是否合焦。这里不需要坐标，只要 [selected] > 0
     * 就算相机给出了对焦点。
     */
    data class FocusPointEx(val selected: Int, val total: Int) : CameraEvent()

    /** 新对象（照片）落盘。 */
    data class ObjectAdded(
        val handle: Int,
        val storageId: Int,
        val format: Int,
        val size: Long,
        val name: String,
    ) : CameraEvent()

    /** 相机要求主机传输某个对象（通常发生在“拍摄后自动传图”场景）。 */
    data class TransferRequested(val handle: Int) : CameraEvent()

    /** 相机状态变化。EOS 用 1 表示忙。 */
    data class CameraStatus(val status: Int) : CameraEvent()

    /** 属性可用值列表变化（切到 M 档时光圈/快门可选值会变）。 */
    data class AvailListChanged(val propCode: Int, val values: List<Long>? = null) : CameraEvent()

    /** 相机即将休眠，剩余秒数。 */
    data class ShutdownTimer(val seconds: Int) : CameraEvent()

    data object WillSoonShutdown : CameraEvent()

    /** B 门已曝光时长（毫秒）。 */
    data class BulbExposureTime(val ms: Int) : CameraEvent()

    /**
     * 短片已录制时长（秒），事件 `RecordingTime 0xC195`。
     *
     * 相机在录制期间大约每秒推一次，界面拿它显示计时器， 比自己在 UI 上跑一个秒表准（暂停/续录会跳变）。
     */
    data class RecordingTime(val seconds: Int) : CameraEvent()

    /** 没解析出具体含义的事件，仅保留码值用于日志。 */
    data class Raw(val eventCode: Int, val size: Int) : CameraEvent()
}

/**
 * `GetEvent` 数据块解包器。
 *
 * 逐行对照 libgphoto2 的 `ptp_unpack_EOS_events`（ptp-pack.c:1907）实现， 包括它那几个看起来古怪的边界判断。唯一一处有意放宽：原始实现用
 * `(curdata - data) + size >= datasize` 就 break，会丢掉“恰好顶到数据末尾” 的那一条事件；实际数据块末尾总有 8 字节的 `size=8,
 * code=0` 终止项， 所以两种写法在真机上等价，这里用 `>` 以免万一丢事件。
 */
object EosEventParser {

    private const val TAG = "EosEvent"

    /** OLC 各字段的字节长度表，行 = OLC 版本，列 = mask 位 1..0x1000。 */
    private val OLC_SIZES: Array<IntArray> =
        arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x1
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x2
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x3
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x4
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x5
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x6
            intArrayOf(2, 6, 5, 4, 4, 6, 7, 4, 6, 5, 5, 8, 1), // 0x7  100D / 5D3 / 650D / 6D
            intArrayOf(2, 6, 5, 4, 4, 6, 7, 4, 6, 7, 7, 8, 1), // 0x8  70D / M10 / SX720HS
            intArrayOf(2, 6, 5, 4, 4, 6, 7, 4, 6, 7, 7, 8, 1), // 0x9
            intArrayOf(2, 6, 5, 4, 4, 6, 7, 4, 6, 7, 7, 8, 1), // 0xa
            intArrayOf(2, 6, 5, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0xb  750D / 5Ds
            intArrayOf(2, 6, 5, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0xc
            intArrayOf(2, 6, 5, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0xd
            intArrayOf(2, 6, 5, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0xe
            intArrayOf(2, 7, 6, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0xf  200D（本机）
            intArrayOf(2, 7, 6, 4, 4, 6, 8, 4, 6, 5, 5, 9, 1), // 0x10
            intArrayOf(2, 7, 6, 6, 4, 6, 8, 4, 6, 5, 5, 9, 8), // 0x11 EOS R
            intArrayOf(2, 7, 9, 6, 4, 6, 8, 5, 7, 5, 5, 9, 8), // 0x12 M6 Mark II
            intArrayOf(2, 7, 9, 7, 4, 6, 8, 5, 7, 5, 5, 9, 8), // 0x13 R5 / R5 C / M50 II
            intArrayOf(2, 9, 9, 7, 4, 6, 8, 5, 7, 5, 5, 9, 8), // 0x14 R8 / R10 / R5 II
        )

    /**
     * @param data `GetEvent` 收到的原始数据
     * @param olcVersion 相机的 `OLCInfoVersion`（属性 0xD199），未知传 0
     */
    fun parse(data: ByteArray?, olcVersion: Int = 0): List<CameraEvent> {
        if (data == null || data.size < 8) return emptyList()

        val out = ArrayList<CameraEvent>(8)
        var offset = 0
        var version = olcVersion
        val datasize = data.size

        while (offset <= datasize - 8) {
            val size = le32(data, offset).toLong() and 0xFFFFFFFFL
            val code = le32(data, offset + 4)

            if (size > datasize) {
                AppLogHolder.w("size=$size 超过数据总长 $datasize，截断")
                break
            }
            if (size < 8) {
                AppLogHolder.w("size=$size 小于最小包头 8，截断")
                break
            }
            if (size == 8L && code == 0) break // 正常终止项
            if (offset + size > datasize) {
                AppLogHolder.w("事件越界（offset=$offset size=$size total=$datasize），截断")
                break
            }

            val intSize = size.toInt()
            when (code) {
                PtpEvent.PROP_VALUE_CHANGED ->
                    parsePropValueChanged(data, offset, intSize)?.let { event ->
                        out.add(event)
                        if (
                            event is CameraEvent.PropChanged &&
                                event.propCode == PtpProp.OLC_INFO_VERSION
                        ) {
                            version = event.value.toInt()
                        }
                    }
                PtpEvent.OLC_INFO_CHANGED -> parseOlc(data, offset, intSize, version)?.let(out::add)
                PtpEvent.AF_RESULT ->
                    if (intSize >= 12) out += CameraEvent.AfResult(le16(data, offset + 8))
                PtpEvent.OBJECT_ADDED_EX -> parseObjectAdded(data, offset, intSize)?.let(out::add)
                PtpEvent.OBJECT_ADDED_EX64 ->
                    parseObjectAdded64(data, offset, intSize)?.let(out::add)
                PtpEvent.CAMERA_STATUS_CHANGED ->
                    if (intSize >= 12) out += CameraEvent.CameraStatus(le32(data, offset + 8))
                PtpEvent.REQUEST_OBJECT_TRANSFER,
                PtpEvent.REQUEST_OBJECT_TRANSFER64 ->
                    if (intSize >= 12) out += CameraEvent.TransferRequested(le32(data, offset + 8))
                PtpEvent.AVAIL_LIST_CHANGED ->
                    parseAvailListChanged(data, offset, intSize)?.let(out::add)
                PtpEvent.SHUTDOWN_TIMER_UPDATED ->
                    if (intSize >= 12) out += CameraEvent.ShutdownTimer(le32(data, offset + 8))
                PtpEvent.WILL_SOON_SHUTDOWN -> out += CameraEvent.WillSoonShutdown
                PtpEvent.BULB_EXPOSURE_TIME ->
                    if (intSize >= 12) out += CameraEvent.BulbExposureTime(le32(data, offset + 8))
                PtpEvent.RECORDING_TIME ->
                    if (intSize >= 12) out += CameraEvent.RecordingTime(le32(data, offset + 8))
                else -> out += CameraEvent.Raw(code, intSize)
            }
            offset += intSize
        }
        return out
    }

    // ------------------------------------------------------------ PropValueChanged

    private fun parsePropValueChanged(data: ByteArray, base: Int, size: Int): CameraEvent? {
        if (size < 0x0C) return null
        val propCode = le32(data, base + 0x08)
        val bodyLen = size - 0x0C
        val body = base + 0x0C

        if (propCode == PtpProp.FOCUS_INFO_EX) return parseFocusInfoEx(data, body, bodyLen)

        if (propCode in PtpProp.STRING_PROPS) {
            val text = if (bodyLen <= 0) "" else decodeCString(data, body, bodyLen)
            return CameraEvent.PropChanged(propCode, 0, text)
        }

        val width = PtpProp.valueWidth(propCode)
        if (bodyLen < width) return CameraEvent.Raw(PtpEvent.PROP_VALUE_CHANGED, size)
        val value =
            when (width) {
                1 -> (data[body].toInt() and 0xFF).toLong()
                2 -> (le16(data, body).toLong() and 0xFFFFL)
                else -> le32(data, body).toLong() and 0xFFFFFFFFL
            }
        return CameraEvent.PropChanged(propCode, value)
    }

    private fun parseAvailListChanged(data: ByteArray, base: Int, size: Int): CameraEvent? {
        if (size < 12) return null
        val propCode = le32(data, base + 8)
        if (
            (propCode != PtpProp.CAPTURE_DESTINATION &&
                propCode != PtpProp.EVF_OUTPUT_DEVICE &&
                propCode != PtpProp.EVF_RECORD_STATUS &&
                propCode !in PtpProp.exposureControls) || size < 20 || le32(data, base + 12) != 3
        ) {
            return CameraEvent.AvailListChanged(propCode)
        }
        val count = le32(data, base + 16)
        if (count !in 0..((size - 20) / 4)) return CameraEvent.AvailListChanged(propCode)
        val values = List(count) { le32(data, base + 20 + it * 4).toLong() and 0xFFFFFFFFL }
        return CameraEvent.AvailListChanged(propCode, values)
    }

    /**
     * 对焦点结构（属性 0xD1D3）： +0x00 总长 (u32) +0x04 半长 (u16) +0x06 版本 (u16) +0x08 结构内对焦点数 (u16) +0x0A
     * 实际使用对焦点数 (u16) +0x0C..0x13 四个尺寸 (u16 ×4) +0x14 起 每个点 8 字节（高/宽/偏移高/偏移宽，各 u16） 之后 位图，标记哪些点被选中
     */
    private fun parseFocusInfoEx(data: ByteArray, base: Int, len: Int): CameraEvent? {
        if (len < 20) return null
        val total = le16(data, base + 8)
        val used = le16(data, base + 10)
        if (total == 0 || used == 0) return CameraEvent.FocusPointEx(0, 0)

        val bitfieldStart = base + 20 + total * 8
        var selected = 0
        for (i in 0 until used) {
            val idx = bitfieldStart + i / 8
            if (idx >= base + len) break
            if ((data[idx].toInt() shr (i % 8)) and 1 != 0) selected++
        }
        return CameraEvent.FocusPointEx(selected, used)
    }

    // ------------------------------------------------------------------- OLC

    /**
     * OLC 事件：一个 mask + 一串定长记录，每条记录的偏移由 [OLC_SIZES] 累加得出。
     *
     * 布局（相对事件起始）： +0x00 size (u32) +0x04 code (u32) +0x08 blobLen (u32) +0x0C mask (u16) +0x10
     * 各字段记录
     */
    private fun parseOlc(data: ByteArray, base: Int, size: Int, olcVersion: Int): CameraEvent? {
        if (olcVersion !in 7 until OLC_SIZES.size || size < 16) {
            return CameraEvent.Raw(PtpEvent.OLC_INFO_CHANGED, size)
        }

        val blobLen = le32(data, base + 8)
        if (blobLen != size - 8 && blobLen != size - 4) {
            AppLogHolder.w("OLC blobLen=$blobLen 与 size=$size 不匹配，跳过")
            return CameraEvent.Raw(PtpEvent.OLC_INFO_CHANGED, size)
        }

        val ver = olcVersion
        val sizes = OLC_SIZES[ver]
        val mask = le16(data, base + 12)

        var shutter: Int? = null
        var aperture: Int? = null
        var iso: Int? = null
        var indicator: Int? = null
        var focusInfo: String? = null
        var focusMask: String? = null
        var button: Int? = null

        var cur = base + 16
        for (j in 0..12) {
            if (cur > base + size) break
            val curmask = 1 shl j
            if (mask and curmask == 0) continue
            val declared = sizes[j]
            val available = (base + size) - cur
            if (declared <= 0 || declared > available) break

            when (curmask) {
                0x0001 -> button = le16(data, cur)
                // 200D（olcver 0xf）实测快门记录 7 字节：01 03 98 10 00 70 00，
                // 第 6 个字节 0x70 就是快门码（1/125）。不同机型偏移不同，见上表。
                0x0002 -> shutter = data[cur + if (ver >= 0x14) 7 else 5].toInt() and 0xFF
                0x0004 -> aperture = data[cur + if (ver >= 0x12) 7 else 4].toInt() and 0xFF
                0x0008 -> iso = data[cur + 3].toInt() and 0xFF
                0x0040 -> indicator = data[cur + 2].toInt() // 有符号字节，单位 0.1EV
                0x0100 -> focusInfo = hex(data, cur, declared)
                0x0200 -> focusMask = hex(data, cur, declared)
                else -> Unit // 0x10 状态字 / 0x20 自拍倒计时 / 0x80 / 0x400 / 0x800 / 0x1000
            }
            cur += declared
        }

        return CameraEvent.Olc(
            shutterSpeed = shutter,
            aperture = aperture,
            iso = iso,
            exposureIndicator = indicator,
            focusInfo = focusInfo,
            focusMask = focusMask,
            button = button,
        )
    }

    // ------------------------------------------------------------- 新对象落盘

    /**
     * ObjectAddedEx (0xC181)：`+0x08 handle`、`+0x0C storageId`、`+0x10 ofc(u32)`、`+0x1C
     * size(u32)`、`+0x28 文件名`。
     */
    private fun parseObjectAdded(data: ByteArray, base: Int, size: Int): CameraEvent? {
        if (size < 0x28) return null
        val handle = le32(data, base + 0x08)
        val storage = le32(data, base + 0x0C)
        val ofc = le32(data, base + 0x10) and 0xFFFF
        val objSize = if (size >= 0x20) le32(data, base + 0x1C).toLong() and 0xFFFFFFFFL else 0L
        val name = if (size > 0x28) decodeCString(data, base + 0x28, size - 0x28) else ""
        return CameraEvent.ObjectAdded(handle, storage, ofc, objSize, name)
    }

    /** ObjectAddedEx64 (0xC1A7)：文件名在 `+0x2C`，句柄是 64 位但实际用低 32 位。 */
    private fun parseObjectAdded64(data: ByteArray, base: Int, size: Int): CameraEvent? {
        if (size < 0x2C) return null
        val handle = le32(data, base + 0x08)
        val storage = le32(data, base + 0x0C)
        val ofc = le32(data, base + 0x10) and 0xFFFF
        val objSize = if (size >= 0x20) le32(data, base + 0x1C).toLong() and 0xFFFFFFFFL else 0L
        val name = if (size > 0x2C) decodeCString(data, base + 0x2C, size - 0x2C) else ""
        return CameraEvent.ObjectAdded(handle, storage, ofc, objSize, name)
    }

    // ---------------------------------------------------------------- 小工具

    private fun le16(b: ByteArray, i: Int): Int =
        if (i + 2 <= b.size) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else 0

    private fun le32(b: ByteArray, i: Int): Int =
        if (i + 4 <= b.size) {
            (b[i].toInt() and 0xFF) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 3].toInt() and 0xFF) shl 24)
        } else 0

    private fun hex(b: ByteArray, offset: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            if (offset + i >= b.size) break
            sb.append("%02x".format(b[offset + i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** PTP 字符串是 UTF-8 且以 NUL 结尾，但长度字段常常把 NUL 也算进去。 */
    private fun decodeCString(b: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = minOf(b.size, offset + maxLen)
        while (end < limit && b[end].toInt() != 0) end++
        return String(b, offset, end - offset, Charsets.UTF_8).trim()
    }

    /** 避免在这个纯解析文件里直接依赖 AppLog，用一层薄包装保持可单测。 */
    private object AppLogHolder {
        fun w(msg: String) = cloud.micoten.camlink.util.AppLog.w(TAG_C, msg)

        private const val TAG_C = "EosEvent"
    }
}
