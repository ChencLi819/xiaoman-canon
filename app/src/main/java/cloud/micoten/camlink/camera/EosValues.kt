package cloud.micoten.camlink.camera

/**
 * 佳能 EOS 私有属性的码值表。
 *
 * 这些不是随便猜的：每一项都对照 libgphoto2 `config.c` 里的 `canon_shutterspeed` / `canon_isospeed` /
 * `canon_aperture` / `canon_eos_whitebalance` / `canon_expcompensation` 五张表逐条抄下来， 并且
 * `SetDevicePropValueEx` 只认这些码值 —— 直接把 "1/125" 写进去相机会 返回 `InvalidDevicePropValue`。
 *
 * 所以 App 里所有参数调整都是“在表里挪一格”，而不是自己算数值。
 */
data class Choice(val code: Int, val label: String)

object EosValues {

    /** 快门速度（属性 0xD102，u16）。含 auto / bulb。 */
    val SHUTTER: List<Choice> =
        listOf(
            Choice(0x0000, "自动"),
            Choice(0x0004, "Bulb"),
            Choice(0x000C, "Bulb"),
            Choice(0x0010, "30\""),
            Choice(0x0013, "25\""),
            Choice(0x0014, "20\""),
            Choice(0x0015, "20\""),
            Choice(0x0018, "15\""),
            Choice(0x001B, "13\""),
            Choice(0x001C, "10\""),
            Choice(0x001D, "10\""),
            Choice(0x0020, "8\""),
            Choice(0x0023, "6\""),
            Choice(0x0024, "6\""),
            Choice(0x0025, "5\""),
            Choice(0x0028, "4\""),
            Choice(0x002B, "3\""),
            Choice(0x002C, "3\""),
            Choice(0x002D, "2.5\""),
            Choice(0x0030, "2\""),
            Choice(0x0033, "1.6\""),
            Choice(0x0034, "1.5\""),
            Choice(0x0035, "1.3\""),
            Choice(0x0038, "1\""),
            Choice(0x003B, "0.8\""),
            Choice(0x003C, "0.7\""),
            Choice(0x003D, "0.6\""),
            Choice(0x0040, "0.5\""),
            Choice(0x0043, "0.4\""),
            Choice(0x0044, "0.3\""),
            Choice(0x0045, "0.3\""),
            Choice(0x0048, "1/4"),
            Choice(0x004B, "1/5"),
            Choice(0x004C, "1/6"),
            Choice(0x004D, "1/6"),
            Choice(0x0050, "1/8"),
            Choice(0x0053, "1/10"),
            Choice(0x0054, "1/10"),
            Choice(0x0055, "1/13"),
            Choice(0x0058, "1/15"),
            Choice(0x005B, "1/20"),
            Choice(0x005C, "1/20"),
            Choice(0x005D, "1/25"),
            Choice(0x0060, "1/30"),
            Choice(0x0063, "1/40"),
            Choice(0x0064, "1/45"),
            Choice(0x0065, "1/50"),
            Choice(0x0068, "1/60"),
            Choice(0x006B, "1/80"),
            Choice(0x006C, "1/90"),
            Choice(0x006D, "1/100"),
            Choice(0x0070, "1/125"),
            Choice(0x0073, "1/160"),
            Choice(0x0074, "1/180"),
            Choice(0x0075, "1/200"),
            Choice(0x0078, "1/250"),
            Choice(0x007B, "1/320"),
            Choice(0x007C, "1/350"),
            Choice(0x007D, "1/400"),
            Choice(0x0080, "1/500"),
            Choice(0x0083, "1/640"),
            Choice(0x0084, "1/750"),
            Choice(0x0085, "1/800"),
            Choice(0x0088, "1/1000"),
            Choice(0x008B, "1/1250"),
            Choice(0x008C, "1/1500"),
            Choice(0x008D, "1/1600"),
            Choice(0x0090, "1/2000"),
            Choice(0x0093, "1/2500"),
            Choice(0x0094, "1/3000"),
            Choice(0x0095, "1/3200"),
            Choice(0x0098, "1/4000"),
            Choice(0x009B, "1/5000"),
            Choice(0x009C, "1/6000"),
            Choice(0x009D, "1/6400"),
            Choice(0x00A0, "1/8000"),
            Choice(0x00A8, "1/16000"),
        )

