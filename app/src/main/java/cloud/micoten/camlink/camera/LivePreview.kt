package cloud.micoten.camlink.camera

internal const val PREVIEW_OUTPUT_MASK = 0xEL
internal const val PREVIEW_PC_OUTPUT = 2L

internal data class FocusGeometry(val width: Int, val height: Int) {
    fun pointAt(x: Float, y: Float): FocusPoint =
        FocusPoint(
            (x * width).toInt().coerceIn(0, width - 1),
            (y * height).toInt().coerceIn(0, height - 1),
            this,
        )
}

internal data class FocusPoint(val x: Int, val y: Int, val geometry: FocusGeometry)

internal data class PreviewFocusArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal enum class TouchFocusStatus(val code: Int) {
    FACE_TRACKING(0x20),
    TRACKING(0x30),
    FOCUSED(0x31),
    FAILED(0x32);

    companion object {
        fun from(code: Int): TouchFocusStatus? = entries.firstOrNull { it.code == code }
    }
}

internal class PreviewImage(
    val bytes: ByteArray,
    val offset: Int,
    val length: Int,
) {
    fun matches(other: PreviewImage): Boolean {
        if (this === other) return true
        if (length != other.length) return false
        for (i in 0 until length) {
            if (bytes[offset + i] != other.bytes[other.offset + i]) return false
        }
        return true
    }
}

internal data class PreviewJpeg(
    val image: PreviewImage,
    val focusGeometry: FocusGeometry? = null,
    val focusArea: PreviewFocusArea? = null,
    val focusStatus: TouchFocusStatus? = null,
) {
    fun hasSameFocus(other: PreviewJpeg): Boolean =
        focusGeometry == other.focusGeometry &&
            focusArea == other.focusArea &&
            focusStatus == other.focusStatus
}

/** 旧取景包可能继续带着上一次对焦的结果，直到机身开始当前请求。 */
internal class TouchFocusFeedback {
    private var lastStatus: TouchFocusStatus? = null
    private var lastArea: PreviewFocusArea? = null

    fun clear() {
        lastStatus = null
        lastArea = null
    }

    fun observe(frame: PreviewJpeg): TouchFocusStatus? {
        val status = frame.focusStatus ?: return null
        val area = frame.focusArea
        val changed = status != lastStatus || (area != null && lastArea != null && area != lastArea)
        lastStatus = status
        if (area != null) lastArea = area
        return status.takeIf {
            changed && (it == TouchFocusStatus.FOCUSED || it == TouchFocusStatus.FAILED)
        }
    }
}

internal sealed interface PreviewPoll {
    class Frame(val jpeg: PreviewJpeg) : PreviewPoll

    enum class Miss : PreviewPoll {
        NOT_READY,
        BUSY,
        EMPTY,
    }
}

/** 调整的是轮询间隔，不假定机身的采集帧率。 */
internal class PreviewCadence {
    private var intervalMs = 25L
    private var readyRuns = 0
    private var repeatRuns = 0
    private var misses = 0

    fun afterFrame(commandMs: Long, adaptive: Boolean, fresh: Boolean): Long {
        misses = 0
        if (adaptive) {
            if (fresh) {
                repeatRuns = 0
                if (++readyRuns == 8) {
                    intervalMs = (intervalMs - 1).coerceAtLeast(22L)
                    readyRuns = 0
                }
            } else {
                readyRuns = 0
                if (++repeatRuns == 3) {
                    intervalMs = (intervalMs + 2).coerceAtMost(42L)
                    repeatRuns = 0
                }
            }
        }
        return ((if (adaptive) intervalMs else 33L) - commandMs).coerceAtLeast(1L)
    }

    fun afterMiss(miss: PreviewPoll.Miss, adaptive: Boolean): Long {
        readyRuns = 0
        repeatRuns = 0
        misses = (misses + 1).coerceAtMost(12)
        if (!adaptive) return misses * 5L
        return when (miss) {
            PreviewPoll.Miss.NOT_READY -> {
                intervalMs = (intervalMs + 2).coerceAtMost(42L)
                (misses * 4L).coerceAtMost(18L)
            }
            PreviewPoll.Miss.BUSY -> (misses * 12L).coerceAtMost(72L)
            PreviewPoll.Miss.EMPTY -> (misses * 6L).coerceAtMost(24L)
        }
    }
}

