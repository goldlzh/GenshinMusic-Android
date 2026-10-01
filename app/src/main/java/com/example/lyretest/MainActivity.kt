package com.example.lyretest

import android.content.Intent
import android.net.Uri
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.lyretest.arrange.ArrangeCache
import com.example.lyretest.arrange.ArrangeResult
import com.example.lyretest.arrange.arrangeDetailed
import com.example.lyretest.core.Display
import com.example.lyretest.midi.MidiFileData
import com.example.lyretest.midi.MidiParser
import com.example.lyretest.midi.displayName
import com.example.lyretest.report.buildReport
import com.example.lyretest.songs.SongRepository
import com.example.lyretest.storage.KeyLayoutStore
import com.example.lyretest.storage.rotationName
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var songList: ListView
    private lateinit var searchInput: EditText
    private lateinit var reportText: TextView
    private lateinit var speedLabel: TextView
    private lateinit var playButton: Button
    private lateinit var arrangeButton: Button
    private lateinit var liveAutoConnectCheck: CheckBox
    private lateinit var liveMergeWindowInput: EditText
    private lateinit var liveMergeCheck: CheckBox
    private lateinit var liveTransposeCheck: CheckBox
    private lateinit var liveTransposeInput: EditText
    private lateinit var liveFoldCheck: CheckBox
    private lateinit var liveHoldCheck: CheckBox
    private lateinit var livePedalCheck: CheckBox
    private lateinit var automaticDispatchModeSpinner: Spinner
    private lateinit var controlBarResizeCheck: CheckBox

    private lateinit var repo: SongRepository
    private lateinit var settingsStore: UserSettingsStore
    private val uiHandler = Handler(Looper.getMainLooper())
    private var serviceWaitGeneration = 0
    private var statusPollGeneration = 0
    private var allSongs: List<File> = emptyList()
    private var songs: List<File> = emptyList()
    private var selected: File? = null
    private var parsedMidi: MidiFileData? = null
    private var arranged: ArrangeResult? = null
    private var selectedChannels: Set<Int>? = null
    private var forcedTrack: Int? = null
    private var manualTranspose: Int? = null
    private var melodyOnly = false
    private var blockGliss = true
    private var enableFold = true
    private var durationMode = "score"
    private var sustainPedal = false
    private var startRatio = 0.0
    private var startLabelId: String? = null
    private lateinit var songState: SongStateStore
    private lateinit var speedBar: SeekBar
    private lateinit var startBar: SeekBar
    private lateinit var startText: TextView
    private lateinit var memoryText: TextView
    private var sortMode = SongSort.NAME
    private var playlist: List<File> = emptyList()
    private var playingFile: File? = null
    private var parseGeneration = 0
    private var playbackGeneration = 0
    private var pendingPlayGeneration = 0
    private var playMode = PlayMode.SEQUENTIAL
    private var pendingAutoPlayPath: String? = null
    private var liveMidiSettings = LiveMidiSettings()
    private var selectedAutomaticDispatchMode = AutomaticDispatchMode.LOW_JITTER
    private var automaticDispatchModeSelectionPending = false

    private enum class PlayMode(val label: String) {
        SEQUENTIAL("顺序播放"),
        SINGLE("单曲循环"),
        RANDOM("随机播放")
    }

    /** 倍速，1.0 = 原速 */
    private var speed = 1.0

    private val pickMidi = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            }
            data.data?.let(::add)
        }.distinct()
        if (uris.isNotEmpty()) importSongs(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeLog.init(this)
        RuntimeLog.info("主界面启动")
        repo = SongRepository(this)
        val installedBundledSongs = runCatching { repo.installBundledSongsIfNeeded() }
            .onFailure { RuntimeLog.error("载入内置 MIDI 失败", it) }
            .getOrDefault(0)
        settingsStore = UserSettingsStore(this)
        songState = SongStateStore(this)
        sortMode = songState.loadSort()
        liveMidiSettings = settingsStore.loadLiveMidi()
        selectedAutomaticDispatchMode = AutomaticDispatchModeConfig.load(this)
            ?: AutomaticDispatchMode.LOW_JITTER
        speed = settingsStore.loadSpeed()
        playMode = runCatching {
            PlayMode.valueOf(settingsStore.loadPlayMode())
        }.getOrDefault(PlayMode.SEQUENTIAL)
        setContentView(buildUi())
        refreshSongs()
        if (installedBundledSongs > 0) {
            Toast.makeText(
                this,
                "内置曲库已载入 $installedBundledSongs 首 MIDI",
                Toast.LENGTH_LONG
            ).show()
        }
        consumeIncomingMidi(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingMidi(intent)
    }

    override fun onResume() {
        super.onResume()
        TouchService.instance?.onHostActivityVisible()
        pollServiceStatus()
    }

    // ---------- 界面 ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val performance = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val live = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tutorial = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addCollapsibleSection(root, "使用前设置", setup)
        addCollapsibleSection(root, "演奏选项", performance)
        addCollapsibleSection(root, "曲库", library, initiallyOpen = true)
        addCollapsibleSection(root, "实时电子琴", live)
        addCollapsibleSection(root, "教程", tutorial)

        setup.addView(TextView(this).apply {
            text = "goldlzh制作 UID：179236334"
            textSize = 12f
            gravity = android.view.Gravity.END
            setTextColor(0xFF666666.toInt())
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        statusText = TextView(this).apply { textSize = 13f }
        setup.addView(statusText)

        setup.addView(row(
            "无障碍设置" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "后台保活" to { requestBatteryProtection() },
            "键位校准" to { startCalibration() }
        ))
        setup.addView(row(
            "导出运行日志" to { exportRuntimeLog() }
        ))

        liveAutoConnectCheck = CheckBox(this).apply {
            text = "即插即弹（在原神内连接电子琴后自动开始）"
            isChecked = liveMidiSettings.autoConnectEnabled
            setOnCheckedChangeListener { _, checked ->
                saveLiveMidiUiSettings()
                TouchService.instance?.refreshLiveMidiAutoConnect(checked)
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "即插即弹已开启：进入原神后可直接连接电子琴"
                    else "即插即弹已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        live.addView(liveAutoConnectCheck)
        liveMergeCheck = CheckBox(this).apply {
            text = "合并同时按下的音符"
            isChecked = liveMidiSettings.mergeSimultaneousNotes
            setOnCheckedChangeListener { _, _ -> saveLiveMidiUiSettings() }
        }
        live.addView(liveMergeCheck)
        live.addView(TextView(this).apply { text = "合并区间（0～200 毫秒；0 表示关闭）"; textSize = 13f })
        liveMergeWindowInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(liveMidiSettings.mergeWindowMs.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveLiveMidiUiSettings() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        live.addView(liveMergeWindowInput)
        live.addView(TextView(this).apply {
            text = "只合并按下，松开仍独立。区间越大，和弦越整齐，但起音等待越久；参数在下次连接时生效。"
            textSize = 12f
        })

        liveTransposeCheck = CheckBox(this).apply {
            text = "启用半音移调"
            isChecked = liveMidiSettings.transposeEnabled
        }
        liveTransposeInput = EditText(this).apply {
            hint = "移调半音数（-48 到 48）"
            setText(liveMidiSettings.transpose.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            isSingleLine = true
            isEnabled = liveMidiSettings.transposeEnabled
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveLiveMidiUiSettings()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        liveTransposeCheck.setOnCheckedChangeListener { _, checked ->
            liveTransposeInput.isEnabled = checked
            saveLiveMidiUiSettings()
        }
        live.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(liveTransposeCheck, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(liveTransposeInput, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        })

        liveFoldCheck = CheckBox(this).apply {
            text = "启用八度折叠（超出三八度时折回可弹音域）"
            isChecked = liveMidiSettings.octaveFoldEnabled
            setOnCheckedChangeListener { _, _ -> saveLiveMidiUiSettings() }
        }
        live.addView(liveFoldCheck)
        liveHoldCheck = CheckBox(this).apply {
            text = "跟随电子琴松键（关闭时固定短按）"; isChecked = liveMidiSettings.followRelease
            setOnCheckedChangeListener { _, _ -> saveLiveMidiUiSettings() }
        }
        livePedalCheck = CheckBox(this).apply {
            text = "跟随电子琴延音踏板"; isChecked = liveMidiSettings.sustainPedal
            setOnCheckedChangeListener { _, _ -> saveLiveMidiUiSettings() }
        }
        live.addView(liveHoldCheck); live.addView(livePedalCheck)
        live.addView(row(
            "连接并开始" to { showLiveMidiDialog() },
            "停止实时演奏" to { cancelPlayback() }
        ))

        library.addView(row(
            "从下载目录导入" to { openMidiPicker() },
            "删除所选" to { deleteSelected() }
        ))

        library.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                SongSort.entries.map { it.label })
            setSelection(SongSort.entries.indexOf(sortMode))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (sortMode == SongSort.entries[pos]) return
                    sortMode = SongSort.entries[pos]
                    songState.saveSort(sortMode)
                    refreshSongs(restore = false)
                    playlist = allSongs.toList()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        searchInput = EditText(this).apply {
            hint = "搜索曲名"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterSongs(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        library.addView(searchInput)

        songList = SongListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                selectSong(songs[pos])
            }
        }
        library.addView(songList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
        ))
        library.addView(row(
            "上一首" to { selectAdjacent(-1) },
            "下一首" to { selectAdjacent(1) }
        ))

        speedLabel = TextView(this).apply {
            textSize = 13f
            text = "播放速度：%.2fx".format(speed)
        }
        performance.addView(speedLabel)
        speedBar = SeekBar(this).apply {
            max = 150          // 0.5x ~ 2.0x，步长 0.01
            progress = ((speed - 0.5) * 100).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    speed = 0.5 + value * 0.01
                    speedLabel.text = "播放速度：%.2fx".format(speed)
                    if (fromUser) settingsStore.saveSpeed(speed)
                    updateStartUi()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        performance.addView(speedBar)
        memoryText = TextView(this).apply { textSize = 12f }
        performance.addView(memoryText)
        startText = TextView(this).apply { textSize = 13f }
        performance.addView(startText)
        startBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { startRatio = value / 1000.0; startLabelId = null; updateStartUi() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        performance.addView(startBar)
        performance.addView(row("插入标签" to { insertSelectedLabel() }, "标签 / 起点" to { showLabels() },
            "从头开始" to { startRatio = 0.0; startLabelId = null; updateStartUi() }))
        performance.addView(row("保存手动记忆" to {
            selected?.let { songState.saveManual(it.path, currentSettings()); updateMemoryUi(); toast("已保存手动记忆") }
        }, "恢复记忆" to { selected?.let { selectSong(it) } }, "清除手动记忆" to {
            selected?.let { songState.clearManual(it.path); selectSong(it); toast("已清除手动记忆，恢复自动记忆") }
        }))

        arrangeButton = Button(this).apply {
            text = "高级编曲参数"
            isEnabled = false
            setOnClickListener { showArrangeDialog() }
        }
        performance.addView(arrangeButton)

        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                PlayMode.entries.map { it.label }
            )
            setSelection(PlayMode.entries.indexOf(playMode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    playMode = PlayMode.entries[pos]
                    settingsStore.savePlayMode(playMode.name)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        performance.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "播放模式：" })
            addView(modeSpinner, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        })

        automaticDispatchModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AutomaticDispatchMode.entries.map { it.displayLabel }
            )
            setSelection(
                AutomaticDispatchMode.entries.indexOf(selectedAutomaticDispatchMode)
                    .coerceAtLeast(0)
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {
                    val selected = AutomaticDispatchMode.entries[pos]
                    // Spinner 首次显示也会回调；只有用户真正换了模式才发起手动验证。
                    if (selected == selectedAutomaticDispatchMode) return
                    selectedAutomaticDispatchMode = selected
                    val service = TouchService.instance
                    if (service == null) {
                        automaticDispatchModeSelectionPending = true
                    } else {
                        service.selectAutomaticDispatchMode(selected)
                        automaticDispatchModeSelectionPending = false
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "已选择${selected.displayLabel}，连续成功5次后才会记住",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        setup.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "自动演奏触摸模式：" })
            addView(automaticDispatchModeSpinner, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        })
        setup.addView(TextView(this).apply {
            text = "软件会自动测试并记住稳定模式，通常不建议手动修改模式选择。"
            textSize = 12f
            setTextColor(0xFFB00020.toInt())
        })
        setup.addView(row(
            "清除模式配置" to { clearAutomaticDispatchModeConfig() }
        ))

        controlBarResizeCheck = CheckBox(this).apply {
            text = "调整悬浮窗大小（演奏时拖拽悬浮条右边框）"
            isChecked = settingsStore.loadControlBar().resizeEnabled
            setOnCheckedChangeListener { _, checked ->
                val current = settingsStore.loadControlBar()
                settingsStore.saveControlBar(current.copy(resizeEnabled = checked))
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "已开启：演奏时拖拽悬浮条右边框调整宽度"
                    else "已关闭悬浮条大小调整",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        setup.addView(controlBarResizeCheck)

        playButton = Button(this).apply {
            text = "开始演奏"
            isEnabled = false
            setOnClickListener { startPlaying() }
        }
        performance.addView(playButton)
        performance.addView(row("停止演奏" to { cancelPlayback() }))

        reportText = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "选择一首曲子后自动解析"
        }
        performance.addView(ScrollView(this).apply {
            addView(reportText)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        tutorial.addView(TextView(this).apply {
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            text = buildString {
                appendLine("一、首次使用")
                appendLine("1. 开启“无障碍设置”中的 LyreTest 服务。")
                appendLine("2. 点击“后台保活”，允许 LyreTest 忽略电池优化；三星手机还应在应用电池设置中选择“不受限制”。")
                appendLine("3. 打开原神乐器界面，返回本软件点击“键位校准”。")
                appendLine("4. 根据手机速度选择5秒或10秒，在倒计时内切回游戏，按提示依次点击 21 个琴键。")
            }
        })
        tutorial.addView(ImageView(this).apply {
            setImageResource(R.drawable.calibration_order_guide)
            contentDescription = "键位校准顺序：从左到右，从上到下"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        tutorial.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB00020.toInt())
            setLineSpacing(0f, 1.2f)
            text = "• 从左到右，从上到下的点击\n" +
                    "• 不适配晚风圆号，悠可琴类的乐器"
            setPadding(0, 0, 0, dp(12))
        })
        tutorial.addView(TextView(this).apply {
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            text = buildString {
                appendLine("二、实时电子琴")
                appendLine("1. 用 USB OTG 连接支持 USB MIDI 的电子琴；只有 MIDI 圆口时需使用 USB-MIDI 转换器。")
                appendLine("2. 即插即弹：开启开关后直接进入原神乐器界面，再插入电子琴，连接成功即可弹奏；拔线或离开原神会自动停止。")
                appendLine("3. 手动连接：设置各项参数，点击“连接并开始”，选择设备和5秒/10秒准备时间后切回原神。")
                appendLine("• 合并同时音符：可设置 0～200ms，默认 30ms。从第一个按键起计时，将窗口内的起音同时触发；松键仍独立，已有长音时也能合并新和弦。0 或取消勾选为关闭。")
                appendLine("• 半音移调：开启后，把所有输入音符统一升高或降低指定半音；12 表示升一个八度，-12 表示降一个八度。")
                appendLine("• 八度折叠：开启后，过高或过低的自然音会按八度折回原神三八度音域；关闭后直接忽略超范围音符。")
                appendLine("• 原神 21 键只有自然音，无法对应的黑键会被忽略。")
                appendLine()
                appendLine("三、MIDI 文件播放")
                appendLine("1. 点击“从下载目录导入”，默认会打开手机的下载文件夹；长按或使用“多选”可一次导入多首。")
                appendLine("2. 也可在下载通知或文件管理器中打开 MIDI 文件，然后在“打开方式/其他应用”里选择 LyreTest。")
                appendLine("3. 可调整速度、播放模式和高级编曲参数。")
                appendLine("4. 触摸模式会自动按低抖动、兼容、主线程顺序测试，并在原神前台连续成功后记住；通常不建议手动修改，异常时可清除模式配置后重新测试。")
                appendLine("5. 可开启“调整悬浮窗大小”，演奏时拖拽悬浮条右边框并保存宽度；空间不足时优先保留进度条、暂停/继续和关闭。")
                appendLine("6. 点击“开始演奏”，根据手机速度选择5秒或10秒，再切回游戏。")
                appendLine("7. 自动弹琴悬浮条可拖动进度、暂停/继续、停止或切到下一首；“下一首”会按当前的单曲循环、随机或顺序播放设置选择曲目。")
                appendLine()
                appendLine("四、问题反馈")
                appendLine("遇到校准正常但无法演奏等问题时，请先复现一次，再点击“使用前设置”中的“导出运行日志”，通过微信、QQ、邮件等方式发送日志文件。")
                appendLine()
                append("注意：校准坐标与屏幕方向绑定。演奏中请勿旋转手机；左右两个横屏方向需要分别校准。")
            }
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addCollapsibleSection(root: LinearLayout, title: String, content: LinearLayout,
                                      initiallyOpen: Boolean = false) {
        val prefs = getSharedPreferences("home_sections", MODE_PRIVATE)
        var open = prefs.getBoolean(title, initiallyOpen)
        val header = Button(this).apply {
            textSize = 17f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            isAllCaps = false
            minHeight = dp(52)
        }
        fun render() {
            header.text = (if (open) "▾  " else "▸  ") + title
            header.contentDescription = title + if (open) "，已展开，点击折叠" else "，已折叠，点击展开"
            content.visibility = if (open) View.VISIBLE else View.GONE
        }
        header.setOnClickListener { open = !open; prefs.edit().putBoolean(title, open).apply(); render() }
        content.setPadding(dp(8), 0, dp(8), dp(12))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 24, 0, 8)
    }

    /** 一行并排按钮 */
    private fun row(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((label, action) in buttons) {
            addView(Button(context).apply {
                text = label
                textSize = 12f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 读取并保存实时电子琴栏目；输入未完成时暂不覆盖上一次有效值。 */
    private fun saveLiveMidiUiSettings(showError: Boolean = false): LiveMidiSettings? {
        if (!::liveTransposeInput.isInitialized) return null
        val transpose = liveTransposeInput.text.toString().toIntOrNull()
        if (transpose == null || transpose !in -48..48) {
            if (showError) liveTransposeInput.error = "请输入 -48 到 48 之间的整数"
            return null
        }
        val mergeMs = if (::liveMergeWindowInput.isInitialized) liveMergeWindowInput.text.toString().toIntOrNull()
            else liveMidiSettings.mergeWindowMs
        if (mergeMs == null || mergeMs !in 0..200) {
            if (showError) liveMergeWindowInput.error = "请输入 0 到 200 之间的整数（毫秒）"
            return null
        }
        liveMidiSettings = LiveMidiSettings(
            mergeWindowMs = mergeMs,
            autoConnectEnabled = liveAutoConnectCheck.isChecked,
            mergeSimultaneousNotes = liveMergeCheck.isChecked,
            transposeEnabled = liveTransposeCheck.isChecked,
            transpose = transpose,
            octaveFoldEnabled = liveFoldCheck.isChecked,
            followRelease = if (::liveHoldCheck.isInitialized) liveHoldCheck.isChecked else liveMidiSettings.followRelease,
            sustainPedal = if (::livePedalCheck.isInitialized) livePedalCheck.isChecked else liveMidiSettings.sustainPedal
        )
        settingsStore.saveLiveMidi(liveMidiSettings)
        return liveMidiSettings
    }

    // ---------- 曲库 ----------

    /** 打开系统文件选择器并尽量直接定位到“下载”目录，保留多选能力。 */
    private fun openMidiPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, SongRepository.PICK_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.providers.downloads.documents/root/downloads")
            )
        }
        pickMidi.launch(picker)
    }

    /** 接收文件管理器、下载通知或“分享到”LyreTest 的一首/多首 MIDI。 */
    @Suppress("DEPRECATION")
    private fun consumeIncomingMidi(incoming: Intent?) {
        if (incoming == null) return
        val uris = when (incoming.action) {
            Intent.ACTION_VIEW -> listOfNotNull(incoming.data)
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }.distinct()
        if (uris.isEmpty()) return

        // 消费后清空，避免屏幕旋转或 Activity 重建时重复导入同一个文件。
        incoming.action = null
        incoming.data = null
        incoming.removeExtra(Intent.EXTRA_STREAM)
        importSongs(uris)
    }

    private fun refreshSongs(restore: Boolean = true) {
        val files = repo.list()
        files.forEach { songState.ensureAdded(it) }
        allSongs = songState.sorted(files, sortMode)
        filterSongs(if (::searchInput.isInitialized) searchInput.text.toString() else "")
        if (allSongs.isEmpty()) {
            reportText.text = "曲库是空的，点「从下载目录导入」选择一首或多首 MIDI。\n" +
                    "推荐找标注「原琴」的 MIDI，弹出来更好听。"
        }
        if (restore) restoreSelection()
    }

    private fun filterSongs(query: String) {
        val selectedPath = selected?.path
        val keyword = query.trim()
        songs = if (keyword.isEmpty()) allSongs
        else allSongs.filter { it.nameWithoutExtension.contains(keyword, ignoreCase = true) }
        if (!::songList.isInitialized) return
        songList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            songs.map { it.nameWithoutExtension }
        )
        val shownIndex = songs.indexOfFirst { it.path == selectedPath }
        if (shownIndex >= 0) songList.setItemChecked(shownIndex, true)
    }

    private fun restoreSelection() {
        // Activity 可能是演奏后被回收再重建的，用缓存恢复上次选中的曲子。
        val restore = selected?.path ?: ArrangeCache.currentPath()
        if (restore != null) {
            val file = allSongs.firstOrNull { it.path == restore }
            if (file != null) {
                val idx = songs.indexOfFirst { it.path == restore }
                if (idx >= 0) songList.setItemChecked(idx, true)
                selectSong(file)
            } else {
                clearSelection()
            }
        }
    }

    private fun selectAdjacent(delta: Int) {
        if (allSongs.isEmpty()) return
        val active = TouchService.instance?.player != null
        val queue = if (active) playlist.filter { it.exists() }.ifEmpty { allSongs } else allSongs
        val file = nextSong(queue, if (active) playingFile else selected, playMode.name, delta) ?: return
        if (searchInput.text.isNotEmpty() &&
            songs.none { it.path == file.path }) {
            searchInput.setText("")
        }
        val shown = songs.indexOfFirst { it.path == file.path }
        if (shown >= 0) songList.setItemChecked(shown, true)
        withTouchService {
            cancelPlayback()
            playlist = queue.toList()
            pendingPlayGeneration = ++playbackGeneration
            pendingAutoPlayPath = file.path
            selectSong(file)
        }
    }

    private fun clearSelection() {
        selected = null
        parseGeneration++
        parsedMidi = null
        arranged = null
        updateStartUi()
        updateMemoryUi()
        arrangeButton.isEnabled = false
        playButton.isEnabled = false
    }

    private fun importSongs(uris: List<Uri>) {
        Toast.makeText(this, "正在导入 ${uris.size} 首 MIDI…", Toast.LENGTH_SHORT).show()
        thread(name = "MidiBatchImport") {
            val imported = ArrayList<File>(uris.size)
            val failures = ArrayList<String>()
            for (uri in uris) {
                runCatching { repo.import(uri) }
                    .onSuccess(imported::add)
                    .onFailure { failures.add(it.message ?: "无法读取文件") }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (imported.isNotEmpty()) {
                    imported.forEach { songState.ensureAdded(it, System.currentTimeMillis()) }
                    // 只解析最后导入的一首；其余曲目已经进入曲库，点选时再解析。
                    // 避免一次导入大量文件时在主线程连续启动无用的解析任务。
                    selected = null
                    ArrangeCache.clear()
                    clearSelection()
                    searchInput.setText("")
                    refreshSongs()
                    val file = imported.last()
                    val idx = songs.indexOfFirst { it.path == file.path }
                    if (idx >= 0) {
                        songList.setItemChecked(idx, true)
                        selectSong(file)
                    }
                }
                val message = when {
                    failures.isEmpty() -> "已导入 ${imported.size} 首 MIDI"
                    imported.isEmpty() -> "导入失败：${failures.first()}"
                    else -> "已导入 ${imported.size} 首，${failures.size} 首失败：${failures.first()}"
                }
                Toast.makeText(
                    this,
                    message,
                    if (failures.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteSelected() {
        val file = selected ?: run {
            Toast.makeText(this, "请先选一首曲子", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("删除 ${file.nameWithoutExtension}？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                repo.delete(file)
                if (playingFile?.path == file.path) cancelPlayback()
                songState.delete(file.path)
                ArrangeCache.clear()
                clearSelection()
                refreshSongs()
            }
            .show()
    }

    // ---------- 解析 ----------

    /** 选中曲目后在后台线程解析，解析完才允许演奏 */
    private fun selectSong(file: File) {
        val generation = ++parseGeneration
        selected = file
        parsedMidi = null
        arranged = null
        applySettings(songState.load(file.path).memory() ?: SongArrangeSettings())
        val settings = currentSettings()
        updateMemoryUi()
        arrangeButton.isEnabled = false
        playButton.isEnabled = false
        reportText.text = "正在解析 ${file.nameWithoutExtension} ..."

        thread(name = "MidiParse") {
            val parsed = runCatching { MidiParser.parse(file.readBytes()) }
            val result = parsed.mapCatching { midi ->
                midi to computeArrangement(midi, settings)
            }
            runOnUiThread {
                if (selected?.path != file.path || parseGeneration != generation) return@runOnUiThread
                parsedMidi = parsed.getOrNull()
                arrangeButton.isEnabled = parsedMidi != null
                parsedMidi?.let { midi ->
                    selectedChannels = settings.channels ?: com.example.lyretest.arrange.autoSelectChannels(midi.channels, 3)
                }
                result.fold(
                    onSuccess = { (midi, computed) ->
                        parsedMidi = midi
                        if (forcedTrack !in midi.noteBearingTracks) forcedTrack = null
                        selectedChannels = computed.first.keptChannels
                        applyComputed(file, computed)
                    },
                    onFailure = { e ->
                        if (pendingAutoPlayPath == file.path) cancelPlayback()
                        reportText.text = "解析失败：${e.message}"
                        Toast.makeText(this, "解析失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    /** 每次后台编曲使用参数快照，切歌不会读到另一首歌的界面参数。 */
    private fun computeArrangement(midi: MidiFileData, options: SongArrangeSettings): Pair<ArrangeResult, String> {
        val selectedChannels = options.channels?.intersect(midi.channels.map { it.channel }.toSet())?.takeIf { it.isNotEmpty() }
        val forcedTrack = options.forcedTrack?.takeIf { it in midi.noteBearingTracks }
        val manualTranspose = options.transpose
        val enableFold = options.enableFold
        val blockGliss = options.blockGliss
        val melodyOnly = options.melodyOnly
        val logs = StringBuilder()
        val r = arrangeDetailed(
            midi = midi,
            channels = selectedChannels,
            forcedTrack = forcedTrack,
            transpose = manualTranspose,
            enableFold = enableFold,
            blockGliss = blockGliss,
            melodyOnly = melodyOnly,
            durationMode = options.durationMode,
            sustainPedal = options.sustainPedal
        ) { line -> logs.appendLine(line) }
            ?: error("当前参数下没有可弹奏的音符")
        val report = buildReport(
            midi, r.tagged, r.transpose, r.melodyDescription,
            r.glissRuns, r.glissNotes, enableFold = enableFold
        )
        val full = buildString {
            appendLine(report)
            appendLine()
            appendLine("保留通道：${r.keptChannels.sorted().joinToString()}")
            appendLine(
                "参数：${if (melodyOnly) "只弹主旋律，" else ""}" +
                        "${if (blockGliss) "屏蔽扫音，" else "保留扫音，"}" +
                        "${if (enableFold) "八度折叠" else "不折叠"}"
            )
            appendLine("音长模式：${DURATION_LABELS[options.durationMode]}，延音踏板：${if (options.sustainPedal) "跟随" else "忽略"}")
            appendLine("原速时长 ${formatTime(r.duration)}，共 ${r.chords.size} 个发音事件")
            appendLine()
            append(logs.toString().trim())
        }
        return r to full
    }

    private fun applyComputed(file: File, computed: Pair<ArrangeResult, String>) {
        val (r, full) = computed
        arranged = r
        arrangeButton.isEnabled = true
        playButton.isEnabled = true
        reportText.text = full
        ArrangeCache.put(file.path, r, full)
        updateStartUi()
        if (pendingAutoPlayPath == file.path && pendingPlayGeneration == playbackGeneration) {
            pendingAutoPlayPath = null
            launchPlayback(r.chords, file.nameWithoutExtension, startDelayMs = 3_000L)
        }
    }

    /**
     * 电脑版“演奏参数”的 Android 版本。应用后在后台重新编曲，避免阻塞主线程。
     */
    private fun showArrangeDialog() {
        val midi = parsedMidi ?: return
        val file = selected ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 8)
        }

        content.addView(sectionLabel("乐器通道（至少选择一个）"))
        val channelChecks = midi.channels.map { info ->
            CheckBox(this).apply {
                text = "通道 ${info.channel}（${info.noteCount} 音）"
                isChecked = info.channel in (selectedChannels ?: emptySet())
                content.addView(this)
            } to info.channel
        }

        content.addView(sectionLabel("主旋律音轨"))
        val tracks = listOf<Int?>(null) + midi.noteBearingTracks
        val trackLabels = tracks.map { track ->
            if (track == null) "自动识别"
            else "音轨 $track（${midi.notes.count { it.track == track }} 音）"
        }
        val trackSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                trackLabels
            )
            setSelection(tracks.indexOf(forcedTrack).coerceAtLeast(0))
        }
        content.addView(trackSpinner)

        val melodyCheck = CheckBox(this).apply {
            text = "只弹主旋律"
            isChecked = melodyOnly
        }
        val glissCheck = CheckBox(this).apply {
            text = "屏蔽扫音"
            isChecked = blockGliss
        }
        val foldCheck = CheckBox(this).apply {
            text = "八度折叠"
            isChecked = enableFold
        }
        content.addView(melodyCheck)
        content.addView(glissCheck)
        content.addView(foldCheck)
        content.addView(sectionLabel("演奏音长模式"))
        val durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, DURATION_LABELS.values.toList())
            setSelection(DURATION_LABELS.keys.indexOf(durationMode).coerceAtLeast(0))
        }
        content.addView(durationSpinner)
        content.addView(TextView(this).apply {
            text = "固定短按：全部 40ms\n原谱音长：全部跟随谱面\n旋律长音：仅主旋律跟随谱面\n和弦长音：仅同时出现的和弦伴奏长按，主旋律和单音伴奏 40ms"
            textSize = 12f
        })
        val pedalCheck = CheckBox(this).apply { text = "跟随 MIDI 延音踏板（仅影响长音）"; isChecked = sustainPedal }
        content.addView(pedalCheck)

        content.addView(sectionLabel("转调（半音）"))
        val autoTranspose = CheckBox(this).apply {
            text = "使用推荐转调"
            isChecked = manualTranspose == null
        }
        val transposeInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText((manualTranspose ?: arranged?.transpose ?: 0).toString())
            isEnabled = !autoTranspose.isChecked
        }
        autoTranspose.setOnCheckedChangeListener { _, checked ->
            transposeInput.isEnabled = !checked
        }
        content.addView(autoTranspose)
        content.addView(transposeInput)

        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("高级编曲参数")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val channels = channelChecks.filter { it.first.isChecked }.map { it.second }.toSet()
                if (channels.isEmpty()) {
                    Toast.makeText(this, "请至少选择一个通道", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val transpose = if (autoTranspose.isChecked) null
                else transposeInput.text.toString().toIntOrNull()
                if (!autoTranspose.isChecked && (transpose == null || transpose !in -48..48)) {
                    transposeInput.error = "请输入 -48 到 48 之间的整数"
                    return@setOnClickListener
                }

                selectedChannels = channels
                forcedTrack = tracks[trackSpinner.selectedItemPosition]
                manualTranspose = transpose
                melodyOnly = melodyCheck.isChecked
                blockGliss = glissCheck.isChecked
                enableFold = foldCheck.isChecked
                durationMode = DURATION_LABELS.keys.elementAt(durationSpinner.selectedItemPosition)
                sustainPedal = pedalCheck.isChecked
                val settings = currentSettings()
                val generation = ++parseGeneration
                dialog.dismiss()

                arranged = null
                arrangeButton.isEnabled = false
                playButton.isEnabled = false
                reportText.text = "正在按新参数重新编曲..."
                thread(name = "MidiRearrange") {
                    val result = runCatching { computeArrangement(midi, settings) }
                    runOnUiThread {
                        if (selected?.path != file.path || parseGeneration != generation) return@runOnUiThread
                        result.fold(
                            onSuccess = { applyComputed(file, it) },
                            onFailure = { e ->
                                arrangeButton.isEnabled = true
                                reportText.text = "重新编曲失败：${e.message}"
                                Toast.makeText(
                                    this,
                                    "重新编曲失败：${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun currentSettings() = SongArrangeSettings(selectedChannels, forcedTrack, manualTranspose,
        melodyOnly, blockGliss, enableFold, durationMode, sustainPedal, speed.coerceIn(.5, 2.0), startRatio, startLabelId)
    private fun applySettings(s: SongArrangeSettings) {
        selectedChannels = s.channels; forcedTrack = s.forcedTrack; manualTranspose = s.transpose
        melodyOnly = s.melodyOnly; blockGliss = s.blockGliss; enableFold = s.enableFold
        durationMode = s.durationMode; sustainPedal = s.sustainPedal; speed = s.speed.coerceIn(.5, 2.0)
        startLabelId = s.startLabelId
        startRatio = selected?.let { songState.load(it.path).startRatio(s) } ?: s.startRatio
        if (::speedBar.isInitialized) speedBar.progress = kotlin.math.round((speed - .5) * 100).toInt()
        updateStartUi()
    }
    private fun updateMemoryUi() {
        if (!::memoryText.isInitialized) return
        val state = selected?.let { songState.load(it.path) }
        val memory = when {
            state?.manual != null -> "本曲：手动记忆优先（参数修改后可重新保存）"
            state?.automatic != null -> "本曲：恢复上次正式开始演奏时的自动记忆"
            else -> "本曲：默认参数；正式开始演奏后自动记忆"
        }
        fun stamp(value: Long) = if (value <= 0) "尚无记录" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(value))
        memoryText.text = memory + (state?.let { "\n添加：${stamp(it.addedAt)}  最近播放：${stamp(it.lastPlayedAt)}" } ?: "")
    }
    private fun updateStartUi() {
        if (!::startText.isInitialized) return
        val total = arranged?.let { com.example.lyretest.arrange.performanceDuration(it.chords, speed) } ?: 0.0
        val label = selected?.let { songState.load(it.path).labels.find { l -> l.id == startLabelId } }
        if (label != null) startRatio = label.ratio
        startText.text = "起点：${formatTime(total * startRatio)} / ${formatTime(total)}" + (label?.let { " · ${it.name}" } ?: "")
        if (::startBar.isInitialized) startBar.progress = (startRatio * 1000).toInt()
    }
    private fun insertSelectedLabel() {
        val file = selected ?: return
        val player = TouchService.instance?.player
        val ratio = if (playingFile?.path == file.path && player != null && player.total > 0) player.playTime / player.total else startRatio
        val label = songState.insertLabel(file.path, ratio)
        toast("已插入${label.name}")
    }
    private fun showLabels() {
        val file = selected ?: return
        val state = songState.load(file.path)
        if (state.labels.isEmpty()) { toast("先用“插入标签”记录一个位置"); return }
        val total = arranged?.let { com.example.lyretest.arrange.performanceDuration(it.chords, speed) } ?: 0.0
        AlertDialog.Builder(this).setTitle("标签 / 起点")
            .setItems(state.labels.map { "${it.name} · ${formatTime(it.ratio * total)}" }.toTypedArray()) { _, index ->
                val label = state.labels[index]
                AlertDialog.Builder(this).setTitle(label.name)
                    .setItems(arrayOf("设为演奏起点", "跳转当前演奏", "重命名", "移动到当前进度 / 起点", "删除")) { _, action ->
                        when (action) {
                            0 -> { startLabelId = label.id; startRatio = label.ratio; updateStartUi() }
                            1 -> if (playingFile?.path == file.path) TouchService.instance?.player?.seekRatio(label.ratio.toFloat())
                                else toast("当前未在演奏这首歌")
                            2 -> {
                                val input = EditText(this).apply { setText(label.name); isSingleLine = true }
                                AlertDialog.Builder(this).setTitle("重命名标签").setView(input).setNegativeButton("取消", null)
                                    .setPositiveButton("保存") { _, _ ->
                                        val name = input.text.toString().trim().take(80)
                                        if (name.isNotEmpty()) {
                                            val current = songState.load(file.path)
                                            songState.save(file.path, current.copy(labels = current.labels.map { if (it.id == label.id) it.copy(name = name) else it }))
                                            updateStartUi()
                                        }
                                    }.show()
                            }
                            3 -> {
                                val p = TouchService.instance?.player
                                val ratio = if (playingFile?.path == file.path && p != null && p.total > 0) p.playTime / p.total else startRatio
                                val current = songState.load(file.path)
                                songState.save(file.path, current.copy(labels = current.labels.map { if (it.id == label.id) it.copy(ratio = ratio) else it }.sortedBy { it.ratio }))
                                updateStartUi()
                            }
                            4 -> {
                                val current = songState.load(file.path)
                                if (startLabelId == label.id) { startRatio = label.ratio; startLabelId = null }
                                songState.save(file.path, current.withoutLabel(label.id)); updateStartUi()
                            }
                        }
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun formatTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- 演奏 ----------

    private fun showLiveMidiDialog() {
        withTouchService { showLiveMidiDialog(it) }
    }

    private fun showLiveMidiDialog(service: TouchService) {
        val liveSettings = saveLiveMidiUiSettings(showError = true) ?: return
        val manager = getSystemService(MIDI_SERVICE) as? MidiManager
        val devices = manager?.devices?.filter { it.outputPortCount > 0 }.orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "没有发现电子琴。请用 USB OTG 连接，并把电子琴设为 USB MIDI 模式后重试。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val deviceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                devices.map(MidiDeviceInfo::displayName)
            )
        }
        val delayValues = listOf(5_000L, 10_000L)
        val delaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("5 秒（普通）", "10 秒（低配置手机）")
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
            addView(TextView(context).apply { text = "MIDI 设备" })
            addView(deviceSpinner)
            addView(TextView(context).apply {
                text = "准备时间"
                setPadding(0, 16, 0, 0)
            })
            addView(delaySpinner)
            addView(TextView(context).apply {
                text = buildString {
                    append("当前设置：")
                    append(if (liveSettings.mergeSimultaneousNotes && liveSettings.mergeWindowMs > 0) "合并和弦 ${liveSettings.mergeWindowMs}ms" else "不合并起音")
                    append("，")
                    append(
                        if (liveSettings.transposeEnabled) "移调 ${liveSettings.transpose} 半音"
                        else "不移调"
                    )
                    append("，")
                    append(if (liveSettings.octaveFoldEnabled) "八度折叠" else "不折叠")
                }
                textSize = 12f
                setPadding(0, 16, 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("实时电子琴")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val started = service.startLiveMidi(
                    deviceInfo = devices[deviceSpinner.selectedItemPosition],
                    transpose = if (liveSettings.transposeEnabled) liveSettings.transpose else 0,
                    fold = liveSettings.octaveFoldEnabled,
                    mergeSimultaneousNotes = liveSettings.mergeSimultaneousNotes,
                    mergeWindowMs = liveSettings.mergeWindowMs,
                    followRelease = liveSettings.followRelease, sustainPedal = liveSettings.sustainPedal,
                    startDelayMs = delayValues[delaySpinner.selectedItemPosition]
                )
                if (started) {
                    dialog.dismiss()
                    moveTaskToBack(true)
                }
            }
        }
        dialog.show()
    }

    private fun startPlaying() {
        pendingAutoPlayPath = null
        playbackGeneration++
        playlist = allSongs.toList()
        val chords = arranged?.chords ?: return
        withTouchService { startPlaying(it, chords) }
    }

    private fun startPlaying(
        service: TouchService,
        chords: List<com.example.lyretest.arrange.Chord>
    ) {
        // 本 App 是竖屏，游戏是横屏，所以这里不校验方向（校验在真正开弹前做）。
        // 但要提醒用户：演奏中别转手机。
        fun begin(delayMs: Long) {
            val started = launchPlayback(
                chords,
                selected?.nameWithoutExtension.orEmpty(),
                startDelayMs = delayMs
            )
            if (started) moveTaskToBack(true)
        }
        AlertDialog.Builder(this)
            .setTitle("准备演奏")
            .setMessage(
                "请选择准备时间。开始后请切回游戏并打开乐器。\n\n" +
                        "• 演奏期间请保持手机方向不变，中途翻转会导致键位错位，演奏会自动停止\n" +
                        "• 键位是按屏幕方向分别保存的：如果你习惯两个方向都玩，" +
                        "请在每个方向下各校准一套，切换方向后 App 会自动使用对应的那套\n" +
                        "• 演奏中用悬浮控制条暂停或停止"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("5秒开始") { _, _ -> begin(5_000L) }
            .setNeutralButton("10秒开始") { _, _ -> begin(10_000L) }
            .show()
    }

    private fun launchPlayback(
        chords: List<com.example.lyretest.arrange.Chord>,
        songName: String,
        startDelayMs: Long
    ): Boolean {
        val service = TouchService.instance ?: return false
        if (automaticDispatchModeSelectionPending) {
            service.selectAutomaticDispatchMode(selectedAutomaticDispatchMode)
            automaticDispatchModeSelectionPending = false
        }
        val file = selected ?: return false
        val snapshot = currentSettings()
        val generation = ++playbackGeneration
        playingFile = file
        return service.startPlaying(
            chords = chords,
            songName = songName,
            speed = snapshot.speed.coerceIn(.5, 2.0),
            startRatio = songState.load(file.path).startRatio(snapshot),
            startDelayMs = startDelayMs,
            onStarted = {
                if (generation == playbackGeneration && file.exists()) {
                    songState.played(file.path, snapshot)
                    refreshSongs(restore = false)
                    updateMemoryUi()
                }
            },
            onInsertLabel = { ratio ->
                if (file.exists()) {
                    val label = songState.insertLabel(file.path, ratio.toDouble())
                    toast("${file.nameWithoutExtension}：已插入${label.name}")
                }
            },
            onNext = { continueByPlayMode(manualDelta = 1) },
            onPrevious = { continueByPlayMode(manualDelta = -1) },
            onStopRequested = { playbackGeneration++; pendingAutoPlayPath = null },
            keepControlBarOnFinish = true,
            onFinish = { completed ->
                if (completed && generation == playbackGeneration) continueByPlayMode()
            }
        )
    }

    private fun clearAutomaticDispatchModeConfig() {
        AutomaticDispatchModeConfig.clear(this)
        TouchService.instance?.clearAutomaticDispatchModeConfig()
        selectedAutomaticDispatchMode = AutomaticDispatchMode.LOW_JITTER
        automaticDispatchModeSelectionPending = false
        if (::automaticDispatchModeSpinner.isInitialized) {
            automaticDispatchModeSpinner.setSelection(
                AutomaticDispatchMode.entries.indexOf(AutomaticDispatchMode.LOW_JITTER)
            )
        }
        Toast.makeText(
            this,
            "模式配置已清除，下次演奏将从低抖动模式重新测试",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 自然顺播到列表末尾停止；手动切歌可循环，最近播放排序不改变已开始的队列。 */
    private fun continueByPlayMode(manualDelta: Int? = null) {
        val current = playingFile ?: return
        val queue = playlist.filter { it.exists() }.ifEmpty { allSongs.filter { it.exists() } }
        if (queue.isEmpty()) { cancelPlayback(); return }
        val next = nextSong(queue, current, playMode.name, manualDelta)
        if (next == null) { cancelPlayback(); return }
        pendingPlayGeneration = ++playbackGeneration
        pendingAutoPlayPath = next.path
        selectSong(next)
    }

    private fun cancelPlayback() {
        playbackGeneration++
        pendingAutoPlayPath = null
        TouchService.instance?.stopPlaying()
    }

    // ---------- 状态与权限 ----------

    private fun refreshStatus() {
        val accOn = isAccessibilityEnabled()
        val store = KeyLayoutStore(this)
        val profile = store.getCurrentProfileName()
        // 本界面固定竖屏，而键位是在游戏（横屏）下校准的，
        // 所以这里列出「已校准了哪些方向」，不按本界面的方向判断可用性。
        val calibrated = profile?.let { store.listRotations(it) } ?: emptyList()
        val midiDevices = (getSystemService(MIDI_SERVICE) as? MidiManager)
            ?.devices?.filter { it.outputPortCount > 0 }.orEmpty()
        statusText.text = buildString {
            append("无障碍服务：").append(
                when {
                    !accOn -> "未开启 ✗"
                    TouchService.instance != null -> "已连接 ✓"
                    else -> "已开启，正在连接…"
                }
            )
                .append("\n")
            append("已校准键位：")
            if (calibrated.isEmpty()) {
                append("无，请先校准")
            } else {
                append(calibrated.joinToString("、") { rotationName(it) })
            }
            append("\n电子琴：")
            if (midiDevices.isEmpty()) append("未检测到 USB MIDI 设备")
            else append(midiDevices.joinToString("、") { it.displayName() })
            append("\n后台保护：")
            append(if (isBatteryProtectionEnabled()) "已允许 ✓" else "受电池优化限制 ✗")
            append("\n演奏时以游戏内的实际方向为准，中途请勿翻转手机。")
        }
    }

    private fun startCalibration() {
        withTouchService { showCalibrationDialog(it) }
    }

    private fun showCalibrationDialog(service: TouchService) {
        fun begin(countdownSeconds: Int) {
            service.startCalibration(countdownSeconds)
            moveTaskToBack(true)
        }
        AlertDialog.Builder(this)
            .setTitle("键位校准")
            .setMessage(
                "请选择准备时间，然后切到原神并打开风物之诗琴，" +
                        "按提示从左到右、从上到下依次点击 21 个琴键。\n\n" +
                        "• 键位与屏幕方向绑定，校准过程中请勿翻转手机，否则本次校准会作废\n" +
                        "• 如果你两种方向都会玩，请在每个方向下各校准一套；" +
                        "挖孔安全区会随方向换边，键位不能自动推导\n" +
                        "• 校准完可以点「测试点击」，看游戏里琴键是否逐个亮起"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("5秒开始") { _, _ -> begin(5) }
            .setNeutralButton("10秒开始") { _, _ -> begin(10) }
            .show()
    }

    /**
     * 系统设置可能已经启用服务，但 Activity 刚启动时 onServiceConnected 尚未完成。
     * 自动等待同进程服务实例出现，避免把短暂的重连状态误报成“未开启”。
     */
    private fun withTouchService(action: (TouchService) -> Unit) {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        TouchService.instance?.let {
            action(it)
            return
        }

        val generation = ++serviceWaitGeneration
        val deadline = SystemClock.uptimeMillis() + 4_000L
        Toast.makeText(this, "无障碍服务正在连接，请稍候…", Toast.LENGTH_SHORT).show()
        fun poll() {
            if (generation != serviceWaitGeneration || isFinishing || isDestroyed) return
            val service = TouchService.instance
            if (service != null) {
                refreshStatus()
                action(service)
                return
            }
            if (SystemClock.uptimeMillis() < deadline) {
                uiHandler.postDelayed({ poll() }, 100L)
                return
            }
            refreshStatus()
            val batteryRestricted = !isBatteryProtectionEnabled()
            AlertDialog.Builder(this)
                .setTitle(if (batteryRestricted) "LyreTest 被系统限制后台" else "无障碍服务尚未连接")
                .setMessage(
                    if (batteryRestricted) {
                        "系统设置虽然保留了无障碍开关，但手机的后台管控已停止 LyreTest 服务。\n\n" +
                                "请先允许后台保活，再到无障碍设置中将 LyreTest 关闭后重新开启一次。"
                    } else {
                        "系统设置显示 LyreTest 已开启，但服务没有连接。" +
                                "请到无障碍设置中将 LyreTest 关闭后重新开启一次。"
                    }
                )
                .setNegativeButton("取消", null)
                .setNeutralButton("无障碍设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setPositiveButton(if (batteryRestricted) "允许后台保活" else "重试") { _, _ ->
                    if (batteryRestricted) requestBatteryProtection() else withTouchService(action)
                }
                .show()
        }
        uiHandler.postDelayed({ poll() }, 100L)
    }

    /** Activity 恢复时短暂轮询，让状态文字自动从“正在连接”变为“已连接”。 */
    private fun pollServiceStatus() {
        val generation = ++statusPollGeneration
        var attempts = 0
        fun poll() {
            if (generation != statusPollGeneration || isFinishing || isDestroyed) return
            refreshStatus()
            if (isAccessibilityEnabled() && TouchService.instance == null && attempts++ < 40) {
                uiHandler.postDelayed({ poll() }, 100L)
            }
        }
        poll()
    }

    private fun isBatteryProtectionEnabled(): Boolean {
        val manager = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    /** 请求加入系统电池优化白名单；失败时退回应用详情页供用户手动设为不受限制。 */
    private fun requestBatteryProtection() {
        if (isBatteryProtectionEnabled()) {
            Toast.makeText(this, "后台保活已允许", Toast.LENGTH_SHORT).show()
            openAppDetails()
            return
        }
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(request) }.onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ))
    }

    /** 通过系统分享导出内部日志，无需存储权限或上传服务器。 */
    private fun exportRuntimeLog() {
        runCatching {
            val file = RuntimeLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "LyreTest 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("LyreTest 运行日志", uri)
            }
            startActivity(Intent.createChooser(share, "发送运行日志"))
        }.onFailure {
            RuntimeLog.error("导出运行日志失败", it)
            Toast.makeText(this, "日志导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${TouchService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

}