    /** ISO 感光度（属性 0xD103，u16）。 */
    val ISO: List<Choice> =
        listOf(
            Choice(0xFFFF, "默认"),
            Choice(0x0028, "6"),
            Choice(0x0030, "12"),
            Choice(0x0038, "25"),
            Choice(0x0040, "50"),
            Choice(0x0043, "64"),
            Choice(0x0045, "80"),
            Choice(0x0048, "100"),
            Choice(0x004B, "125"),
            Choice(0x004D, "160"),
            Choice(0x0050, "200"),
            Choice(0x0053, "250"),
            Choice(0x0055, "320"),
            Choice(0x0058, "400"),
            Choice(0x005B, "500"),
            Choice(0x005D, "640"),
            Choice(0x0060, "800"),
            Choice(0x0063, "1000"),
            Choice(0x0065, "1250"),
            Choice(0x0068, "1600"),
            Choice(0x006B, "2000"),
            Choice(0x006D, "2500"),
            Choice(0x0070, "3200"),
            Choice(0x0073, "4000"),
            Choice(0x0075, "5000"),
            Choice(0x0078, "6400"),
            Choice(0x007B, "8000"),
            Choice(0x007D, "10000"),
            Choice(0x0080, "12800"),
            Choice(0x0083, "16000"),
            Choice(0x0085, "20000"),
            Choice(0x0088, "25600"),
            Choice(0x008B, "32000"),
            Choice(0x008D, "40000"),
            Choice(0x0090, "51200"),
            Choice(0x0093, "64000"),
            Choice(0x0095, "80000"),
            Choice(0x0098, "102400"),
            Choice(0x00A0, "204800"),
            Choice(0x00A8, "409600"),
            Choice(0x00B0, "819200"),
            Choice(0x0000, "自动"),
            Choice(0x0001, "自动 ISO"),
        )

    /** 光圈（属性 0xD101，u16）。 */
    val APERTURE: List<Choice> =
        listOf(
            Choice(0x0000, "自动"),
            Choice(0xFFFF, "自动"),
            Choice(0x00B0, "自动"),
            Choice(0x0008, "f/1.0"),
            Choice(0x000B, "f/1.1"),
            Choice(0x000C, "f/1.2"),
            Choice(0x000D, "f/1.2"),
            Choice(0x0010, "f/1.4"),
            Choice(0x0013, "f/1.6"),
            Choice(0x0014, "f/1.8"),
            Choice(0x0015, "f/1.8"),
            Choice(0x0018, "f/2.0"),
            Choice(0x001B, "f/2.2"),
            Choice(0x001C, "f/2.5"),
            Choice(0x001D, "f/2.5"),
            Choice(0x0020, "f/2.8"),
            Choice(0x0023, "f/3.2"),
            Choice(0x0024, "f/3.5"),
            Choice(0x0025, "f/3.5"),
            Choice(0x0028, "f/4.0"),
            Choice(0x002B, "f/4.5"),
            Choice(0x002C, "f/4.5"),
            Choice(0x002D, "f/5.0"),
            Choice(0x0030, "f/5.6"),
            Choice(0x0033, "f/6.3"),
            Choice(0x0034, "f/6.7"),
            Choice(0x0035, "f/7.1"),
            Choice(0x0038, "f/8.0"),
            Choice(0x003B, "f/9.0"),
            Choice(0x003C, "f/9.5"),
            Choice(0x003D, "f/10"),
            Choice(0x0040, "f/11"),
            Choice(0x0043, "f/13"),
            Choice(0x0044, "f/13"),
            Choice(0x0045, "f/14"),
            Choice(0x0048, "f/16"),
            Choice(0x004B, "f/18"),
            Choice(0x004C, "f/19"),
            Choice(0x004D, "f/20"),
            Choice(0x0050, "f/22"),
            Choice(0x0053, "f/25"),
            Choice(0x0054, "f/27"),
            Choice(0x0055, "f/29"),
            Choice(0x0058, "f/32"),
            Choice(0x005B, "f/36"),
            Choice(0x005C, "f/38"),
            Choice(0x005D, "f/40"),
            Choice(0x0060, "f/45"),
            Choice(0x0063, "f/51"),
            Choice(0x0064, "f/54"),
            Choice(0x0065, "f/57"),
            Choice(0x0068, "f/64"),
            Choice(0x006B, "f/72"),
            Choice(0x006C, "f/76"),
            Choice(0x006D, "f/81"),
            Choice(0x0070, "f/91"),
        )

