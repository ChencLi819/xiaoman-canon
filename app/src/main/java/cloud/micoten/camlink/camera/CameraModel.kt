package cloud.micoten.camlink.camera

import cloud.micoten.camlink.ptpip.PtpProp

/**
 * 可调参数的种类。
 *
 * UI 只认这个枚举，具体取哪些码值由 [CameraProfile] 决定 —— 换成别的机身时，界面一行代码都不用动。
 */
enum class ParamKind(val propertyCode: Int) {
    SHUTTER(PtpProp.SHUTTER_SPEED),
    APERTURE(PtpProp.APERTURE),
    ISO(PtpProp.ISO_SPEED),
    EXPOSURE_COMP(PtpProp.EXPOSURE_COMPENSATION),
    WHITE_BALANCE(PtpProp.WHITE_BALANCE);

    /** 界面上显示的名字。 */
    val label: String
        get() =
            when (this) {
                SHUTTER -> "快门"
                APERTURE -> "光圈"
                ISO -> "感光度"
                EXPOSURE_COMP -> "曝光补偿"
                WHITE_BALANCE -> "白平衡"
            }

    /** 取景页四格上的短名。 */
    val short: String
        get() =
            when (this) {
                SHUTTER -> "Tv"
                APERTURE -> "Av"
                ISO -> "ISO"
                EXPOSURE_COMP -> "EV"
                WHITE_BALANCE -> "WB"
            }
}

/**
 * 曝光模式，也就是机身上那个实体拨盘停在哪一档。
 *
 * 关键事实：EOS 机身的 `0xD105 AutoExposureMode` 是**只读**的（写它会回 `0x2019 DeviceBusy`）。所以这个枚举在 App 里的角色是「镜像 +
 * 上锁」： 拨盘停在哪档由机身说了算，App 只负责把该灰的格子灰掉， 免得用户在一个机身根本不听的格子上点半天。
 *
 * [codes] 是机身可能回给我们的码值。多值是因为同一档在不同固件、不同地区的 回报值不一致（A+ 有报 9 的也有报 19 的），收全一点比漏判成「未知」好。
 */
enum class ExposureMode(
    val label: String,
    val short: String,
    /** 机身回传的主码值。 */
    val code: Int,
    /** 同一档位的别名码值：不同固件回报不一致，收全了才不会误判成「未知」。 */
    private val aliases: List<Int> = emptyList(),
) {
    /** 场景智能自动：机身全接管，五个格子全部只读。 */
    AUTO("A+", "A+", 9, listOf(19, 22)),

    /** 程序自动：机身配光圈快门，只留 ISO / EV / WB 给用户。 */
    P("P", "P", 0),

    /** 快门优先：快门可调，光圈由机身配。 */
    TV("Tv", "Tv", 1),

    /** 光圈优先：光圈可调，快门由机身配。 */
    AV("Av", "Av", 2),

    /** 全手动：五项全开。 */
    M("M", "M", 3),

    /** B 门：200D II 没有独立 B 档，是把 M 档快门拧到 BULB 得到的。 */
    BULB("B", "B", 4),

    /** 特殊场景（人像/风光/运动/微距/夜景…）：机身全接管。 */
    SCN(
        "SCN",
        "SCN",
        0x19,
        listOf(10, 11, 12, 13, 14, 15, 0x17, 0x18, 0x1B, 0x2D, 0x31, 0x32, 0x33, 0x34),
    ),

    /** 创意滤镜：机身全接管，出图带滤镜。 */
    FILTER("滤镜", "FX", 0x1E, (0x1F..0x26).toList()),

    /** 由机身切换到短片模式。 */
    MOVIE("短片", "MOV", 0x14),

    /**
     * 认不出来的档位。
     *
     * 刻意让五项全开：认不出档位时宁可让用户点，也不能把整条参数栏锁死。
     */
    UNKNOWN("—", "?", -1);

    /** 这一档下某个参数是否允许用户改。 */
    fun allows(kind: ParamKind): Boolean =
        when (this) {
            AUTO,
            SCN,
            FILTER -> false
            P ->
                kind == ParamKind.ISO ||
                    kind == ParamKind.EXPOSURE_COMP ||
                    kind == ParamKind.WHITE_BALANCE
            TV -> kind != ParamKind.APERTURE
            AV -> kind != ParamKind.SHUTTER
            M,
            BULB -> true
            /* 短片模式下曝光由短片管线决定，五项都还能点，能写进去算机身给面子 */
            MOVIE,
            UNKNOWN -> true
        }

    /** 这一档下快门格子该显示的字；非 B 门返回 null 表示照常显示码值。 */
    fun shutterPlaceholder(): String? = if (this == BULB) "BULB" else null

    fun usesBulb(shutterCode: Int?): Boolean =
        this == BULB || (this == M && (shutterCode == 0x0004 || shutterCode == 0x000C))

    companion object {
        /** 码值 → 档位；认不出返回 [UNKNOWN]。 */
        fun from(code: Int?): ExposureMode {
            if (code == null) return UNKNOWN
            return entries.firstOrNull { code == it.code || code in it.aliases } ?: UNKNOWN
        }
    }
}

/**
 * 一款机身的能力档案。
 *
 * 设计要点：码表从 [EosValues] 的并集里**裁剪**，不是重写。 裁剪的意义是“界面上只出现这台机器真的能做到的档位” —— 200D II 最高 1/4000，就不该让用户在
 * 1/8000 上点半天没反应。
 *
 * 裁剪表是“界面推荐值”，不是“唯一合法值”：相机换镜头、机身切自动档时 会回报裁剪表外的码值，[label] 此时使用完整码表。
 */
