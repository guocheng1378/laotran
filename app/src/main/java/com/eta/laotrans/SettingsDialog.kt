package com.eta.laotrans

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置弹窗：液态玻璃风格，填写 接口地址 / API Key / 模型。
 * 支持「拉取模型」—— 用当前填的地址+key 调用该接口的 /models，
 * 列出可用模型供选择，选中后自动回填到模型输入框。
 */
object SettingsDialog {

    private var lastStatus: String = ""

    fun show(context: Context, onSaved: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)
        val baseUrlEdit = view.findViewById<EditText>(R.id.baseUrlEdit)
        val apiKeyEdit = view.findViewById<EditText>(R.id.apiKeyEdit)
        val modelEdit = view.findViewById<EditText>(R.id.modelEdit)
        val fetchBtn = view.findViewById<Button>(R.id.fetchModelsBtn)
        val fetchStatus = view.findViewById<TextView>(R.id.fetchStatusText)

        // 回填已保存的配置
        baseUrlEdit.setText(Config.baseUrl(context))
        apiKeyEdit.setText(Config.apiKey(context))
        modelEdit.setText(Config.model(context))
        fetchStatus.text = lastStatus

        val dialog = AppCompatDialog(context)
        dialog.setContentView(view)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            w.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.35f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        // 用 Activity 的生命周期协程作用域
        val scope = (context as? AppCompatActivity)?.lifecycleScope

        view.findViewById<Button>(R.id.cancelBtn).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.saveBtn).setOnClickListener {
            Config.save(
                context,
                baseUrlEdit.text.toString(),
                apiKeyEdit.text.toString(),
                modelEdit.text.toString()
            )
            lastStatus = "已保存"
            dialog.dismiss()
            onSaved()
        }

        fetchBtn.setOnClickListener {
            val baseUrl = baseUrlEdit.text.toString().trim()
            val key = apiKeyEdit.text.toString().trim()
            if (baseUrl.isBlank()) { fetchStatus.text = "请先填写接口地址"; return@setOnClickListener }
            if (key.isBlank()) { fetchStatus.text = "请先填写 API Key"; return@setOnClickListener }

            fetchStatus.text = "正在拉取模型…"
            fetchBtn.isEnabled = false
            val runner = getRunner(scope) {
                try {
                    val models = withContext(Dispatchers.IO) {
                        // 后台线程允许直接读取保存进 Config 的临时值
                        Config.save(context, baseUrl, key, modelEdit.text.toString())
                        TranslateEngine.listModels(context)
                    }
                    fetchBtn.isEnabled = true
                    if (models.isEmpty()) {
                        fetchStatus.text = "未获取到模型"
                    } else {
                        fetchStatus.text = "获取到 ${models.size} 个模型，请选择"
                        showModelPicker(context, models) { chosen ->
                            modelEdit.setText(chosen)
                            fetchStatus.text = "已选择：$chosen"
                        }
                    }
                } catch (e: Exception) {
                    fetchBtn.isEnabled = true
                    fetchStatus.text = "拉取失败：${e.message}"
                }
            }
            runner()
        }

        dialog.show()
    }

    private fun getRunner(scope: androidx.lifecycle.LifecycleCoroutineScope?, block: suspend () -> Unit): () -> Unit {
        return if (scope != null) {
            { scope.launch { block() } }
        } else {
            { kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { block() } }
        }
    }

    private fun showModelPicker(context: Context, models: List<String>, onPick: (String) -> Unit) {
        val arr = models.toTypedArray()
        val current = Config.model(context)
        var checked = arr.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        AlertDialog.Builder(context)
            .setTitle("选择模型")
            .setSingleChoiceItems(arr, checked) { _, which -> checked = which }
            .setPositiveButton("确定") { _, _ ->
                onPick(arr[checked])
            }
            .setNegativeButton("取消", null)
            .show()
    }
}