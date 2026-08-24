package com.eta.laotrans

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置弹窗：液态玻璃风格，填写 接口地址 / API Key / 模型，并可切换界面语言（中文/老挝文）。
 * 支持「拉取模型」——用当前填的地址+key 调用该接口的 /models，
 * 列出可用模型供选择，选中后自动回填到模型输入框。
 * 切语言后由调用方 recreate 生效。
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
        val langGroup = view.findViewById<RadioGroup>(R.id.langGroup)

        // 回填已保存的配置
        baseUrlEdit.setText(Config.baseUrl(context))
        apiKeyEdit.setText(Config.apiKey(context))
        modelEdit.setText(Config.model(context))
        val savedLocale = Config.locale(context)
        if (savedLocale == "lo") langGroup.check(R.id.langLo) else langGroup.check(R.id.langZh)
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

        val scope = (context as? AppCompatActivity)?.lifecycleScope

        fun currentLang(): String =
            if (langGroup.checkedRadioButtonId == R.id.langLo) "lo" else "zh"

        view.findViewById<Button>(R.id.cancelBtn).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.saveBtn).setOnClickListener {
            Config.save(
                context,
                baseUrlEdit.text.toString(),
                apiKeyEdit.text.toString(),
                modelEdit.text.toString(),
                currentLang()
            )
            lastStatus = context.getString(R.string.saved_ok)
            dialog.dismiss()
            onSaved()
        }

        fetchBtn.setOnClickListener {
            val baseUrl = baseUrlEdit.text.toString().trim()
            val key = apiKeyEdit.text.toString().trim()
            if (baseUrl.isBlank()) {
                fetchStatus.text = context.getString(R.string.need_base_url)
                return@setOnClickListener
            }
            if (key.isBlank()) {
                fetchStatus.text = context.getString(R.string.need_api_key)
                return@setOnClickListener
            }

            fetchStatus.text = context.getString(R.string.fetching_models)
            fetchBtn.isEnabled = false
            val runner = getRunner(scope) {
                try {
                    val models = withContext(Dispatchers.IO) {
                        // 后台线程允许直接读取保存进 Config 的临时值
                        Config.save(context, baseUrl, key, modelEdit.text.toString(), currentLang())
                        TranslateEngine.listModels(context)
                    }
                    fetchBtn.isEnabled = true
                    if (models.isEmpty()) {
                        fetchStatus.text = context.getString(R.string.no_models)
                    } else {
                        fetchStatus.text = context.getString(R.string.got_models_fmt, models.size)
                        showModelPicker(context, models) { chosen ->
                            modelEdit.setText(chosen)
                            fetchStatus.text = context.getString(R.string.chosen_fmt, chosen)
                        }
                    }
                } catch (e: Exception) {
                    fetchBtn.isEnabled = true
                    fetchStatus.text = context.getString(R.string.fetch_failed_fmt, e.message ?: "")
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
            .setTitle(R.string.settings_model)
            .setSingleChoiceItems(arr, checked) { _, which -> checked = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPick(arr[checked])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