    /** 白平衡（属性 0xD109，u8）。 */
    val WHITE_BALANCE: List<Choice> =
        listOf(
            Choice(0, "自动"),
            Choice(1, "日光"),
            Choice(2, "阴天"),
            Choice(3, "钨丝灯"),
            Choice(4, "荧光灯"),
            Choice(5, "闪光灯"),
            Choice(6, "手动"),
            Choice(7, "未知"),
            Choice(8, "阴影"),
            Choice(9, "色温"),
            Choice(10, "自定义 1"),
            Choice(11, "自定义 2"),
            Choice(12, "自定义 3"),
            Choice(15, "手动 2"),
            Choice(16, "手动 3"),
            Choice(18, "手动 4"),
            Choice(19, "手动 5"),
            Choice(20, "自定义 4"),
            Choice(21, "自定义 5"),
            Choice(23, "自动（白色优先）"),
        )

    /**
     * 曝光补偿（属性 0xD104，u8）。
     *
     * 注意码值是“反着长”的：+3EV 是 0x00，0EV 是 0x18，-3EV 是 0x30。 所以加减必须按表索引走，不能对码值做算术。
     */
    val EXPOSURE_COMP: List<Choice> =
        listOf(
            Choice(0xFF, "默认"),
            Choice(0x00, "+3.0"),
            Choice(0x03, "+2.7"),
            Choice(0x04, "+2.5"),
            Choice(0x05, "+2.3"),
            Choice(0x08, "+2.0"),
            Choice(0x0B, "+1.7"),
            Choice(0x0C, "+1.5"),
            Choice(0x0D, "+1.3"),
            Choice(0x10, "+1.0"),
            Choice(0x13, "+0.7"),
            Choice(0x14, "+0.5"),
            Choice(0x15, "+0.3"),
            Choice(0x18, "0"),
            Choice(0x1B, "-0.3"),
            Choice(0x1C, "-0.5"),
            Choice(0x1D, "-0.7"),
            Choice(0x20, "-1.0"),
            Choice(0x23, "-1.3"),
            Choice(0x24, "-1.5"),
            Choice(0x25, "-1.7"),
            Choice(0x28, "-2.0"),
            Choice(0x2B, "-2.3"),
            Choice(0x2C, "-2.5"),
            Choice(0x2D, "-2.7"),
            Choice(0x30, "-3.0"),
        )

    // ------------------------------------------------------------------ 只读信息表

    /** 驱动模式（属性 0xD106，u16）。 对照 libgphoto2 config.c `canon_eos_drive_mode`，14 项全收。 */
    val DRIVE: List<Choice> =
        listOf(
            Choice(0x00, "单拍"),
            Choice(0x01, "连拍"),
            Choice(0x02, "短片"),
            Choice(0x04, "高速连拍"),
            Choice(0x05, "低速连拍"),
            Choice(0x06, "静音单拍"),
            Choice(0x07, "连拍定时"),
            Choice(0x10, "10 秒自拍"),
            Choice(0x11, "2 秒自拍"),
            Choice(0x12, "超高速连拍"),
            Choice(0x13, "静音单拍"),
            Choice(0x14, "静音连拍"),
            Choice(0x15, "静音高速连拍"),
            Choice(0x16, "静音低速连拍"),
        )

    /**
     * 测光模式（属性 0xD10E，u8）。
     *
     * config.c 里没有这张表（0xD10E 未收录），这里用佳能官方文档的已知值， 认不出的码走 [label] 的 hex 兜底。
     */
    val METERING: List<Choice> =
        listOf(
            Choice(4, "评价测光"),
            Choice(5, "局部测光"),
            Choice(6, "中央重点平均测光"),
        )

    /**
     * 画质（属性 0xD120，u16）。
     *
     * 高字节是主格式、低字节是副格式（0xFF 表示没有第二张），见 config.c `_get_Canon_EOS_ImageFormat`。单格式码表按
     * `canon_eos_single_ImageFormats` 全量抄录 —— 不同机型共用同一字节码，撞码就撞了，显示不会出错。
     */
    fun imageFormatText(v: Int): String {
        val main = formatName((v shr 8) and 0xFF)
        val sub = v and 0xFF
        return if (sub == 0xFF) main else "$main + ${formatName(sub)}"
    }

    private fun formatName(b: Int): String =
        when (b) {
            0x0C -> "RAW"
            0x1C -> "mRAW"
            0x2C -> "sRAW"
            0x0B -> "cRAW"
            0x03 -> "L"
            0x13 -> "M"
            0x23 -> "S"
            0x02 -> "cL"
            0x12 -> "cM"
            0x22 -> "cS"
            0xD3 -> "S1"
            0xE3 -> "S2"
            0xF3 -> "S3"
            0xD2 -> "cS1"
            0xE2 -> "cS2"
            0xF2 -> "cS3"
            0x53 -> "M1"
            0x63 -> "M2"
            0x52 -> "cM1"
            0x62 -> "cM2"
            // 1DXm2 的用户/自定义压缩档
            0x01 -> "L"
            0x51 -> "M1"
            0x61 -> "M2"
            0x21 -> "S"
            // R5m2 的档位
            0x00 -> "L"
            0x10 -> "M"
            0xE0 -> "S1"
            0xD0 -> "S2"
            else -> "0x%02X".format(b)
        }

