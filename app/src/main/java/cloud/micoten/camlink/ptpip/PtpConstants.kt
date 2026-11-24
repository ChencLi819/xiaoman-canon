package cloud.micoten.camlink.ptpip

/**
 * PTP / PTP-IP 常量。
 *
 * 所有数值均与 libgphoto2 源码逐一核对：
 * - PTP/IP 包类型与字段偏移来自 camlibs/ptp2/ptpip.c
 * - 操作码 / 属性码 / 响应码来自 camlibs/ptp2/ptp.h
 */
object PtpIpType {
    const val INIT_COMMAND_REQUEST = 0x01
    const val INIT_COMMAND_ACK = 0x02
    const val INIT_EVENT_REQUEST = 0x03
    const val INIT_EVENT_ACK = 0x04
    const val INIT_FAIL = 0x05
    const val CMD_REQUEST = 0x06
    const val CMD_RESPONSE = 0x07
    const val EVENT = 0x08
    const val START_DATA_PACKET = 0x09
    const val DATA_PACKET = 0x0A
    const val END_DATA_PACKET = 0x0C
    const val PING = 0x0D
    const val PONG = 0x0E
}

/**
 * PTP-IP 命令包里的 DataPhase 字段。
 *
 * 注意：这里的取值和 ISO 15740 字面上的方向定义相反，libgphoto2 的实现是 “主机发送数据填 2，主机接收数据或无数据阶段填 1”，佳能机器按这个来。 见 ptpip.c:
 * `if ((dataphase & PTP_DP_DATA_MASK) == PTP_DP_SENDDATA) ... 2 else ... 1`
 */
object PtpDataPhase {
    const val NONE = 1
    const val RECEIVE = 1
    const val SEND = 2
}

/** 通用响应码。 */
object PtpRc {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val INVALID_TRANSACTION_ID = 0x2004
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val PARAMETER_NOT_SUPPORTED = 0x2006
    const val INCOMPLETE_TRANSFER = 0x2007
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val INVALID_OBJECT_FORMAT_CODE = 0x200B
    const val STORE_FULL = 0x200C
    const val ACCESS_DENIED = 0x200F
    const val DEVICE_BUSY = 0x2019
    const val INVALID_PARENT_OBJECT = 0x201A
    const val INVALID_DEVICE_PROP_VALUE = 0x201C
    const val SESSION_ALREADY_OPEN = 0x201E
    const val TRANSACTION_CANCELLED = 0x201F

    /** 佳能 EOS 私有响应码。 */
    const val EOS_LOW_BATTERY = 0xA101

    /** 对象还没准备好（实时取景帧取太早时会返回这个）。 */
    const val EOS_OBJECT_NOT_READY = 0xA102
    const val EOS_CANNOT_MAKE_OBJECT = 0xA104
    const val EOS_MEMORY_STATUS_NOT_READY = 0xA106

    fun name(code: Int): String =
        when (code) {
            OK -> "OK"
            GENERAL_ERROR -> "GeneralError"
            SESSION_NOT_OPEN -> "SessionNotOpen"
            INVALID_TRANSACTION_ID -> "InvalidTransactionID"
            OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
            PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
            INCOMPLETE_TRANSFER -> "IncompleteTransfer"
            INVALID_STORAGE_ID -> "InvalidStorageID"
            INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
            DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
            STORE_FULL -> "StoreFull"
            ACCESS_DENIED -> "AccessDenied"
            DEVICE_BUSY -> "DeviceBusy"
            INVALID_PARENT_OBJECT -> "InvalidParentObject"
            INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
            SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
            TRANSACTION_CANCELLED -> "TransactionCancelled"
            EOS_LOW_BATTERY -> "EOS_LowBattery"
            EOS_OBJECT_NOT_READY -> "EOS_ObjectNotReady"
            EOS_CANNOT_MAKE_OBJECT -> "EOS_CannotMakeObject"
            EOS_MEMORY_STATUS_NOT_READY -> "EOS_MemoryStatusNotReady"
            else -> "0x%04X".format(code)
        }
}

/** 操作码（标准 PTP + 佳能 EOS 私有扩展）。 */
object PtpOp {
    // 标准（注意 GetDeviceInfo 是 0x1001，GetStorageIDs 才是 0x1004）
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
    const val GET_PARTIAL_OBJECT = 0x101B

