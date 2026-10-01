package com.example.lyretest.report

import com.example.lyretest.arrange.ArrangeParams
import com.example.lyretest.arrange.TaggedNote
import com.example.lyretest.arrange.countChords
import com.example.lyretest.arrange.countPlayable
import com.example.lyretest.midi.DRUM_CHANNEL
import com.example.lyretest.midi.MidiFileData

/** GM 标准音色名，用于解析报告里显示每个通道是什么乐器 */
private val GM_INSTRUMENTS = listOf(
    "大钢琴", "亮音钢琴", "电钢琴(EP)", "酒吧钢琴", "电钢琴1", "电钢琴2", "羽管键琴", "翼琴",
    "钢片琴", "钟琴", "音乐盒", "颤音琴", "马林巴", "木琴", "管钟", "扬琴",
    "风琴1", "打击风琴", "摇滚风琴", "教堂管风琴", "簧风琴", "手风琴", "口琴", "探戈手风琴",
    "尼龙吉他", "钢弦吉他", "爵士电吉他", "闷音电吉他", "过载吉他", "失真吉他", "吉他泛音", "原声贝斯",
    "指弹贝斯", "拨片贝斯", "无品贝斯", "掌击贝斯1", "掌击贝斯2", "合成贝斯1", "合成贝斯2", "小提琴",
    "中提琴", "大提琴", "低音提琴", "颤弓弦乐", "拨弦弦乐", "竖琴", "定音鼓", "弦乐合奏1",
    "弦乐合奏2", "合成弦乐1", "合成弦乐2", "人声合唱", "人声Oohs", "合成人声", "管弦乐击奏", "小号",
    "长号", "大号", "弱音小号", "圆号", "铜管组", "合成铜管1", "合成铜管2", "高音萨克斯",
    "中音萨克斯", "次中音萨克斯", "上低音萨克斯", "双簧管", "英国管", "巴松管", "单簧管", "短笛",
    "长笛", "竖笛", "排箫", "吹瓶声", "尺八", "哨笛", "陶笛", "方波主音",
    "锯齿波主音", "汽笛风琴主音", "合成主音", "合成人声主音", "五度主音", "低音+主音", "新时代铺底", "暖音铺底",
    "复音铺底", "唱诗铺底", "弓弦铺底", "金属铺底", "光环铺底", "扫弦铺底", "冰雨音效", "音轨音效",
    "水晶音效", "大气音效", "明亮音效", "鬼魅音效", "回声音效", "科幻音效", "西塔琴", "班卓琴",
    "三味线", "古筝", "卡林巴", "风笛", "提琴(民族)", "唢呐", "叮当铃", "阿哥哥",
    "钢鼓", "木鱼", "太鼓", "定音筒鼓", "合成鼓", "反钹", "吉他滑弦", "呼吸声",
    "海浪声", "鸟鸣", "电话铃", "直升机", "掌声", "枪声"
)

fun gmName(program: Int): String =
    GM_INSTRUMENTS.getOrNull(program) ?: "音色#$program"

/** 生成解析报告文本，内容对应电脑版的 build_report_text */
fun buildReport(
    midi: MidiFileData,
    tagged: List<TaggedNote>,
    transpose: Int,
    modeDesc: String,
    glissRuns: Int,
    glissNotes: Int,
    params: ArrangeParams = ArrangeParams(),
    enableFold: Boolean = true
): String = buildString {
    val total = tagged.size
    appendLine("========== 解析报告 ==========")
    appendLine("物理音轨总数     : ${midi.trackCount} 条")
    appendLine("含音符音轨       : ${midi.noteBearingTracks.size} 条")
    appendLine("旋律识别         : $modeDesc")
    appendLine()
    appendLine("乐器（按通道，共 ${midi.channels.size} 个）:")
    for (ch in midi.channels) {
        val names = ch.programs.sorted().joinToString("、") { gmName(it) }
        appendLine("  通道 ${ch.channel}: ${names.ifEmpty { "默认钢琴" }}（${ch.noteCount} 音）")
    }
    if (midi.drumNotes > 0) {
        appendLine("  通道 $DRUM_CHANNEL: 鼓组/打击乐（${midi.drumNotes} 音，已排除）")
    }
    appendLine()
    val melTotal = tagged.count { it.isMelody }
    appendLine("音符总数         : $total（主旋律 $melTotal / 伴奏 ${total - melTotal}）")
    val (groups, chords) = countChords(tagged, transpose, enableFold, params)
    appendLine(
        "和弦情况         : $chords 个和弦 / 共 $groups 个发音组" +
                if (chords > 0) "  → 含和弦" else "  → 纯单音"
    )
    appendLine(
        "扫音检测         : $glissRuns 段，共 $glissNotes 个音" +
                if (glissNotes > 0) "  → 已屏蔽" else "  → 未发现"
    )
    appendLine()
    appendLine("----- 转调分析 -----")
    if (transpose == 0) {
        appendLine("转调量           : 0（原调）")
    } else {
        appendLine("转调量           : %+d 个半音".format(transpose))
    }
    val (mel0, acc0) = countPlayable(tagged, 0, false)
    val (melT, accT) = countPlayable(tagged, transpose, enableFold)
    appendLine("  原调无折叠   可弹 ${mel0 + acc0} / $total")
    appendLine("  当前设置     可弹 ${melT + accT} / $total（${if (enableFold) "折叠" else "不折叠"}）")
    appendLine("  不折叠推荐   ${com.example.lyretest.arrange.findBestTranspose(tagged, false)} 半音")
    appendLine("  折叠推荐     ${com.example.lyretest.arrange.findBestTranspose(tagged, true)} 半音")
    append("==============================")
}
