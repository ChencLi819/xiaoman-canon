package cloud.micoten.camlink.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用内日志总线：既输出到 logcat，也保留最近若干行供“日志”页展示。 调试相机协议时非常有用（PTP 操作码/响应码全部可见）。 */
object AppLog {

    private const val TAG = "CAMLink"
    private const val MAX_LINES = 600

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    private fun append(level: Char, tag: String, msg: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $msg"
        when (level) {
            'E' -> Log.e(TAG, "$tag: $msg")
            'W' -> Log.w(TAG, "$tag: $msg")
            'I' -> Log.i(TAG, "$tag: $msg")
            'V' -> Log.v(TAG, "$tag: $msg")
            else -> Log.d(TAG, "$tag: $msg")
        }
        val cur = _lines.value
        val next =
            if (cur.size >= MAX_LINES) {
                ArrayList<String>(MAX_LINES).apply {
                    addAll(cur.subList(cur.size - MAX_LINES + 1, cur.size))
                    add(line)
                }
            } else {
                ArrayList<String>(cur.size + 1).apply {
                    addAll(cur)
                    add(line)
                }
            }
        _lines.value = next
    }

    fun d(tag: String, msg: String) = append('D', tag, msg)

    fun i(tag: String, msg: String) = append('I', tag, msg)

    fun w(tag: String, msg: String) = append('W', tag, msg)

    /** 逐帧级细节：事件块内容这类一秒几十行、排查单次故障才有用的东西。 默认不刷屏，真要看时 `adb logcat -s CAMLink:V`。 */
    fun v(tag: String, msg: String) = append('V', tag, msg)

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        append(
            'E',
            tag,
            if (tr == null) msg else "$msg (${tr.javaClass.simpleName}: ${tr.message})",
        )
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
