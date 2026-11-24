package cloud.micoten.camlink

import android.app.Application
import cloud.micoten.camlink.util.AppLog

/**
 * 应用入口。
 *
 * 只做两件事：
 * 1. 把未捕获异常的堆栈塞进日志页，避免安卓上“闪退且无信息”的黑洞；
 * 2. 记录一个启动标记，方便确认当前跑的是哪一版。
 */
class CamLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        AppLog.i("App", "小满·卡农 启动，v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.e(
                    "Crash",
                    "线程 ${thread.name} 未捕获异常：${error.javaClass.name}: ${error.message}",
                    error,
                )
            } catch (_: Throwable) {
                // 记录失败也不能掩盖原始崩溃
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
