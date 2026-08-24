package com.eta.laotrans

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 翻译历史列表：展示原文/译文/方向/时间，
 * 每条可一键重读译文（老挝语走 MMS 在线合成、中文/其他走系统 TTS），可单条删除、整体清空。
 */
class HistoryActivity : AppCompatActivity() {

    private var records: List<HistoryRecord> = emptyList()
    private var systemTts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        systemTts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) systemTts?.language = Locale.CHINA
        }

        findViewById<Button>(R.id.historyBackBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.historyClearBtn).setOnClickListener {
            HistoryStore.clear(this)
            Toast.makeText(this, "已清空记录", Toast.LENGTH_SHORT).show()
            reload()
        }

        reload()
    }

    private fun reload() {
        records = HistoryStore.list(this)
        findViewById<ListView>(R.id.historyList).adapter = HistoryAdapter(this, records)
    }

    /** 重读某条译文的译文本体（去掉「转写：」「拼音：」辅助行） */
    private fun play(dst: String) {
        val body = dst.substringBefore("转写：").substringBefore("拼音：").trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "无可朗读内容", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (TranslateEngine.containsLao(body)) {
                Toast.makeText(this@HistoryActivity, "正在合成老挝语音…", Toast.LENGTH_SHORT).show()
                LaoSpeech.speak(body, this@HistoryActivity, 1.0f)
            } else {
                speakZh(body)
            }
        }
    }

    private suspend fun speakZh(text: String) = withContext(Dispatchers.Main) {
        val tts = systemTts
        if (!ttsReady || tts == null) {
            Toast.makeText(this@HistoryActivity, "本机没有可用的中文语音引擎", Toast.LENGTH_SHORT).show()
            return@withContext
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hist_${System.currentTimeMillis()}")
    }

    override fun onDestroy() {
        systemTts?.stop()
        systemTts?.shutdown()
        super.onDestroy()
    }

    private inner class HistoryAdapter(
        private val act: HistoryActivity,
        private val items: List<HistoryRecord>
    ) : BaseAdapter() {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(act).inflate(R.layout.history_item, parent, false)
            val r = items[p]
            v.findViewById<TextView>(R.id.itemDir).text = r.direction
            v.findViewById<TextView>(R.id.itemTime).text = fmt.format(Date(r.time))
            v.findViewById<TextView>(R.id.itemSrc).text = r.srcText
            v.findViewById<TextView>(R.id.itemDst).text = r.dstText
            v.findViewById<Button>(R.id.itemPlay).setOnClickListener { act.play(r.dstText) }
            v.findViewById<Button>(R.id.itemDelete).setOnClickListener {
                HistoryStore.remove(act, r.id)
                act.reload()
            }
            return v
        }
    }
}
