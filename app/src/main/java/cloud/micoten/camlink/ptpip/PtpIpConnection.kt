package cloud.micoten.camlink.ptpip

import cloud.micoten.camlink.util.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.min

/** PTP 层的错误。传输层异常统一用这个类型向上抛。 */
class PtpException(message: String, val ptpCode: Int = 0, cause: Throwable? = null) :
    IOException(message, cause)

/** 一次操作事务的结果。 */
class PtpResult(
    val code: Int,
    val params: IntArray = IntArray(0),
    val data: ByteArray? = null,
) {
    val ok: Boolean
        get() = code == PtpRc.OK

    fun describe(): String = PtpRc.name(code)

    fun requireOk(): PtpResult {
        if (!ok) throw PtpException("PTP 操作失败：${PtpRc.name(code)}", code)
        return this
    }
}

/** 数据接收目的地（用于把大文件直接写盘，避免整张 RAW 进内存）。 */
fun interface DataSink {
    fun write(buf: ByteArray, offset: Int, length: Int)
}

/**
 * PTP/IP（ISO 15740 over TCP）传输层。
 *
 * 一条相机连接包含两条 TCP 通道：
 * - 命令通道（cmd）：所有 Operation 请求、数据阶段、响应
 * - 事件通道（evt）：相机主动上报的事件 两条通道都连相机同一个端口（默认 15740），握手顺序严格按 libgphoto2：
 *
 *   connect(cmd) -> Init_Command_Request -> 读 Init_Command_Ack（取出 eventPipeId） connect(evt) ->
 *   Init_Event_Request(eventPipeId) -> 读 Init_Event_Ack
 *
 * 所有 `execute*` 方法都是阻塞的，调用方需要放到 IO 线程。类本身通过 [lock] 保证同一时刻只有一个事务在跑。
 */
class PtpIpConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val clientGuid: ByteArray,
    private val createSocket: () -> Socket,
    private val clientName: String = "Xiaoman Canon",
) {

    companion object {
        const val DEFAULT_PORT = 15740
        private const val TAG = "PtpIp"
        private const val WRITE_BLOCK_SIZE = 65536
        private const val READ_CHUNK = 32 * 1024
        private const val MAX_PACKET = 64 * 1024 * 1024
        private const val MAX_DATA = 512 * 1024 * 1024
        private const val MAX_SKIP = 32
    }

    private val lock = Any()

    private var cmdSocket: Socket? = null
    private var evtSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var evtIn: InputStream? = null
    private var evtThread: Thread? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    var eventPipeId: Int = 0
        private set

    var cameraName: String = ""
        private set

    var cameraGuid: ByteArray = ByteArray(0)
        private set

    private var transactionId: Int = 0

    /** 相机通过事件通道上报的事件回调（在事件线程上调用）。 */
    @Volatile var onEvent: ((eventCode: Int, params: IntArray) -> Unit)? = null

    /** 连接被异常断开时的回调。 */
    @Volatile var onDisconnected: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- 连接

    fun connect(
        connectTimeoutMs: Int = 8000,
        ioTimeoutMs: Int = 20000,
        onStage: (String) -> Unit = {},
    ) {
        synchronized(lock) {
            closeQuietly()
            transactionId = 0
            try {
                connectChannels(connectTimeoutMs, ioTimeoutMs, onStage)
            } catch (e: Exception) {
                closeQuietly()
                throw e
            }
        }
    }

    private fun connectChannels(
        connectTimeoutMs: Int,
        ioTimeoutMs: Int,
        onStage: (String) -> Unit,
    ) {
        onStage("正在连接相机")
        AppLog.i(TAG, "正在连接 $host:$port")
        val cmd = createSocket()
        cmdSocket = cmd
        cmd.tcpNoDelay = true
        try {
            cmd.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: SocketTimeoutException) {
            runCatching { cmd.close() }
            throw PtpException(
                "相机无响应：${host}:${port} ${connectTimeoutMs / 1000} 秒内没有应答。" +
                    "请确认相机正停在「等待连接」画面、IP 无误，且路由器没有开启 AP 隔离。",
                cause = e,
            )
        } catch (e: ConnectException) {
            runCatching { cmd.close() }
            val reason = e.message.orEmpty()
            AppLog.w(TAG, "TCP 连接失败 $host:$port：$reason")
            val detail =
                if (
                    reason.contains("ECONNREFUSED") ||
                        reason.contains("Connection refused", ignoreCase = true)
                ) {
                    "$host 拒绝了 $port 端口的连接。"
                } else "无法连接 $host:$port。"
            throw PtpException(
                detail + "请核对相机 IP，并在「遥控（EOS Utility）」中确认「开始设备配对」。",
                cause = e,
            )
        }
        cmd.soTimeout = 45_000
        cmdIn = BufferedInputStream(cmd.getInputStream(), 64 * 1024)
        cmdOut = cmd.getOutputStream()

        onStage("等待相机确认连接。首次配对请在机身确认 Xiaoman Canon")
        sendInitCommandRequest()
        readInitCommandAck()
        cmd.soTimeout = ioTimeoutMs
        AppLog.i(TAG, "相机应答：name=$cameraName eventPipeId=$eventPipeId")
        onStage("相机已应答，正在建立事件通道")

        // 事件通道：相机可能还没准备好，ECONNREFUSED 时短暂重试
        var evt: Socket? = null
        var attempt = 0
        var lastError: IOException? = null
        while (evt == null && attempt < 3) {
            try {
                val s = createSocket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    s.soTimeout = connectTimeoutMs
                    evt = s
                } catch (e: IOException) {
                    runCatching { s.close() }
                    throw e
                }
            } catch (e: IOException) {
                lastError = e
                attempt++
                if (attempt >= 3 || e !is ConnectException) break
                AppLog.w(TAG, "事件通道连接失败（第 $attempt 次）：${e.message}，100ms 后重试")
                Thread.sleep(100)
            }
        }
        if (evt == null) {
            closeQuietly()
            throw PtpException("事件通道连接失败：${lastError?.message}", cause = lastError)
        }
        evtSocket = evt
        evtIn = evt.getInputStream()

        sendInitEventRequest()
        readInitEventAck()
        evt.soTimeout = 0

        isConnected = true
        startEventThread()
        AppLog.i(TAG, "PTP/IP 握手完成")
    }

    fun openSession() =
        synchronized(lock) {
            val response =
                transact(PtpOp.OPEN_SESSION, intArrayOf(1), null, false, null, null, tid = 0)
            if (!response.ok && response.code != PtpRc.SESSION_ALREADY_OPEN) {
                throw PtpException("打开 PTP 会话失败：${response.describe()}", response.code)
            }
        }

    fun <T> withCommandTimeout(timeoutMs: Int, block: () -> T): T =
        synchronized(lock) {
            val socket = cmdSocket ?: throw PtpException("相机命令通道已关闭")
            val previous = socket.soTimeout
            socket.soTimeout = timeoutMs
            try {
                block()
            } finally {
                if (cmdSocket === socket && !socket.isClosed) socket.soTimeout = previous
            }
        }

    fun close() {
        synchronized(lock) {
            isConnected = false
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        isConnected = false
        runCatching { cmdSocket?.close() }
        runCatching { evtSocket?.close() }
        cmdSocket = null
        evtSocket = null
        cmdIn = null
        cmdOut = null
        evtIn = null
        evtThread = null
    }

    private fun ensureConnected(): OutputStream = cmdOut ?: throw PtpException("尚未连接相机")

    // ------------------------------------------------------------ 初始化握手

    private fun sendInitCommandRequest() {
        val nameBytes = (clientName + "\u0000").toByteArray(Charsets.UTF_16LE)
        val total = 8 + 16 + nameBytes.size + 4
        val buf = ByteArray(total)
        putLe32(buf, 0, total)
        putLe32(buf, 4, PtpIpType.INIT_COMMAND_REQUEST)
        // GUID 16 字节，偏移 8
        System.arraycopy(normalizeGuid(), 0, buf, 8, 16)
        // 名称（UTF-16LE，含结尾 0），偏移 24
        System.arraycopy(nameBytes, 0, buf, 24, nameBytes.size)
        // 版本：minor 在前，major 在后
        val verOffset = 24 + nameBytes.size
        putLe16(buf, verOffset, 0) // minor
        putLe16(buf, verOffset + 2, 1) // major

        ensureConnected().write(buf)
        ensureConnected().flush()
    }

    private fun normalizeGuid(): ByteArray {
        val g = ByteArray(16)
        System.arraycopy(clientGuid, 0, g, 0, min(clientGuid.size, 16))
        return g
    }

    private fun readInitCommandAck() {
        val pkt =
            try {
                readPacket()
            } catch (e: PtpException) {
                throw PtpException("应用没有收到相机的命令通道应答：${e.message}", cause = e)
            }
        if (pkt.type != PtpIpType.INIT_COMMAND_ACK) {
            if (pkt.type == PtpIpType.INIT_FAIL) {
                val reason = if (pkt.body.size >= 4) "0x%08X".format(le32(pkt.body, 0)) else "未知"
                throw PtpException("相机拒绝配对（$reason），请在相机 EOS Utility 菜单添加设备，或断开正在连接的设备")
            }
            throw PtpException("握手失败：期望 Init_Command_Ack，收到类型 ${pkt.type}")
        }
        val b = pkt.body
        if (b.size < 20) throw PtpException("Init_Command_Ack 长度异常：${b.size}")
        eventPipeId = le32(b, 0)
        cameraGuid = b.copyOfRange(4, 20)
        cameraName = decodeUtf16Le(b, 20).trim('\u0000')
    }

    private fun sendInitEventRequest() {
        val buf = ByteArray(12)
        putLe32(buf, 0, 12)
        putLe32(buf, 4, PtpIpType.INIT_EVENT_REQUEST)
        putLe32(buf, 8, eventPipeId)
        val s = evtSocket ?: throw PtpException("事件通道未建立")
        s.getOutputStream().write(buf)
        s.getOutputStream().flush()
    }

    private fun readInitEventAck() {
        val pkt =
            try {
                readPacket(evtIn ?: throw PtpException("事件通道未建立"))
            } catch (e: IOException) {
                throw PtpException("相机已应答，但事件通道握手失败：${e.message}", cause = e)
            }
        if (pkt.type != PtpIpType.INIT_EVENT_ACK) {
            throw PtpException("事件通道握手失败：类型 ${pkt.type}")
        }
    }

    // -------------------------------------------------------------- 事件线程

    private fun startEventThread() {
        val output = evtSocket?.getOutputStream()
        val t =
            Thread(
                {
                    val input = evtIn
                    try {
                        while (isConnected && input != null) {
                            val pkt = readPacket(input)
                            when (pkt.type) {
                                PtpIpType.EVENT -> {
                                    // 标准 EVENT 包体是 u16 code + u32 transactionId + u32 参数…，
                                    // 但部分实现（含测试用的虚拟相机）只发 u16 code，
                                    // 所以下限放到 2 字节，避免把有效通知整条丢掉。
                                    if (pkt.body.size >= 2) {
                                        val code = le16(pkt.body, 0)
                                        val n =
                                            if (pkt.body.size >= 6) (pkt.body.size - 6) / 4 else 0
                                        val params =
                                            IntArray(n.coerceAtLeast(0)) { i ->
                                                le32(pkt.body, 6 + i * 4)
                                            }
                                        onEvent?.invoke(code, params)
                                    }
                                }
                                PtpIpType.PING -> if (output != null) sendPong(output)
                                else -> AppLog.d(TAG, "事件通道收到非事件包 type=${pkt.type}")
                            }
                        }
                    } catch (e: Exception) {
                        synchronized(lock) {
                            if (isConnected && evtIn === input) {
                                reportDisconnected("相机事件通道断开：${e.message.orEmpty()}")
                            }
                        }
                    }
                },
                "ptp-event",
            )
        t.isDaemon = true
        evtThread = t
        t.start()
    }

    private fun sendPong(output: OutputStream) {
        val header = ByteArray(8)
        putLe32(header, 0, header.size)
        putLe32(header, 4, PtpIpType.PONG)
        output.write(header)
        output.flush()
    }

    private fun reportDisconnected(reason: String) {
        closeQuietly()
        onDisconnected?.invoke(reason)
    }

    // ---------------------------------------------------------------- 事务

    fun <T> execute(
        opCode: Int,
        params: IntArray = IntArray(0),
        payload: ByteArray? = null,
        receiveData: Boolean = false,
        sink: DataSink? = null,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
        block: (PtpResult) -> T,
    ): T =
        synchronized(lock) {
            val result =
                try {
                    transact(opCode, params, payload, receiveData, sink, onProgress)
                } catch (e: IOException) {
                    val message = "相机命令 0x%04X 失败：%s".format(opCode, e.message.orEmpty())
                    if (isConnected) reportDisconnected(message)
                    throw PtpException(message, cause = e)
                }
            if (result.code == PtpRc.SESSION_NOT_OPEN && opCode != PtpOp.OPEN_SESSION) {
                reportDisconnected("相机会话已关闭（命令 0x%04X），请重新连接".format(opCode))
            }
            block(result)
        }

    /** 无数据阶段的普通操作。 */
    fun command(opCode: Int, vararg params: Int): PtpResult = execute(opCode, params) { it }

    /** 需要接收数据的操作，返回完整数据。 */
    fun commandWithData(opCode: Int, vararg params: Int): PtpResult =
        execute(opCode, params, receiveData = true) { it }

    /** 需要发送数据的操作（如 SetDevicePropValueEx）。 */
    fun commandWithPayload(opCode: Int, payload: ByteArray, vararg params: Int): PtpResult =
        execute(opCode, params, payload = payload) { it }

    private fun transact(
        opCode: Int,
        params: IntArray,
        payload: ByteArray?,
        receiveData: Boolean,
        sink: DataSink?,
        onProgress: ((Long, Long) -> Unit)?,
        tid: Int = nextTransactionId(),
    ): PtpResult {
        if (!isConnected) throw PtpException("尚未连接相机")
        val logTransaction =
            opCode != PtpOp.EOS_GET_VIEWFINDER_DATA && opCode != PtpOp.EOS_GET_EVENT
        if (logTransaction) {
            AppLog.d(
                TAG,
                ">> 0x%04X tid=%d params=%s%s"
                    .format(
                        opCode,
                        tid,
                        params.joinToString(","),
                        if (payload != null) " data=${payload.size}B" else "",
                    ),
            )
        }

        // 1. Operation Request
        sendCommandRequest(
            opCode,
            tid,
            params,
            if (payload != null) PtpDataPhase.SEND else PtpDataPhase.RECEIVE,
        )

        // 2. 主机 -> 相机 的数据阶段
        if (payload != null) sendDataPayload(tid, payload)

        // 3. 相机 -> 主机 的数据阶段
        var data: ByteArray? = null
        if (receiveData) {
            val outcome = readDataPayload(tid, sink, onProgress)
            when (outcome) {
                is DataOutcome.Refused -> {
                    if (
                        logTransaction ||
                            (outcome.code != PtpRc.DEVICE_BUSY &&
                                outcome.code != PtpRc.EOS_OBJECT_NOT_READY)
                    ) {
                        AppLog.d(TAG, "<< 0x%04X 数据阶段被拒绝，响应码 0x%04X".format(opCode, outcome.code))
                    }
                    return PtpResult(outcome.code, outcome.params)
                }
                is DataOutcome.Complete -> {
                    data = outcome.data
                    if (logTransaction) AppLog.d(TAG, "<< 数据 ${outcome.total}B")
                }
            }
        }

        // 4. 响应
        val resp = readResponse(tid)
        if (
            logTransaction ||
                (resp.code != PtpRc.OK &&
                    resp.code != PtpRc.DEVICE_BUSY &&
                    resp.code != PtpRc.EOS_OBJECT_NOT_READY)
        ) {
            AppLog.d(
                TAG,
                "<< 0x%04X ${PtpRc.name(resp.code)} tid=${resp.transId}".format(resp.code),
            )
        }
        return PtpResult(resp.code, resp.params, data)
    }

    private fun nextTransactionId(): Int {
        transactionId = if (transactionId >= Int.MAX_VALUE - 1) 1 else transactionId + 1
        return transactionId
    }

    private fun sendCommandRequest(opCode: Int, tid: Int, params: IntArray, dataPhase: Int) {
        val n = min(params.size, 5)
        val total = 18 + n * 4
        val buf = ByteArray(total)
        putLe32(buf, 0, total)
        putLe32(buf, 4, PtpIpType.CMD_REQUEST)
        putLe32(buf, 8, dataPhase)
        putLe16(buf, 12, opCode)
        putLe32(buf, 14, tid)
        for (i in 0 until n) putLe32(buf, 18 + i * 4, params[i])
        val out = ensureConnected()
        out.write(buf)
        out.flush()
    }

    private fun sendDataPayload(tid: Int, payload: ByteArray) {
        val header = ByteArray(20)
        putLe32(header, 0, 20)
        putLe32(header, 4, PtpIpType.START_DATA_PACKET)
        putLe32(header, 8, tid)
        putLe32(header, 12, payload.size)
        putLe32(header, 16, 0)
        val out = ensureConnected()
        out.write(header)

        var offset = 0
        if (payload.isEmpty()) {
            out.flush()
            return
        }
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val chunk = min(remaining, WRITE_BLOCK_SIZE)
            val isLast = offset + chunk >= payload.size
            val pktLen = 12 + chunk
            val buf = ByteArray(pktLen)
            putLe32(buf, 0, pktLen)
            putLe32(buf, 4, if (isLast) PtpIpType.END_DATA_PACKET else PtpIpType.DATA_PACKET)
            putLe32(buf, 8, tid)
            System.arraycopy(payload, offset, buf, 12, chunk)
            out.write(buf)
            offset += chunk
        }
        out.flush()
    }

    private sealed class DataOutcome {
        class Complete(val total: Long, val data: ByteArray?) : DataOutcome()

        class Refused(val code: Int, val params: IntArray) : DataOutcome()
    }

    private fun readDataPayload(
        tid: Int,
        sink: DataSink?,
        onProgress: ((Long, Long) -> Unit)?,
    ): DataOutcome {
        val input = cmdIn ?: throw PtpException("命令通道已关闭")
        var pkt = readDataPacket(input)

        // 相机直接回响应（通常是出错，没有数据阶段）
        if (pkt.type == PtpIpType.CMD_RESPONSE) {
            val resp = parseResponse(pkt.body, tid)
            return DataOutcome.Refused(resp.code, resp.params)
        }
        if (pkt.type != PtpIpType.START_DATA_PACKET) {
            throw PtpException("期望 Start_Data_Packet，收到类型 ${pkt.type}")
        }
        if (pkt.body.size < 12 || le32(pkt.body, 0) != tid) throw PtpException("数据起始包与当前事务不匹配")
        val total =
            (le32(pkt.body, 8).toLong() shl 32) or (le32(pkt.body, 4).toLong() and 0xFFFFFFFFL)
        if (total < 0 || (sink == null && total > MAX_DATA)) throw PtpException("相机声明的数据长度超出上限")

        val data = if (sink == null && total <= 8L * 1024 * 1024) ByteArray(total.toInt()) else null
        val acc = if (sink == null && data == null) ByteArrayOutputStream(8 * 1024 * 1024) else null
        val buffer = if (data == null) ByteArray(READ_CHUNK) else null
        var received = 0L

        while (received < total) {
            pkt = readDataPacket(input, headerOnly = true)
            if (pkt.type == PtpIpType.CMD_RESPONSE) {
                val resp = parseResponse(pkt.body, tid)
                if (resp.code == PtpRc.OK) throw PtpException("相机在数据结束前返回成功")
                return DataOutcome.Refused(resp.code, resp.params)
            }
            if (pkt.type != PtpIpType.DATA_PACKET && pkt.type != PtpIpType.END_DATA_PACKET) {
                throw PtpException("数据阶段收到意外包类型 ${pkt.type}")
            }
            val payloadLen = pkt.length - 12
            if (payloadLen < 0) throw PtpException("数据包长度异常 ${pkt.length}")
            val transaction = ByteArray(4)
            readFully(input, transaction, 0, transaction.size)
            if (le32(transaction, 0) != tid || payloadLen > total - received) {
                throw PtpException("数据包与当前事务或声明长度不匹配")
            }
            if (data != null) {
                readFully(input, data, received.toInt(), payloadLen)
                received += payloadLen
            } else if (buffer != null) {
                var left = payloadLen
                while (left > 0) {
                    val want = min(left, buffer.size)
                    val got = input.read(buffer, 0, want)
                    if (got <= 0) throw EOFException("数据阶段读取中断")
                    sink?.write(buffer, 0, got)
                    acc?.write(buffer, 0, got)
                    left -= got
                    received += got
                }
            }
            onProgress?.invoke(received, total)
            if (pkt.type == PtpIpType.END_DATA_PACKET) break
        }
        if (received != total) throw PtpException("数据不完整（已收 $received/$total 字节）")
        return DataOutcome.Complete(received, data ?: acc?.toByteArray())
    }

    private fun readDataPacket(input: InputStream, headerOnly: Boolean = false): Packet {
        repeat(MAX_SKIP) {
            val packet = if (headerOnly) readPacketHeaderOnly(input) else readPacket(input)
            if (packet.type != PtpIpType.PING) return packet
            sendPong(ensureConnected())
        }
        throw PtpException("相机连续发送探活包，未返回数据")
    }

    private class Response(val code: Int, val transId: Int, val params: IntArray)

    private fun parseResponse(body: ByteArray, expectedTid: Int): Response {
        if (body.size < 6) throw PtpException("响应包长度异常 ${body.size}")
        val code = le16(body, 0)
        val tid = le32(body, 2)
        if (tid != expectedTid) throw PtpException("相机响应事务号不匹配（收到 $tid，预期 $expectedTid）")
        val n = (body.size - 6) / 4
        val params = IntArray(n.coerceAtLeast(0)) { i -> le32(body, 6 + i * 4) }
        return Response(code, tid, params)
    }

    private fun readResponse(tid: Int): Response {
        val input = cmdIn ?: throw PtpException("命令通道已关闭")
        var skipped = 0
        while (true) {
            val pkt = readPacket(input)
            when (pkt.type) {
                PtpIpType.CMD_RESPONSE -> return parseResponse(pkt.body, tid)
                PtpIpType.PING -> sendPong(ensureConnected())
                // 数据阶段的尾巴：跳过继续等响应
                PtpIpType.END_DATA_PACKET,
                PtpIpType.DATA_PACKET,
                PtpIpType.START_DATA_PACKET -> {
                    if (++skipped > MAX_SKIP) throw PtpException("等待响应时丢弃数据包过多")
                }
                else -> {
                    AppLog.d(TAG, "等待响应时收到包类型 ${pkt.type}，忽略")
                    if (++skipped > MAX_SKIP) throw PtpException("等待响应超时")
                }
            }
        }
    }

    // ------------------------------------------------------------ 包读写工具

    private class Packet(val length: Int, val type: Int, val body: ByteArray)

    private fun readPacket(input: InputStream = cmdIn ?: throw PtpException("命令通道已关闭")): Packet {
        val hdr = ByteArray(8)
        readFully(input, hdr, 0, 8)
        val len = le32(hdr, 0)
        val type = le32(hdr, 4)
        if (len < 8 || len > MAX_PACKET) throw PtpException("非法包长度 $len（type=$type）")
        val body = ByteArray(len - 8)
        readFully(input, body, 0, body.size)
        return Packet(len, type, body)
    }

    /** 只读头部，随后由调用方按需读取包体（用于大块数据流式处理）。 */
    private fun readPacketHeaderOnly(input: InputStream): Packet {
        val hdr = ByteArray(8)
        readFully(input, hdr, 0, 8)
        val len = le32(hdr, 0)
        val type = le32(hdr, 4)
        if (len < 8 || len > MAX_PACKET) throw PtpException("非法包长度 $len（type=$type）")
        // 数据包：包体只保留 4 字节 transid 的语义，实际数据由调用方流式读取
        val body =
            if (type == PtpIpType.CMD_RESPONSE) {
                val b = ByteArray(len - 8)
                readFully(input, b, 0, b.size)
                b
            } else {
                ByteArray(0)
            }
        return Packet(len, type, body)
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n =
                try {
                    input.read(buf, offset + read, length - read)
                } catch (e: SocketTimeoutException) {
                    throw PtpException("读取超时（已收 $read/$length 字节）", cause = e)
                }
            if (n < 0) throw EOFException("连接已被相机关闭")
            read += n
        }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

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

    private fun decodeUtf16Le(b: ByteArray, offset: Int): String {
        if (offset >= b.size) return ""
        val sb = StringBuilder()
        var i = offset
        while (i + 1 < b.size) {
            val c = le16(b, i)
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }
}
