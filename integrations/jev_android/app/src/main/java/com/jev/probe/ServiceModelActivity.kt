package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ModelConfig
import com.jev.probe.core.ModelConfigSnapshot
import com.jev.probe.core.ModelProtocol
import com.jev.probe.core.Prefs
import com.jev.probe.core.ServiceConfig
import com.jev.probe.core.ServiceKind
import com.jev.probe.jev.ModelCatalogClient
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Dedicated service/model catalog. Edits here save independently of other settings. */
class ServiceModelActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private var snapshot = ModelConfigSnapshot()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#2B5245")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val red = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    private fun render() {
        snapshot = prefs.modelSnapshot()
        container.removeAllViews()
        container.addView(button("‹ 返回", outlined = true) { finish() })
        container.addView(title("服务与模型", 24f, ink, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        container.addView(hint("每个服务保存地址和密钥；同一服务下可添加多个模型。" +
            "设置页的接口只选择这里的模型。"))
        container.addView(button("新增服务") { editService(null) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        })

        if (snapshot.services.isEmpty()) {
            container.addView(card("还没有服务", "新增服务并添加模型后，才能选择判断、回复和视觉模型。"))
            return
        }
        snapshot.services.sortedBy { it.name }.forEach { service ->
            container.addView(serviceCard(service))
        }
    }

    private fun serviceCard(service: ServiceConfig): View {
        val box = card(service.name, "${service.kind.label} · ${service.baseUrl}")
        val models = snapshot.models.filter { it.serviceId == service.id }
            .sortedWith(compareBy<ModelConfig> { it.protocol.ordinal }.thenBy { it.label })
        box.addView(tag(if (service.key.isBlank()) "密钥未填写" else "密钥已保存",
            if (service.key.isBlank()) red else accent))
        if (models.isEmpty()) {
            box.addView(hint("尚未添加模型"))
        } else {
            models.forEach { model -> box.addView(modelRow(service, model)) }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton("添加模型") { editModel(service, null) })
        if (service.kind.supportsModelList) {
            actions.addView(smallButton("拉取模型列表") { fetchModels(service) })
        }
        actions.addView(smallButton("编辑服务") { editService(service) })
        actions.addView(smallButton("删除", danger = true) { deleteService(service) })
        box.addView(actions)
        return box
    }

    private fun modelRow(service: ServiceConfig, model: ModelConfig): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(dp(10), Color.parseColor("#F7F8FA"))
            setPadding(dp(12), dp(10), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        row.addView(title(model.label, 14f, ink, true))
        row.addView(hint("${model.protocol.label} · ${model.modelId}" +
            if (model.vision) " · 支持图片" else ""))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton("编辑") { editModel(service, model) })
        actions.addView(smallButton("删除", danger = true) { deleteModel(model) })
        row.addView(actions)
        return row
    }

    private fun editService(existing: ServiceConfig?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val name = input(existing?.name.orEmpty(), "服务名称，例如 OpenRouter")
        val kinds = ServiceKind.entries
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@ServiceModelActivity,
                android.R.layout.simple_spinner_dropdown_item, kinds.map { it.label })
            setSelection(existing?.kind?.ordinal ?: 0)
        }
        val base = input(existing?.baseUrl ?: kinds.first().defaultBaseUrl,
            "API 地址，按提示填写 /v1 或完整 Jev 端点")
        val key = input(existing?.key.orEmpty(), "服务密钥，仅保存在本机", password = true)
        var lastDefault = kinds.first().defaultBaseUrl
        kind.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = kinds[position]
                if (base.text.toString().trim().isBlank() || base.text.toString().trim() == lastDefault)
                    base.setText(selected.defaultBaseUrl)
                lastDefault = selected.defaultBaseUrl
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        layout.addView(label("服务类型"))
        layout.addView(kind)
        layout.addView(label("服务名称"))
        layout.addView(name)
        layout.addView(label("API 地址"))
        layout.addView(base)
        layout.addView(label("密钥"))
        layout.addView(key)
        layout.addView(hint("OpenRouter 填 https://openrouter.ai/api；兼容服务通常填到 /v1；" +
            "自定义 Jev 填完整 POST 地址。"))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增服务" else "编辑服务")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedKind = kinds[kind.selectedItemPosition]
                val serviceName = name.text.toString().trim().ifBlank { selectedKind.label }
                val normalized = runCatching {
                    ServiceConfig.normalizeBase(selectedKind, base.text.toString())
                }.getOrElse { toast(it.message ?: "地址无效"); return@setOnClickListener }
                if (normalized.isBlank()) { toast("请填写 API 地址"); return@setOnClickListener }
                if (existing != null) {
                    val incompatible = snapshot.models.filter { it.serviceId == existing.id }
                        .filter { it.protocol !in selectedKind.protocols }
                    if (incompatible.isNotEmpty()) {
                        toast("请先删除或调整不兼容的模型：${incompatible.joinToString { it.label }}")
                        return@setOnClickListener
                    }
                }
                val saved = ServiceConfig(
                    id = existing?.id ?: "svc_${UUID.randomUUID().toString().replace("-", "")}",
                    name = serviceName,
                    kind = selectedKind,
                    baseUrl = normalized,
                    key = key.text.toString()
                )
                val services = if (existing == null) snapshot.services + saved
                    else snapshot.services.map { if (it.id == existing.id) saved else it }
                persist(snapshot.copy(services = services)) { dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun editModel(service: ServiceConfig, existing: ModelConfig?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val display = input(existing?.displayName.orEmpty(), "显示名称，例如 DeepSeek Chat")
        val modelId = input(existing?.modelId.orEmpty(), "接口实际使用的模型 ID")
        val protocols = service.kind.protocols.toList().sortedBy { it.ordinal }
        val protocolSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ServiceModelActivity,
                android.R.layout.simple_spinner_dropdown_item, protocols.map { it.label })
            setSelection(protocols.indexOf(existing?.protocol).coerceAtLeast(0))
            isEnabled = protocols.size > 1
        }
        val vision = CheckBox(this).apply {
            text = "支持图片输入（视觉 / OCR）"
            isChecked = existing?.vision == true
            setTextColor(ink)
            isEnabled = protocols.contains(ModelProtocol.CHAT)
        }
        val protocolLabel = if (protocols.size == 1) protocols.first().label else null
        layout.addView(label("显示名称"))
        layout.addView(display)
        layout.addView(label("模型 ID"))
        layout.addView(modelId)
        layout.addView(label("调用协议"))
        if (protocolLabel != null) layout.addView(hint(protocolLabel)) else layout.addView(protocolSpinner)
        layout.addView(vision)
        layout.addView(hint("模型 ID 必须与服务实际返回值一致。标准模型列表无法判断图片能力时，请在此手动确认。"))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加模型" else "编辑模型")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = modelId.text.toString().trim()
                if (id.isBlank()) { toast("请填写模型 ID"); return@setOnClickListener }
                val protocol = if (protocols.size == 1) protocols.first()
                    else protocols[protocolSpinner.selectedItemPosition]
                val duplicate = snapshot.models.any {
                    it.id != existing?.id && it.serviceId == service.id &&
                        it.protocol == protocol && it.modelId == id
                }
                if (duplicate) { toast("该服务下已存在相同协议的模型 ID"); return@setOnClickListener }
                val saved = ModelConfig(
                    id = existing?.id ?: "model_${UUID.randomUUID().toString().replace("-", "")}",
                    serviceId = service.id,
                    modelId = id,
                    displayName = display.text.toString().trim().ifBlank { id },
                    protocol = protocol,
                    vision = vision.isChecked && protocol == ModelProtocol.CHAT
                )
                val models = if (existing == null) snapshot.models + saved
                    else snapshot.models.map { if (it.id == existing.id) saved else it }
                persist(snapshot.copy(models = models)) { dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun fetchModels(service: ServiceConfig) {
        toast("正在拉取模型列表…")
        worker.execute {
            val result = runCatching { ModelCatalogClient.fetch(service) }
            main.post {
                result.onSuccess { entries ->
                    if (entries.isEmpty()) {
                        toast("服务返回了空模型列表，可手动添加")
                    } else showImportDialog(service, entries)
                }.onFailure { toast(it.message ?: "拉取模型列表失败") }
            }
        }
    }

    private fun showImportDialog(service: ServiceConfig, entries: List<ModelCatalogClient.Entry>) {
        val existingIds = snapshot.models.filter { it.serviceId == service.id }
            .filter { it.protocol == protocolFor(service.kind) }
            .map { it.modelId }.toSet()
        val selected = HashSet<String>()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
        }
        val search = input("", "搜索模型 ID").apply { inputType = InputType.TYPE_CLASS_TEXT }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        layout.addView(search)
        layout.addView(scroll)
        fun renderChoices() {
            list.removeAllViews()
            val query = search.text.toString().trim().lowercase()
            entries.filter { query.isBlank() || it.id.lowercase().contains(query) }
                .forEach { entry ->
                    val row = CheckBox(this).apply {
                        text = entry.id + if (entry.vision == true) "（元数据标记支持图片）" else ""
                        isChecked = entry.id in selected
                        isEnabled = entry.id !in existingIds
                        setTextColor(if (isEnabled) ink else sub)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(entry.id) else selected.remove(entry.id)
                        }
                    }
                    list.addView(row)
                }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderChoices()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderChoices()
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择要导入的模型")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                if (selected.isEmpty()) return@setPositiveButton
                val added = entries.filter { it.id in selected }.map { entry ->
                    ModelConfig(
                        id = "model_${UUID.randomUUID().toString().replace("-", "")}",
                        serviceId = service.id,
                        modelId = entry.id,
                        displayName = entry.id,
                        protocol = protocolFor(service.kind),
                        vision = entry.vision == true
                    )
                }
                persist(snapshot.copy(models = snapshot.models + added))
            }
            .create()
        dialog.show()
    }

    private fun deleteService(service: ServiceConfig) {
        val refs = snapshot.referencesForService(service.id)
        if (refs.isNotEmpty()) {
            toast("该服务仍被接口或功能模型引用，请先解除关联")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除服务")
            .setMessage("将删除“${service.name}”及其所有模型，不能恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val models = snapshot.models.filterNot { it.serviceId == service.id }
                persist(snapshot.copy(
                    services = snapshot.services.filterNot { it.id == service.id },
                    models = models))
            }.show()
    }

    private fun deleteModel(model: ModelConfig) {
        val refs = snapshot.referencesForModel(model.id)
        if (refs.isNotEmpty()) {
            toast("该模型仍被引用：${refs.joinToString()}；请先解除关联")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("删除“${model.label}”？不能恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                persist(snapshot.copy(models = snapshot.models.filterNot { it.id == model.id }))
            }.show()
    }

    private fun protocolFor(kind: ServiceKind): ModelProtocol = when {
        kind.protocols.size == 1 -> kind.protocols.first()
        else -> ModelProtocol.CHAT
    }

    private fun persist(updated: ModelConfigSnapshot, after: () -> Unit = {}) {
        runCatching { updated.validate() }
            .onFailure { toast(it.message ?: "配置无效"); return }
        if (!prefs.saveModelSnapshot(updated)) { toast("保存失败"); return }
        snapshot = updated
        render()
        after()
    }

    private fun card(titleText: String, detail: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(dp(14), Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            addView(title(titleText, 16f, ink, true))
            addView(hint(detail))
        }

    private fun input(value: String, hintText: String, password: Boolean = false) =
        EditText(this).apply {
            setText(value)
            hint = hintText
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(Color.parseColor("#9CA3AF"))
            background = round(dp(8), Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2) }
        }

    private fun title(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun hint(text: String) = title(text, 12.5f, sub).apply {
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun label(text: String) = title(text, 13f, ink, true).apply {
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun tag(text: String, color: Int) = title(text, 11.5f, color, true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = round(dp(8), if (color == red) Color.parseColor("#FEE2E2") else Color.parseColor("#E7F3EE"))
    }

    private fun button(text: String, outlined: Boolean = false, onClick: () -> Unit) =
        title(text, 14f, if (outlined) accent else Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(dp(10), if (outlined) Color.WHITE else accent, stroke = outlined)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, danger: Boolean = false, onClick: () -> Unit) =
        title(text, 12.5f, if (danger) red else accent, true).apply {
            gravity = Gravity.CENTER
            background = round(dp(9), Color.WHITE, stroke = true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(7) }
            setOnClickListener { onClick() }
        }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
