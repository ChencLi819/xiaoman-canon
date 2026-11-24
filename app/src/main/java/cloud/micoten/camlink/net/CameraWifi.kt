package cloud.micoten.camlink.net

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.ContextCompat
import cloud.micoten.camlink.util.AppLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Wi-Fi 发现和接入。探测、命令和事件 socket 使用同一条 Android Network。 */
object CameraWifi {
    private const val TAG = "Wifi"
    private const val CANON_SERVICE = "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1"
    const val PTP_PORT = 15740

    data class Candidate(val ssid: String, val level: Int, val looksLikeCamera: Boolean)

    class NetworkAccessException(message: String, cause: Exception) : IOException(message, cause)

    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var requestedNetwork: Network? = null

    private fun cm(context: Context) =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun prioritizePreview(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val lock =
            runCatching {
                context.applicationContext
                    .getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CamLink:Preview")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
                .onFailure { AppLog.w(TAG, "无法开启系统 Wi-Fi 低延迟模式：${it.message}") }
                .getOrNull() ?: return
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            runCatching { lock.release() }
                .onFailure { AppLog.w(TAG, "释放 Wi-Fi 取景锁失败：${it.message}") }
        }
    }

    private fun looksLikeCamera(ssid: String): Boolean {
        val name = ssid.uppercase()
        return name.contains("CANON") || name.startsWith("EOS") || name.startsWith("KISS")
    }

    @Suppress("DEPRECATION")
    suspend fun scan(context: Context): List<Candidate> =
        withContext(Dispatchers.Main) {
            val app = context.applicationContext
            val wifi = app.getSystemService(WifiManager::class.java) ?: error("手机没有 Wi-Fi 服务")
            check(wifi.isWifiEnabled) { "请先打开手机 Wi-Fi" }
            check(
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                "扫描热点需要精确位置权限，请在应用权限中开启"
            }
            val location = app.getSystemService(LocationManager::class.java)
            val locationEnabled =
                if (Build.VERSION.SDK_INT >= 28) {
                    location?.isLocationEnabled == true
                } else {
                    location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                        location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                }
            check(locationEnabled) { "扫描热点需要开启手机的位置服务" }

            var receiver: BroadcastReceiver? = null
            val updated =
                try {
                    withTimeoutOrNull(6_000) {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            val listener =
                                object : BroadcastReceiver() {
                                    override fun onReceive(context: Context, intent: Intent) {
                                        if (cont.isActive) {
                                            cont.resume(
                                                intent.getBooleanExtra(
                                                    WifiManager.EXTRA_RESULTS_UPDATED,
                                                    false,
                                                )
                                            )
                                        }
                                    }
                                }
                            ContextCompat.registerReceiver(
                                app,
                                listener,
                                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                                ContextCompat.RECEIVER_NOT_EXPORTED,
                            )
                            receiver = listener
                            try {
                                if (!wifi.startScan() && cont.isActive) cont.resume(false)
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }
                    }
                } finally {
                    receiver?.let { app.unregisterReceiver(it) }
                }
            wifi.scanResults
                .filter { it.SSID.isNotBlank() }
                .sortedByDescending { it.level }
                .distinctBy { it.SSID }
                .map { Candidate(it.SSID, it.level, looksLikeCamera(it.SSID)) }
                .sortedByDescending { it.looksLikeCamera }
                .also { AppLog.i(TAG, "热点扫描更新=$updated，候选 ${it.size} 个") }
        }