class CameraProfile(
    val id: String,
    val model: String,
    /** 用于匹配相机自报名字的片段，全部小写 */
    val matchKeys: List<String>,
    private val tables: Map<ParamKind, List<Choice>>,
    /** 蓝牙遥控要求机身先把驱动模式设成这个，否则 BLE 快门按了没反应 */
    val bleDriveHint: String = "10s/遥控 或 2s/遥控",
) {
    /** 这一档下某个参数是否可改 —— 机型支持 + 档位允许，两个条件都要过。 */
    fun isEditable(kind: ParamKind, mode: ExposureMode): Boolean =
        table(kind).isNotEmpty() && mode.allows(kind)

    fun table(kind: ParamKind): List<Choice> = tables[kind].orEmpty()

    /** 码值 → 显示名。裁剪表里找不到就退回并集表，再找不到才显示十六进制。 */
    fun label(kind: ParamKind, code: Int?): String {
        if (code == null) return "—"
        tables[kind]
            ?.firstOrNull { it.code == code }
            ?.let {
                return it.label
            }
        return EosValues.label(EosValues.of(kind), code)
    }

    override fun toString(): String = model
}

/* ────────────────────────────────────────────── 佳能 EOS 200D II */

private fun between(src: List<Choice>, lo: Int, hi: Int): List<Choice> = src.filter {
    it.code in lo..hi
}

/**
 * 同名档位只留第一个。
 *
 * 佳能码表里 1/3 步与 1/2 步长同值各有一条编码（1/6、1/10、1/20、f/1.8 这类成对出现）， 照单全收的话滚轮上会挨着两条一模一样的档位，拨一格数值纹丝不动，纯属噪音。
 * 只裁这份给滚轮用的切片；并集表保留全部编码，回显时该叫什么还叫什么。
 */
private fun onePerLabel(src: List<Choice>): List<Choice> {
    val seen = HashSet<String>()
    return src.filter { seen.add(it.label) }
}

/**
 * 佳能 EOS 200D II（海外版 Rebel SL3 / 日版 Kiss X10，渠道常写作 200D Mark II）： 2420 万像素 APS-C、DIGIC 8、ISO
 * 100–25600（可扩 51200）、 快门 30"–1/4000 + B 门、9 点 AF、4K25 视频、Wi-Fi(b/g/n) + 蓝牙 4.1 BLE。
 *
 * 国内官方机型名是「EOS 200D II」，界面上一律用这个名字；`matchKeys` 里把各地区 写法都收进来，海外版机身自报 Rebel SL3 / Kiss X10
 * 时也能对上同一份参数表。
 */
val Eos200DII: CameraProfile =
    CameraProfile(
        id = "canon-eos-200d-ii",
        model = "EOS 200D II",
        matchKeys = listOf("200d ii", "200d mark ii", "200d2", "250d", "sl3", "kiss x10"),
        tables =
            mapOf(
                /* 30" – 1/4000，另加自动与 B 门；1/8000 是更高机身才有的档 */
                ParamKind.SHUTTER to
                    onePerLabel(
                        EosValues.SHUTTER.filter { it.code == 0x0000 || it.code == 0x0004 } +
                            between(EosValues.SHUTTER, 0x0010, 0x0098)
                    ),
                /* 光圈随镜头变，这里只保留消费级 EF-S 镜头够得着的 f/1.0–f/32；
                 * 「自动」在并集表里有三种编码，切片只认 0x0000，滚轮上不能出现三条自动 */
                ParamKind.APERTURE to
                    onePerLabel(
                        EosValues.APERTURE.filter { it.code == 0x0000 } +
                            between(EosValues.APERTURE, 0x0008, 0x0058)
                    ),
                /*
                 * 原生 ISO 100–25600，扩展档 51200；更低的 50 与更高的 10 万级不收。
                 * 自动档只留 0x0000：0xFFFF 是机身没报值时的占位码，不该出现在能写的档位里；
                 * 0x0001「自动 ISO」在 200D II 上与 0x0000 同义，留一条就够。
                 */
                ParamKind.ISO to
                    (EosValues.ISO.filter { it.code == 0x0000 } +
                        between(EosValues.ISO, 0x0048, 0x0090)),
                /* 曝光补偿整档 1/3 步，-3.0 … +3.0，全留 */
                ParamKind.EXPOSURE_COMP to EosValues.EXPOSURE_COMP,
                ParamKind.WHITE_BALANCE to EosValues.WHITE_BALANCE,
            ),
    )

/**
 * 机型登记表。
 *
 * 现在只落地 200D II 一款，但界面已经全部按 [CameraProfile] 取值， 加第二台机器只需要在这里 push 一个 profile。
 */
object CameraCatalog {

    val ALL: List<CameraProfile> = listOf(Eos200DII)

    val DEFAULT: CameraProfile = Eos200DII

    /**
     * 按相机自报的名字找 profile。
     *
     * PTP/IP 握手上报的名字各家写法不一（"Canon EOS 200D II" / "EOS 200D" / "Canon EOS Rebel SL3"），
     * 所以只做包含匹配；匹配不上就退回默认机型 —— 总比界面上一个参数都没有好。
     */
    fun detect(reported: String?): CameraProfile {
        val s = reported?.lowercase().orEmpty()
        if (s.isBlank()) return DEFAULT
        return ALL.firstOrNull { p -> p.matchKeys.any { s.contains(it) } } ?: DEFAULT
    }

    /**
     * 按 id 找 profile，找不到返回 null。
     *
     * 手动指定的机型存了 id 在本地，卸载重装或降级后这个 id 可能已不在表里， 这种情况下让调用方退回 [detect]，不要拿一个空档案把界面清空。
     */
    fun byId(id: String?): CameraProfile? {
        if (id.isNullOrBlank()) return null
        return ALL.firstOrNull { it.id == id }
    }
}
