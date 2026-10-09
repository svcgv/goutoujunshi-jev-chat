package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.InterfaceBindings
import com.jev.probe.core.FeatureModelOverrides
import com.jev.probe.core.ModelConfigSnapshot
import com.jev.probe.core.ModelConfigStore
import com.jev.probe.core.ModelProtocol
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import com.jev.probe.jev.StrategyClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#2B5245")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")
    private var snapshot = ModelConfigSnapshot()
    private var judgeRef = ""
    private var replyRef = ""
    private var visionRef = ""
    private var openRef = ""
    private var endRef = ""
    private var consultRef = ""
    private lateinit var judgeValue: TextView
    private lateinit var replyValue: TextView
    private lateinit var visionValue: TextView
    private lateinit var openValue: TextView
    private lateinit var endValue: TextView
    private lateinit var consultValue: TextView
    private var awaitingCatalog = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))
        snapshot = prefs.modelSnapshot()
        judgeRef = snapshot.bindings.judgeModelId
        replyRef = snapshot.bindings.replyModelId
        visionRef = snapshot.bindings.visionModelId
        openRef = snapshot.overrides.openModelId
        endRef = snapshot.overrides.endModelId
        consultRef = snapshot.overrides.consultModelId

        val manageCard = card()
        manageCard.addView(cardTitle("服务与模型"))
        manageCard.addView(text("每个服务只保存一次地址和密钥；同一服务下可添加多个模型。" +
            "接口只保存所选模型引用，服务修改后所有引用自动生效。", 12f, sub))
        manageCard.addView(cardBtn("管理服务与模型") {
            awaitingCatalog = true
            startActivity(Intent(this, ServiceModelActivity::class.java))
        })
        manageCard.addView(text("当前：${snapshot.services.size} 个服务，${snapshot.models.size} 个模型", 11.5f, sub))
        root.addView(manageCard)

        val judgeCard = card()
        judgeCard.addView(cardTitle("判断模型"))
        judgeCard.addView(text("选择 Jev 或聊天模型。聊天模型走现有策略判断；Jev 模型走专用 decisions 接口。", 12f, sub))
        judgeValue = modelValue()
        judgeCard.addView(judgeValue)
        judgeCard.addView(cardBtn("选择判断模型") {
            pickModel("选择判断模型", null, false, true, judgeRef) { judgeRef = it; refreshModelLabels() }
        })
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试判断") { testJudge(judgeResult) })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        val replyCard = card()
        replyCard.addView(cardTitle("回复模型"))
        replyCard.addView(text("生成普通回复、详细分析、解释和口吻改写；必须是 OpenAI 兼容聊天模型。", 12f, sub))
        replyValue = modelValue()
        replyCard.addView(replyValue)
        replyCard.addView(cardBtn("选择回复模型") {
            pickModel("选择回复模型", ModelProtocol.CHAT, false, true, replyRef) { replyRef = it; refreshModelLabels() }
        })
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") { testReply(replyResult) })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        val visionCard = card()
        visionCard.addView(cardTitle("视觉模型（OCR 用，可不配置）"))
        visionCard.addView(text("只显示已标记“支持图片”的聊天模型。测试只发送 1×1 测试图，不读取聊天截图。", 12f, sub))
        visionValue = modelValue()
        visionCard.addView(visionValue)
        visionCard.addView(cardBtn("选择视觉模型") {
            pickModel("选择视觉模型", ModelProtocol.CHAT, true, true, visionRef) {
                visionRef = it
                refreshModelLabels()
            }
        })
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") { testVision(visionResult) })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 功能模型 ===================
        root.addView(section("功能模型"))
        val featureCard = card()
        featureCard.addView(cardTitle("军师任务的生成模型"))
        featureCard.addView(text("默认跟随全局回复模型；也可为单个功能选择其他聊天模型。" +
            "普通“帮我回复”始终使用全局回复模型。", 12f, sub))
        openValue = modelValue()
        featureCard.addView(label("发起聊天"))
        featureCard.addView(openValue)
        featureCard.addView(cardBtn("选择发起聊天模型") {
            pickModel("发起聊天生成模型", ModelProtocol.CHAT, false, true, openRef) {
                openRef = it
                refreshModelLabels()
            }
        })
        endValue = modelValue()
        featureCard.addView(label("暂时离开会话"))
        featureCard.addView(endValue)
        featureCard.addView(cardBtn("选择暂时离开模型") {
            pickModel("暂时离开生成模型", ModelProtocol.CHAT, false, true, endRef) {
                endRef = it
                refreshModelLabels()
            }
        })
        consultValue = modelValue()
        featureCard.addView(label("问军师"))
        featureCard.addView(consultValue)
        featureCard.addView(cardBtn("选择问军师模型") {
            pickModel("问军师生成模型", ModelProtocol.CHAT, false, true, consultRef) {
                consultRef = it
                refreshModelLabels()
            }
        })
        root.addView(featureCard)
        clampModelReferences()

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)

        // --- OCR 兜底（B 阶段）---
        card2.addView(label("截图识图方式"))
        var ocrEngineIdx = if (prefs.ocrEngine == Prefs.OCR_VISION) 1 else 0
        card2.addView(pills(listOf("ML Kit 本地", "视觉模型（DeepSeek 等）"), ocrEngineIdx) {
            ocrEngineIdx = it
        })
        card2.addView(text("视觉模型会收到裁剪后的聊天截图并产生接口用量；识别原文和说话人仍需核对。" +
            "DeepSeek Flash 可用于图片识别。", 11f, sub))
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("微信可从悬浮助手手动识别；失败时使用系统授权截屏或导入截图。仅支持一对一，系统安全窗口不可绕过。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时自动 OCR 只亮悬浮球，点分析后核对；手动截屏仍直接打开核对页。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val skillRow = toggleRow("注入狗头军师知识库参考", prefs.skillKnowledgeEnabled)
        card2.addView(skillRow)
        card2.addView(text("随 App 内置的军师方法摘要，分析时按主题在本机匹配后随提示词发送（不含完整聊天、不联网检索）。关闭后只用内置规则与你的档案。", 11f, sub))
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("总开关关闭时不写入或注入历史。每个会话还须先绑定对象并单独同意记忆；仅核对确认后保存。旧联系人需重新绑定一次。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(label("模型上下文窗口（tokens，1 024–1 000 000）"))
        val ctxWindowEdit = edit(prefs.modelContextWindow.toString(), "8192").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxWindowEdit)
        card2.addView(text("用于估算每次分析能带多少对话。留默认 8192 即可；若代理实际窗口更小，" +
            "过长对话会改为分段提取要点后再分析，核对原文不受影响。", 11f, sub))
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        card2.addView(cardBtn("精简记忆与咨询原文") {
            startActivity(android.content.Intent(this, MemoryActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        // Diagnostics: surface the last recorded crash so a failure during
        // binding/analysis can be reported instead of only "it stopped".
        card2.addView(cardBtn("查看最近崩溃（如有）") {
            val crash = CrashLogger.readLast(this)
            if (crash.isNullOrBlank()) {
                kbResult.text = "没有记录到的崩溃"
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("最近崩溃")
                    .setMessage(crash.takeLast(4000))
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("清除") { _, _ ->
                        CrashLogger.clear(this); kbResult.text = "已清除崩溃记录"
                    }
                    .show()
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "这个 App 会读取你当前聊天窗口的文字，发给你自己配置的模型接口做判断和起草回复。作者不运营服务器，收不到你的数据。",
            12f, sub))
        aboutCard.addView(cardBtn("隐私政策") { openUrl(PRIVACY_URL) })
        aboutCard.addView(cardBtn("开源仓库") { openUrl(REPO_URL) })
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            snapshot = prefs.modelSnapshot()
            clampModelReferences()
            val updated = snapshot.copy(
                bindings = InterfaceBindings(judgeRef, replyRef, visionRef),
                overrides = FeatureModelOverrides(openRef, endRef, consultRef)
            )
            runCatching { updated.validate() }.onFailure {
                Toast.makeText(this, it.message ?: "模型关联无效", Toast.LENGTH_LONG).show()
                return@primaryBtn
            }
            if (!prefs.saveModelSnapshot(updated)) {
                Toast.makeText(this, "模型关联保存失败", Toast.LENGTH_LONG).show()
                return@primaryBtn
            }
            snapshot = updated

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: false
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrEngine = if (ocrEngineIdx == 1) Prefs.OCR_VISION else Prefs.OCR_MLKIT
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.skillKnowledgeEnabled = (skillRow.tag as? Boolean) ?: true
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.modelContextWindow =
                ctxWindowEdit.text.toString().trim().toIntOrNull()?.coerceIn(1024, 1_000_000) ?: 8192
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingCatalog) return
        awaitingCatalog = false
        snapshot = prefs.modelSnapshot()
        clampModelReferences()
    }

    private fun modelValue() = text("", 13f, ink).apply {
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun clampModelReferences() {
        if (snapshot.model(judgeRef) == null) judgeRef = ""
        if (snapshot.model(replyRef) == null) replyRef = ""
        if (snapshot.model(visionRef) == null) visionRef = ""
        if (snapshot.model(openRef) == null) openRef = ""
        if (snapshot.model(endRef) == null) endRef = ""
        if (snapshot.model(consultRef) == null) consultRef = ""
        refreshModelLabels()
    }

    private fun refreshModelLabels() {
        if (!::judgeValue.isInitialized) return
        judgeValue.text = modelLabel(judgeRef, "未选择")
        replyValue.text = modelLabel(replyRef, "未选择")
        visionValue.text = modelLabel(visionRef, "不配置")
        openValue.text = featureLabel(openRef)
        endValue.text = featureLabel(endRef)
        consultValue.text = featureLabel(consultRef)
    }

    private fun featureLabel(ref: String): String =
        if (ref.isBlank()) "跟随全局回复模型：${modelLabel(replyRef, "尚未选择")}"
        else "独立模型：${modelLabel(ref, "引用失效")}"

    private fun modelLabel(ref: String, fallback: String): String {
        if (ref.isBlank()) return fallback
        val route = snapshot.resolve(ref) ?: return "引用失效，请重新选择"
        return "${route.serviceName} / ${route.modelName} · ${route.modelId}"
    }

    private fun pickModel(title: String, protocol: ModelProtocol?, visionOnly: Boolean,
                          allowNone: Boolean, currentRef: String, onPick: (String) -> Unit) {
        snapshot = prefs.modelSnapshot()
        clampModelReferences()
        val options = snapshot.modelOptions(protocol, visionOnly)
        val labels = ArrayList<String>()
        val values = ArrayList<String>()
        if (allowNone) {
            labels.add("不选择")
            values.add("")
        }
        options.forEach { model ->
            labels.add("${snapshot.service(model.serviceId)?.name.orEmpty()} / ${model.label}（${model.modelId}）")
            values.add(model.id)
        }
        val initial = values.indexOf(currentRef).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), initial) { dialog, which ->
                onPick(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testPrefs(bindings: InterfaceBindings, overrides: FeatureModelOverrides): Prefs {
        val test = snapshot.copy(bindings = bindings, overrides = overrides)
        val sp = getSharedPreferences(SCRATCH_MODEL_TEST, MODE_PRIVATE)
        sp.edit().clear().commit()
        check(ModelConfigStore(sp).save(test)) { "测试配置无效" }
        return Prefs(this, SCRATCH_MODEL_TEST)
    }

    private fun testJudge(result: TextView) {
        if (judgeRef.isBlank()) { result.text = "请先选择判断模型"; return }
        val route = snapshot.resolve(judgeRef) ?: run { result.text = "模型引用失效"; return }
        val probe = runCatching { testPrefs(InterfaceBindings(judgeRef), FeatureModelOverrides()) }
            .getOrElse { result.text = it.message ?: "测试配置无效"; return }
        result.text = "测试中…"
        worker.execute {
            val start = System.currentTimeMillis()
            val demo = ChatSnapshot("连通测试", listOf(Msg("other", "在吗？"), Msg("me", "在")))
            val outcome = runCatching {
                if (route.protocol == ModelProtocol.JEV) JudgeClient(probe).judge(demo, prefs.relationship)
                else StrategyClient(probe, applicationContext).judge(demo, prefs.relationship)
            }
            val ms = System.currentTimeMillis() - start
            main.post {
                outcome.onSuccess { analysis ->
                    result.text = analysis.error?.let { "失败（${ms}ms）：$it" }
                        ?: "成功 ${ms}ms · 策略=${analysis.strategy ?: analysis.bestAction?.choice ?: "?"}"
                }.onFailure { result.text = "失败（${ms}ms）：${it.message ?: "请求失败"}" }
            }
        }
    }

    private fun testReply(result: TextView) {
        if (replyRef.isBlank()) { result.text = "请先选择回复模型"; return }
        val probe = runCatching { testPrefs(InterfaceBindings(replyModelId = replyRef), FeatureModelOverrides()) }
            .getOrElse { result.text = it.message ?: "测试配置无效"; return }
        result.text = "测试中…"
        worker.execute {
            val start = System.currentTimeMillis()
            val outcome = runCatching { ReplyClient(probe).ping() }
            val ms = System.currentTimeMillis() - start
            main.post {
                outcome.onSuccess { result.text = "成功 ${ms}ms · 返回：${it.replace("\n", " ").take(60)}" }
                    .onFailure { result.text = "失败（${ms}ms）：${it.message ?: "请求失败"}" }
            }
        }
    }

    private fun testVision(result: TextView) {
        if (visionRef.isBlank()) { result.text = "请先选择已标记支持图片的视觉模型"; return }
        val probe = runCatching { testPrefs(InterfaceBindings(visionModelId = visionRef), FeatureModelOverrides()) }
            .getOrElse { result.text = it.message ?: "测试配置无效"; return }
        result.text = "测试中…"
        worker.execute {
            val start = System.currentTimeMillis()
            val outcome = runCatching {
                VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
            }
            val ms = System.currentTimeMillis() - start
            main.post {
                outcome.onSuccess { result.text = "成功 ${ms}ms · 返回：${it.replace("\n", " ").take(60)}" }
                    .onFailure { result.text = "失败（${ms}ms）：${it.message ?: "请求失败"}" }
            }
        }
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Throwaway complete snapshot used by the three connectivity tests. */
        private const val SCRATCH_MODEL_TEST = "jev_probe_scratch_model"

        private const val PRIVACY_URL = "https://github.com/shengjidaguai-china/goutoujunshi-jev-chat/blob/main/PRIVACY.md"
        private const val REPO_URL = "https://github.com/shengjidaguai-china/goutoujunshi-jev-chat"
    }
}