    suspend fun connect(
        context: Context,
        ssid: String,
        passphrase: String,
        onNetworkLost: (Network) -> Unit,
    ): Network =
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                error("请先在手机的 Wi-Fi 设置中接入相机热点，再回应用扫描本网")
            }
            release(context)
            val manager = cm(context) ?: error("系统没有网络服务")
            val specifier =
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .apply {
                        if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase)
                    }
                    .build()
            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

            try {
                val network =
                    withTimeoutOrNull(45_000) {
                        suspendCancellableCoroutine<Network> { cont ->
                            val handler = Handler(Looper.getMainLooper())
                            val cb =
                                object : ConnectivityManager.NetworkCallback() {
                                    private val ipv4Ready = mutableSetOf<Network>()
                                    private val unblocked = mutableSetOf<Network>()
                                    private var denied: Network? = null
                                    private val confirmBlocked = Runnable {
                                        val blockedNetwork = denied
                                        if (
                                            callback === this &&
                                                cont.isActive &&
                                                blockedNetwork != null
                                        ) {
                                            cont.resumeWithException(
                                                accessDenied(
                                                    context,
                                                    blockedNetwork,
                                                    "热点接入",
                                                    SecurityException(
                                                        "Android reported the requested Wi-Fi network blocked"
                                                    ),
                                                )
                                            )
                                        }
                                    }

                                    override fun onAvailable(network: Network) {
                                        if (callback === this)
                                            AppLog.i(TAG, "Android 已提供相机热点网络 $network")
                                    }

                                    override fun onLinkPropertiesChanged(
                                        network: Network,
                                        linkProperties: LinkProperties,
                                    ) {
                                        if (callback !== this || !cont.isActive) return
                                        if (
                                            linkProperties.linkAddresses.any {
                                                it.address is Inet4Address
                                            }
                                        ) {
                                            ipv4Ready += network
                                            completeRequest(network)
                                        } else {
                                            ipv4Ready -= network
                                        }
                                    }

                                    override fun onBlockedStatusChanged(
                                        network: Network,
                                        blocked: Boolean,
                                    ) {
                                        if (callback !== this) return
                                        if (blocked) {
                                            unblocked -= network
                                            if (cont.isActive) {
                                                denied = network
                                                handler.removeCallbacks(confirmBlocked)
                                                handler.postDelayed(confirmBlocked, 2_000)
                                            } else if (network == requestedNetwork) {
                                                requestedNetwork = null
                                                onNetworkLost(network)
                                            }
                                        } else {
                                            if (denied == network) clearBlocked()
                                            unblocked += network
                                            completeRequest(network)
                                        }
                                    }

                                    private fun clearBlocked() {
                                        denied = null
                                        handler.removeCallbacks(confirmBlocked)
                                    }

                                    private fun completeRequest(network: Network) {
                                        if (
                                            cont.isActive &&
                                                network in ipv4Ready &&
                                                network in unblocked
                                        ) {
                                            clearBlocked()
                                            requestedNetwork = network
                                            cont.resume(network)
                                        }
                                    }

                                    override fun onUnavailable() {
                                        clearBlocked()
                                        if (cont.isActive)
                                            cont.resumeWithException(
                                                IllegalStateException("未能接入热点，请检查密码和系统连接确认")
                                            )
                                    }

                                    override fun onLost(network: Network) {
                                        if (callback !== this) return
                                        if (denied == network) clearBlocked()
                                        ipv4Ready -= network
                                        unblocked -= network
                                        if (network == requestedNetwork) {
                                            requestedNetwork = null
                                            onNetworkLost(network)
                                        }
                                    }
                                }
                            callback = cb
                            manager.requestNetwork(request, cb, handler)
                        }
                    } ?: error("连接相机热点超时，请确认热点还在广播")
                waitForSocketAccess(context, network)
                AppLog.i(TAG, "相机热点网络 $network 已取得 IPv4 并通过 socket 检查")
                network
            } catch (e: Exception) {
                release(context)
                if (e is SecurityException) {
                    val permission = if (Build.VERSION.SDK_INT >= 33) "附近的设备" else "位置"
                    throw IOException("Android 未允许应用请求相机热点。请检查「$permission」权限，或点「用系统连接」。", e)
                }
                throw e
            }
        }

    private suspend fun waitForSocketAccess(context: Context, network: Network) =
        withContext(Dispatchers.IO) {
            try {
                openSocket(context, network).close()
            } catch (_: NetworkAccessException) {
                // 某些设备先发网络回调，稍后才允许这个应用绑定 socket。
                delay(350)
                try {
                    openSocket(context, network).close()
                } catch (_: NetworkAccessException) {
                    delay(750)
                    openSocket(context, network).close()
                }
            }
        }

    fun release(context: Context) {
        val current = callback
        callback = null
        requestedNetwork = null
        if (current != null) runCatching { cm(context)?.unregisterNetworkCallback(current) }
    }

    @Suppress("DEPRECATION")
    suspend fun wifiNetwork(context: Context): Network? =
        withContext(Dispatchers.IO) {
            val manager = cm(context) ?: return@withContext null
            val candidates =
                (listOfNotNull(requestedNetwork, manager.activeNetwork) +
                        manager.allNetworks.toList())
                    .distinct()
            var denied: NetworkAccessException? = null
            for (network in candidates) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                )
                    continue
                try {
                    openSocket(context, network).close()
                    return@withContext network
                } catch (e: NetworkAccessException) {
                    denied = e
                    AppLog.w(TAG, "Wi-Fi $network 不可用：${e.cause?.message}")
                } catch (e: IOException) {
                    AppLog.d(TAG, "Wi-Fi $network 已不可用：${e.message}")
                }
            }
            denied?.let { throw it }
            null
        }

    fun openSocket(context: Context, network: Network): Socket {
        val socket = Socket()
        try {
            routeSocket(context, network, "TCP 绑定") { network.bindSocket(socket) }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private inline fun routeSocket(
        context: Context,
        network: Network,
        stage: String,
        bind: () -> Unit,
    ) {
        // 默认 Wi-Fi 已由 Android 路由；实体副网络仍需要系统授权后才能显式绑定。
        if (cm(context)?.activeNetwork == network) return
        try {
            bind()
        } catch (e: Exception) {
            if (isPermissionDenied(e)) throw accessDenied(context, network, stage, e)
            throw e
        }
    }

    private fun isPermissionDenied(error: Exception): Boolean =
        generateSequence<Throwable>(error) { it.cause }
            .any {
                it is SecurityException ||
                    (it is ErrnoException &&
                        it.errno in listOf(OsConstants.EPERM, OsConstants.EACCES)) ||
                    it.message?.let { message ->
                        message.contains("EPERM") || message.contains("EACCES")
                    } == true
            }

    private fun accessDenied(
        context: Context,
        network: Network,
        stage: String,
        cause: Exception,
    ): NetworkAccessException {
        val manager = cm(context)
        val active = manager?.activeNetwork
        val vpn =
            manager
                ?.let { it.activeNetwork?.let(it::getNetworkCapabilities) }
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        AppLog.w(
            TAG,
            "网络访问被拒绝：阶段=$stage，Wi-Fi=$network，默认网络=$active，VPN=$vpn，Android API=${Build.VERSION.SDK_INT}，原因=${cause.message}",
        )
        val help =
            if (vpn) {
                "手机当前启用了 VPN，请允许访问局域网后重试"
            } else {
                "请点「用系统连接」，由手机 Wi-Fi 设置接入相机热点"
            }
        return NetworkAccessException("Android 拒绝访问相机 Wi-Fi（$stage，网络 $network）。$help", cause)
    }

    fun explainConnectionError(context: Context, network: Network, error: Exception): Exception =
        if (error !is NetworkAccessException && isPermissionDenied(error)) {
            accessDenied(context, network, "PTP/IP 连接", error)
        } else error

    suspend fun scanHosts(context: Context): List<String> {
        val network = wifiNetwork(context) ?: error("请先让手机连接相机热点或相机所在的 Wi-Fi")
        return scanHosts(context, network)
    }

    suspend fun scanHosts(
        context: Context,
        network: Network,
        fallback: Boolean = true,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val link = cm(context)?.getLinkProperties(network) ?: error("Wi-Fi 已断开，请重新连接")
            val local =
                link.linkAddresses.firstOrNull { it.address is Inet4Address }
                    ?: error("手机还没有取得 Wi-Fi 地址，请稍后重试")
            openSocket(context, network).close()
            val announced = discoverCanon(context, network, link.interfaceName)
            if (announced.isNotEmpty() || !fallback) return@withContext announced

            val localIp = local.address.hostAddress
            val preferred =
                (link.routes.mapNotNull { it.gateway as? Inet4Address } +
                        InetAddress.getByName("192.168.1.2"))
                    .filter { it != local.address && local.isSameSubnet(it) }
                    .mapNotNull { it.hostAddress }
                    .distinct()
            val prefix = local.prefixLength.coerceAtLeast(24)
            val address = local.address.address
            val last = address[3].toInt() and 0xFF
            val size = 1 shl (32 - prefix)
            val start = last and (size - 1).inv()
            val firstOctets = address.take(3).joinToString(".") { (it.toInt() and 0xFF).toString() }
            val hosts =
                (start + 1 until start + size - 1)
                    .map { "$firstOctets.$it" }
                    .filter { it != localIp && it !in preferred }
            probe(context, network, preferred + hosts).also {
                AppLog.i(TAG, "Wi-Fi 上的 PTP/IP 候选：$it")
            }
        }

    private fun android.net.LinkAddress.isSameSubnet(other: InetAddress): Boolean {
        val a = address.address
        val b = other.address
        if (a.size != b.size) return false
        return a.indices.all { i ->
            val bits = (prefixLength - 8 * i).coerceIn(0, 8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            ((a[i].toInt() xor b[i].toInt()) and mask) == 0
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun probe(
        context: Context,
        network: Network,
        hosts: List<String>,
    ): List<String> = coroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(32)
        hosts
            .map { host ->
                async(dispatcher) {
                    try {
                        openSocket(context, network).use {
                            it.connect(InetSocketAddress(host, PTP_PORT), 450)
                        }
                        host
                    } catch (e: IOException) {
                        if (e is NetworkAccessException) throw e
                        if (isPermissionDenied(e)) throw accessDenied(context, network, "TCP 探测", e)
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /** EOS Utility 模式用 Canon SSDP 公告。只采信报文来源地址。 */
    private suspend fun discoverCanon(
        context: Context,
        network: Network,
        interfaceName: String?,
    ): List<String> {
        val iface = interfaceName?.let { NetworkInterface.getByName(it) } ?: return emptyList()
        val wifi =
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?: return emptyList()
        val group = InetSocketAddress("239.255.255.250", 1900)
        val lock =
            wifi.createMulticastLock("camlink-canon-discovery").apply { setReferenceCounted(false) }
        val coroutineContext = kotlin.coroutines.coroutineContext
        val found = linkedSetOf<String>()

        try {
            lock.acquire()
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(1900))
                routeSocket(context, network, "发现相机") { network.bindSocket(socket) }
                socket.networkInterface = iface
                socket.joinGroup(group, iface)
                socket.soTimeout = 350
                val search =
                    ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $CANON_SERVICE\r\n\r\n")
                        .toByteArray()
                socket.send(DatagramPacket(search, search.size, group))
                val buffer = ByteArray(4096)
                val until = SystemClock.elapsedRealtime() + 1_800
                while (SystemClock.elapsedRealtime() < until) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val message = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (message.startsWith("NOTIFY ") || message.startsWith("HTTP/1.1 200")) {
                        val service =
                            message
                                .lineSequence()
                                .firstOrNull {
                                    it.startsWith("NT:", true) || it.startsWith("ST:", true)
                                }
                                ?.substringAfter(':')
                                ?.trim()
                        val expired =
                            message.lineSequence().any {
                                it.trim().equals("NTS: ssdp:byebye", true)
                            }
                        if (!expired && service.equals(CANON_SERVICE, true)) {
                            packet.address.hostAddress?.let { found += it }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            AppLog.d(TAG, "Canon SSDP 未能完成：${e.message}")
        } finally {
            if (lock.isHeld) lock.release()
        }
        return found.toList().also { AppLog.i(TAG, "Canon SSDP 候选：$it") }
    }
}