    // 佳能 EOS
    const val EOS_GET_STORAGE_IDS = 0x9101
    const val EOS_GET_STORAGE_INFO = 0x9102
    const val EOS_GET_OBJECT_INFO = 0x9103
    const val EOS_GET_OBJECT = 0x9104
    const val EOS_GET_PARTIAL_OBJECT = 0x9107
    const val EOS_GET_DEVICE_INFO_EX = 0x9108
    const val EOS_GET_OBJECT_INFO_EX = 0x9109
    const val EOS_GET_THUMB_EX = 0x910A
    const val EOS_SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val EOS_SET_REMOTE_MODE = 0x9114
    const val EOS_SET_EVENT_MODE = 0x9115
    const val EOS_GET_EVENT = 0x9116
    const val EOS_TRANSFER_COMPLETE = 0x9117
    const val EOS_CANCEL_TRANSFER = 0x9118
    const val EOS_RESET_TRANSFER = 0x9119
    const val EOS_KEEP_DEVICE_ON = 0x911D
    const val EOS_BULB_START = 0x9125
    const val EOS_BULB_END = 0x9126
    const val EOS_REQUEST_DEVICE_PROP_VALUE = 0x9127
    const val EOS_REMOTE_RELEASE_ON = 0x9128
    const val EOS_REMOTE_RELEASE_OFF = 0x9129
    const val EOS_SET_REQUEST_OLC_INFO_GROUP = 0x913D
    const val EOS_INITIATE_VIEWFINDER = 0x9151
    const val EOS_TERMINATE_VIEWFINDER = 0x9152
    const val EOS_GET_VIEWFINDER_DATA = 0x9153
    const val EOS_DO_AF = 0x9154
    const val EOS_TOUCH_AF_POSITION = 0x915B
    const val EOS_AF_CANCEL = 0x9160
    const val EOS_GET_OBJECT_64 = 0x9171
}

/** 佳能 EOS 设备属性码。 */
object PtpProp {
    const val APERTURE = 0xD101
    const val SHUTTER_SPEED = 0xD102
    const val ISO_SPEED = 0xD103
    const val EXPOSURE_COMPENSATION = 0xD104
    const val AUTO_EXPOSURE_MODE = 0xD105
    const val DRIVE_MODE = 0xD106
    const val FOCUS_MODE = 0xD108
    const val WHITE_BALANCE = 0xD109
    const val COLOR_SPACE = 0xD10A
    const val PICTURE_STYLE = 0xD10B
    const val METERING_MODE = 0xD10E
    const val BATTERY_POWER = 0xD111
    /** 电池来源（0xD112，u16）：主电池 / 另一块 / 外接，机身自报。 */
    const val BATTERY_SELECT = 0xD112
    /**
     * 电池详情（0xD1A6）与扩展电池详情（0xD21C）。ptp.h 里有码，但 libgphoto2 config.c 没有任何解析实现 —— 各机型返回块结构不公开， battery
     * health（健康度） 无法经标准 PTP 读取，界面只能展示电量与来源，详情留占位说明。
     */
    const val BATTERY_INFO = 0xD1A6
    const val BATTERY_INFO_EX = 0xD21C
    const val AVAILABLE_SHOTS = 0xD11B
    /** 快门计数（u32，只读）：机身累计快门释放次数，即通常说的"快门数"。 */
    const val SHUTTER_COUNTER = 0xD1AC
    const val CAPTURE_DESTINATION = 0xD11C
    const val IMAGE_FORMAT = 0xD120
    const val AF_SELECT_FOCUS_AREA = 0xD13C
    /** 拨盘位置，u16。只读，与 [AUTO_EXPOSURE_MODE] 的区别是它反映旋钮停在哪档。 */
    const val AE_MODE_DIAL = 0xD138

    val exposureControls =
        setOf(SHUTTER_SPEED, APERTURE, ISO_SPEED, EXPOSURE_COMPENSATION, WHITE_BALANCE)
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B1
    /** 录制目标，u16：0=None 3=SDRAM 4=Card。见 config.c `canon_eos_evfrecordtarget`。 */
    const val EVF_RECORD_STATUS = 0xD1B8
    const val OLC_INFO_VERSION = 0xD199
    const val LV_VIEW_TYPE_SELECT = 0xD1BC
    const val MOVIE_PARAM = 0xD1BE
    /** 实体拍照/短片开关，0 为拍照，1 为短片。 */
    const val FIXED_MOVIE = 0xD1C2
    const val FOCUS_INFO_EX = 0xD1D3

