package cloud.micoten.camlink

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.nav.CanonRoot
import cloud.micoten.camlink.ui.theme.XiaomanCanonTheme

/**
 * 唯一的 Activity，界面全部由 Compose 承载。
 *
 * 这里只留三件与界面无关的事：常亮、系统栏沉浸、运行时权限。
 */
class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 拍延时/长曝光时会长时间盯着取景，息屏等于断线
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 页面自己画到系统栏底下，状态栏图标明暗由主题决定（见 CanonRoot 的 BarTint）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        requestMissingPermissions()

        setContent {
            val appearance by vm.appearance.collectAsState()
            XiaomanCanonTheme(appearance) {
                CanonRoot(vm = vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 手机已在 Wi-Fi 上但应用离线时，自动扫本网直连相机（见 CameraViewModel）。
        // 内部有 15 秒冷却与链路状态校验，反复切前台不会造成负担。
        vm.autoConnectIfPossible()
    }

    private fun requestMissingPermissions() {
        val wanted = ArrayList<String>(5)
        // 拉热点列表（扫相机 SSID）在 13+ 走 NEARBY_WIFI_DEVICES，
        // 但 getScanResults() 在部分机型上仍要用定位权限，两个一起要最保险。
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        // 蓝牙遥控：12 起是「附近的设备」两个运行时权限，11 及以下靠定位权限兜着
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing =
            wanted
                .filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                .toMutableList()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Manifest.permission.ACCESS_FINE_LOCATION in missing
        )
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
