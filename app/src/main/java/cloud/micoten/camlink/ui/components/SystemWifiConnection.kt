package cloud.micoten.camlink.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberSystemWifiConnection(): (String, String) -> Unit {
    val context = LocalContext.current
    val openWifiSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    val saveNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) openWifiSettings()
        }

    return { ssid, password ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ssid.isNotBlank()) {
            try {
                val network =
                    WifiNetworkSuggestion.Builder()
                        .setSsid(ssid.trim())
                        .apply {
                            if (password.isNotEmpty()) setWpa2Passphrase(password)
                        }
                        .build()
                val intent =
                    Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                        putParcelableArrayListExtra(
                            Settings.EXTRA_WIFI_NETWORK_LIST,
                            arrayListOf(network),
                        )
                    }
                saveNetwork.launch(intent)
            } catch (_: ActivityNotFoundException) {
                openWifiSettings()
            } catch (_: IllegalArgumentException) {
                openWifiSettings()
            }
        } else {
            openWifiSettings()
        }
    }
}