    /*
     * ── 损耗 / 机身状态 ──
     *
     * 「损耗」不止快门帘幕：反光板机构、电子触点、电源、温度、故障记录都算。
     * 下面这些码值全部来自 ptp.h 的 `PTP_DPC_CANON_EOS_*`（0xD100 段），
     * 标注「u32/u16」的以 config.c 的属性表类型为准；没标的那几个是佳能私有
     * 块，libgphoto2 也没有解析实现 —— 值宽度交给事件里的实际长度决定。
     */

    /**
     * 快门释放计数（0xD167）。与 [SHUTTER_COUNTER] 不同：它统计的是「释放」次数， 反光板预升、电子快门这类不走机械帘幕的释放也会计入，所以通常大于快门数。 ptp.h
     * 在该码后留了注释，提示可能要走 RequestDevicePropEx 才回值。
     */
    const val SHUTTER_RELEASE_COUNTER = 0xD167

    /** 错误历史（0xD169）。私有块，机身记录的历史故障。 */
    const val ERROR_HISTORY = 0xD169

    /** 镜头更换历史（0xD16A）。卡口电子触点的磨损程度看这个。 */
    const val LENS_EXCHANGE_HISTORY = 0xD16A

    /** 闪光灯更换历史（0xD16B）。热靴触点的磨损程度看这个。 */
    const val STROBO_EXCHANGE_HISTORY = 0xD16B

    /** 电源适配器信息（0xD1A7）。外接供电时才有意义。 */
    const val ADAPTER_INFO = 0xD1A7

    /** 镜头状态（0xD1A8）。 */
    const val LENS_STATUS = 0xD1A8

    /** 卡扩展信息（0xD1AA）。标准 StorageInfo 之外的补充位。 */
    const val CARD_EXTENSION = 0xD1AA

    /** 机身温度状态（0xD1AB）。过热是 CMOS 与快门机构的共同杀手。 */
    const val TEMP_STATUS = 0xD1AB

    /** 反光板下落状态（0xD1BD，u32，只读）。 */
    const val MIRROR_DOWN_STATUS = 0xD1BD

    /** 反光板锁定状态（0xD1BF，u32，只读）。 */
    const val MIRROR_LOCKUP_STATE = 0xD1BF

    /** 反光板预升设置（0xD13A，u32）。反光板预升会减少机震，但增加机构动作次数。 */
    const val MIRROR_UP_SETTING = 0xD13A

    /** 自动关机（0xD114，u32，单位秒）。0 表示关闭。 */
    const val AUTO_POWER_OFF = 0xD114

    /** 快门类型（0xD21A）。区分机械 / 电子前帘 / 电子快门。 */
    const val SHUTTER_TYPE = 0xD21A

    /** PTP 扩展版本（0xD119，u32）。 */
    const val PTP_EXTENSION_VERSION = 0xD119

    /** DPOF 版本（0xD11A，u16）。 */
    const val DPOF_VERSION = 0xD11A

    /** 当前写入的存储（0xD11E，u32）。 */
    const val CURRENT_STORAGE = 0xD11E

    /** 当前写入的文件夹（0xD11F，u32）。 */
    const val CURRENT_FOLDER = 0xD11F

    /** 镜头 ID（0xD1DD）。同一支镜头在不同机身上的编号。 */
    const val LENS_ID = 0xD1DD

    /** 机型 ID（0xD116，u32）。 */
    const val MODEL_ID = 0xD116

    /** 机身内部时间（0xD113，u32，Unix 秒）。 */
    const val CAMERA_TIME = 0xD113

    // 字符串型属性（值类型 PTP_DTC_STR），解析时要走文本分支
    const val OWNER = 0xD115
    const val CAMERA_NICKNAME = 0xD125
    const val SERIAL_NUMBER = 0xD1AF
    const val ARTIST = 0xD1D0
    const val COPYRIGHT = 0xD1D1
    const val LENS_NAME = 0xD1D8

