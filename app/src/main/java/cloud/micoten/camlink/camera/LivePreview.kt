package cloud.micoten.camlink.camera

internal const val PREVIEW_OUTPUT_MASK = 0xEL
internal const val PREVIEW_PC_OUTPUT = 2L

internal data class PreviewFocusArea(
    val status: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal class PreviewJpeg(
    val bytes: ByteArray,
    val offset: Int,
    val length: Int,
    val focusWidth: Int = 0,
    val focusHeight: Int = 0,
    val focusArea: PreviewFocusArea? = null,
) {
    fun matches(other: PreviewJpeg): Boolean {
        if (
            length != other.length ||
                focusWidth != other.focusWidth ||
                focusHeight != other.focusHeight ||
                focusArea != other.focusArea
        )
            return false
        for (i in 0 until length) {
            if (bytes[offset + i] != other.bytes[other.offset + i]) return false
        }
        return true
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
    var intervalMs = 25L
        private set

    private var readyRuns = 0
    private var misses = 0

    fun afterFrame(commandMs: Long, adaptive: Boolean): Long {
        misses = 0
        if (adaptive && ++readyRuns >= 8) {
            intervalMs = (intervalMs - 1).coerceAtLeast(22L)
            readyRuns = 0
        }
        return ((if (adaptive) intervalMs else 33L) - commandMs).coerceAtLeast(1L)
    }

    fun afterMiss(miss: PreviewPoll.Miss, adaptive: Boolean): Long {
        readyRuns = 0
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

internal fun parsePreviewJpeg(data: ByteArray?): PreviewJpeg? {
    if (data == null) return null
    fun isJpeg(start: Int, end: Int): Boolean =
        end - start >= 3 &&
            data[start] == 0xFF.toByte() &&
            data[start + 1] == 0xD8.toByte() &&
            data[start + 2] == 0xFF.toByte()
    if (isJpeg(0, data.size)) return PreviewJpeg(data, 0, data.size)
    var offset = 0
    var jpegOffset = 0
    var jpegLength = 0
    var focusWidth = 0
    var focusHeight = 0
    var focusArea: PreviewFocusArea? = null
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
                focusWidth = width
                focusHeight = height
            }
        } else if (type == 0x08 && size >= 36) {
            val status = le32(data, offset + 12)
            focusArea =
                when (status) {
                    0x20,
                    0x30,
                    0x31,
                    0x32 ->
                        PreviewFocusArea(
                            status,
                            le32(data, offset + 20),
                            le32(data, offset + 24),
                            le32(data, offset + 28),
                            le32(data, offset + 32),
                        )
                    else -> null
                }
        }
        offset += size
    }
    if (jpegLength == 0) return null
    val validFocusArea = focusArea?.takeIf {
        it.x in 0 until focusWidth &&
            it.y in 0 until focusHeight &&
            it.width in 1..focusWidth &&
            it.height in 1..focusHeight
    }
    return PreviewJpeg(data, jpegOffset, jpegLength, focusWidth, focusHeight, validFocusArea)
}