/** 每个日志窗口统计帧间隔，保留跨窗口的相邻两帧。 */
internal class PreviewIntervals {
    private val samples = LongArray(512)
    private var count = 0
    private var lastAtNanos = 0L
    private var longestMs = 0L
    private var stalls = 0

    fun record(atNanos: Long) {
        if (lastAtNanos != 0L) {
            val gapMs = (atNanos - lastAtNanos) / 1_000_000L
            if (count < samples.size) samples[count++] = gapMs
            longestMs = maxOf(longestMs, gapMs)
            if (gapMs >= 100L) stalls++
        }
        lastAtNanos = atNanos
    }

    fun report(): String {
        if (count == 0) return "帧间隔样本不足"
        samples.sort(0, count)
        val p95Index = (count * 95 + 99) / 100 - 1
        val result = "帧间隔 P95 ${samples[p95Index]}ms，最长 ${longestMs}ms，≥100ms ${stalls} 次"
        clearWindow()
        return result
    }

    fun clear() {
        lastAtNanos = 0L
        clearWindow()
    }

    private fun clearWindow() {
        count = 0
        longestMs = 0
        stalls = 0
    }
}

internal fun previewOutputCandidates(
    bodyOutput: Long,
    advertised: List<Long>?,
    preferSmall: Boolean,
): List<Long> {
    val display = bodyOutput and PREVIEW_OUTPUT_MASK.inv()
    val candidates = ArrayList<Long>(4)
    if (preferSmall && advertised != null) {
        for (size in listOf(8L, 4L)) {
            val withDisplay = display or size
            val withoutLcd = withDisplay and 1L.inv()
            if (withDisplay in advertised) candidates += withDisplay
            if (withoutLcd in advertised) candidates += withoutLcd
        }
    }
    candidates += display or PREVIEW_PC_OUTPUT
    candidates += PREVIEW_PC_OUTPUT
    return candidates.distinct()
}

internal fun parsePreviewJpeg(
    data: ByteArray?,
    previousGeometry: FocusGeometry? = null,
): PreviewJpeg? {
    if (data == null) return null
    fun isJpeg(start: Int, end: Int): Boolean =
        end - start >= 3 &&
            data[start] == 0xFF.toByte() &&
            data[start + 1] == 0xD8.toByte() &&
            data[start + 2] == 0xFF.toByte()
    if (isJpeg(0, data.size)) {
        return PreviewJpeg(PreviewImage(data, 0, data.size), previousGeometry)
    }
    var offset = 0
    var jpegOffset = 0
    var jpegLength = 0
    var focusGeometry = previousGeometry
    var focusArea: PreviewFocusArea? = null
    var focusStatus: TouchFocusStatus? = null
    while (data.size - offset >= 8) {
        val size = le32(data, offset)
        if (size !in 8..(data.size - offset)) break
        val type = le32(data, offset + 4)
        if (
            jpegLength == 0 &&
                (type == 1 || type == 9 || type == 11) &&
                isJpeg(offset + 8, offset + size)
        ) {
            jpegOffset = offset + 8
            jpegLength = size - 8
        } else if (type == 0x0E && size >= 16) {
            val width = le32(data, offset + 8)
            val height = le32(data, offset + 12)
            if (width in 1..32768 && height in 1..32768) {
                val current = focusGeometry
                if (current == null || current.width != width || current.height != height) {
                    focusGeometry = FocusGeometry(width, height)
                }
            }
        } else if (type == 0x08 && size >= 16) {
            focusStatus = TouchFocusStatus.from(le32(data, offset + 12))
            focusArea =
                if (focusStatus != null && size >= 36) {
                    PreviewFocusArea(
                        le32(data, offset + 20),
                        le32(data, offset + 24),
                        le32(data, offset + 28),
                        le32(data, offset + 32),
                    )
                } else null
        }
        offset += size
    }
    if (jpegLength == 0) return null
    val geometry = focusGeometry
    val validFocusArea =
        if (geometry == null) null
        else
            focusArea?.takeIf {
                it.x in 0 until geometry.width &&
                    it.y in 0 until geometry.height &&
                    it.width in 1..geometry.width &&
                    it.height in 1..geometry.height
            }
    return PreviewJpeg(
        PreviewImage(data, jpegOffset, jpegLength),
        geometry,
        validFocusArea,
        focusStatus,
    )
}