    /**
     * 固件版本（u32，只读）。ptp.h `PTP_DPC_CANON_FirmwareVersion = 0xD031`。 四个字节依次解成 a.b.c.d，见 config.c
     * `_get_CANON_FirmwareVersion`。
     */
    const val FIRMWARE_VERSION = 0xD031

    val STRING_PROPS: Set<Int> =
        setOf(
            OWNER,
            CAMERA_NICKNAME,
            SERIAL_NUMBER,
            ARTIST,
            COPYRIGHT,
            LENS_NAME,
        )

    /** 该属性在事件流中的值宽度（字节）。以 ptp_unpack_EOS_events 的 switch 为准。 */
    fun valueWidth(code: Int): Int =
        when (code) {
            // u8 组：最容易搞错的一组
            WHITE_BALANCE,
            EXPOSURE_COMPENSATION,
            PICTURE_STYLE,
            METERING_MODE -> 1
            // u16 组（0xD120 画质按 gphoto2 config.c 是 PTP_DTC_UINT16：高字节主格式、低字节副格式）
            APERTURE,
            SHUTTER_SPEED,
            ISO_SPEED,
            COLOR_SPACE,
            BATTERY_POWER,
            DRIVE_MODE,
            IMAGE_FORMAT,
            EVF_MODE,
            AUTO_EXPOSURE_MODE,
            EVF_RECORD_STATUS,
            AE_MODE_DIAL -> 2
            // u32 组
            else -> 4
        }
}

/** 句柄特殊值。`0xFFFFFFFF` 在 Int 里就是 -1，写包时会按位铺满。 */
object PtpHandle {
    /** 表示“该存储卡的根目录”。 */
    const val SPECIAL = -1
    const val ROOT = 0
}

/** 存储卡访问能力位。 */
object PtpStorage {
    const val READ_WRITE = 0x0000
}

/** 事件码（佳能 EOS 私有，见 ptp.h `PTP_EC_CANON_EOS_*`）。 */
object PtpEvent {
    /** 事件通道上唯一的“正经”通知：相机有新事件，让主机去 GetEvent 取。 */
    const val REQUEST_GET_EVENT = 0xC101
    const val OBJECT_ADDED_EX = 0xC181
    const val OBJECT_REMOVED = 0xC182
    const val REQUEST_GET_OBJECT_INFO_EX = 0xC183
    const val STORAGE_STATUS_CHANGED = 0xC184
    const val REQUEST_OBJECT_TRANSFER = 0xC186
    const val OBJECT_INFO_CHANGED_EX = 0xC187
    const val PROP_VALUE_CHANGED = 0xC189
    const val AVAIL_LIST_CHANGED = 0xC18A
    const val CAMERA_STATUS_CHANGED = 0xC18B
    const val WILL_SOON_SHUTDOWN = 0xC18D
    const val SHUTDOWN_TIMER_UPDATED = 0xC18E
    const val BULB_EXPOSURE_TIME = 0xC194
    /** 短片录制时长（秒）。 */
    const val RECORDING_TIME = 0xC195
    const val AF_RESULT = 0xC1A3
    const val OLC_INFO_CHANGED = 0xC1A5
    const val OBJECT_ADDED_EX64 = 0xC1A7
    const val OBJECT_INFO_CHANGED_EX64 = 0xC1A8
    const val REQUEST_OBJECT_TRANSFER64 = 0xC1A9

    /** 兼容旧引用。 */
    const val OBJECT_ADDED = OBJECT_ADDED_EX
}

/** 对象格式码。 */
object PtpFormat {
    const val ASSOCIATION = 0x3001
    const val EXIF_JPEG = 0x3801
    const val TIFF = 0x380D
    const val CR2 = 0xB103
    const val CR3 = 0xB104
    const val MP4 = 0xB982

    fun isImage(ofc: Int): Boolean =
        when {
            ofc == EXIF_JPEG || ofc == TIFF || ofc == CR2 || ofc == CR3 -> true
            // 佳能部分机型 RAW 用 0x3801 之外的私有值，这里放宽到 JPEG/TIFF 区间
            ofc in 0x3800..0x38FF -> true
            else -> false
        }

    fun isRaw(ofc: Int): Boolean = ofc == CR2 || ofc == CR3
}