    /** 固件版本（属性 0xD031，u32）：四个字节依次解成 a.b.c.d。 */
    fun firmwareText(v: Long): String =
        "%d.%d.%d.%d"
            .format(
                ((v shr 24) and 0xFF).toInt(),
                ((v shr 16) and 0xFF).toInt(),
                ((v shr 8) and 0xFF).toInt(),
                (v and 0xFF).toInt(),
            )

    /** 色彩空间（属性 0xD10A，u16）。 对照 config.c `canon_eos_colorspace`，只有两项。 */
    val COLOR_SPACE: List<Choice> =
        listOf(
            Choice(0x01, "sRGB"),
            Choice(0x02, "Adobe RGB"),
        )

    /** 照片风格（属性 0xD10B，u8）。 对照 config.c `canon_eos_picturestyle`，11 项全收。 */
    val PICTURE_STYLE: List<Choice> =
        listOf(
            Choice(0x81, "标准"),
            Choice(0x82, "人像"),
            Choice(0x83, "风光"),
            Choice(0x84, "中性"),
            Choice(0x85, "可靠设置"),
            Choice(0x86, "单色"),
            Choice(0x87, "自动"),
            Choice(0x88, "精细细节"),
            Choice(0x21, "用户自定义 1"),
            Choice(0x22, "用户自定义 2"),
            Choice(0x23, "用户自定义 3"),
        )

    /** 存储卡的访问能力（标准 StorageInfo 的 AccessCapability 字段）。 */
    fun accessText(v: Int): String =
        when (v) {
            0x0000 -> "可读写"
            0x0001 -> "只读"
            0x0002 -> "只读（可删除）"
            else -> "0x%04X".format(v)
        }

    /** 存储卡类型（标准 StorageInfo 的 StorageType 字段）。 */
    fun storageTypeText(v: Int): String =
        when (v) {
            0x0000 -> "未定义"
            0x0001 -> "固定 ROM"
            0x0002 -> "固定 RAM"
            0x0003 -> "可移动 RAM"
            0x0004 -> "可移动 ROM"
            else -> "0x%04X".format(v)
        }

    /** 容量按人类习惯分级显示，超过 1GB 用 GB，否则 MB。 */
    fun bytesText(v: Long): String =
        when {
            v <= 0L -> "—"
            v >= 1L shl 30 -> "%.1f GB".format(v.toDouble() / (1L shl 30))
            else -> "%d MB".format(v shr 20)
        }

    // ------------------------------------------------------------------ 查询

    /**
     * 按参数种类取全量码表。
     *
     * 这是所有佳能 EOS 机身的并集（libgphoto2 的原始表），机型的 [CameraProfile] 只在它的基础上按机身规格裁剪。保留并集是因为：相机偶尔会回报一个不在机型
     * 裁剪表里的码值（同一档速在不同机身上的两个码），那时还要靠它把名字叫对。
     */
    fun of(kind: ParamKind): List<Choice> =
        when (kind) {
            ParamKind.SHUTTER -> SHUTTER
            ParamKind.APERTURE -> APERTURE
            ParamKind.ISO -> ISO
            ParamKind.EXPOSURE_COMP -> EXPOSURE_COMP
            ParamKind.WHITE_BALANCE -> WHITE_BALANCE
        }

    fun label(table: List<Choice>, code: Int): String =
        table.firstOrNull { it.code == code }?.label ?: "0x%02X".format(code)

    fun shotsText(v: Long): String = if (v in 0 until 0xFFFFFFFFL) v.toString() else "—"
}

enum class CameraBattery(val bars: Int, val label: String) {
    FULL(4, "充足"),
    HIGH(3, "较高"),
    MEDIUM(2, "中等"),
    LOW(1, "电量低"),
    CRITICAL(0, "请充电"),
    UNKNOWN(0, "暂无读数");

    companion object {
        /** Canon EOS BatteryPower (0xD111) 的档位编码。 */
        fun fromCode(code: Long?): CameraBattery =
            when (code) {
                2L -> FULL
                4L -> HIGH
                1L -> MEDIUM
                5L -> LOW
                0L -> CRITICAL
                else -> UNKNOWN
            }
    }
}
