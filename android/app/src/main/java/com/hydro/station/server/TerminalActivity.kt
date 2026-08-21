package com.hydro.station.server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.hydro.station.server.util.DataManager
import com.hydro.station.server.util.ShizukuManager
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dataManager by lazy { DataManager.get(this) }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var outputScroll: ScrollView
    private lateinit var tvOutput: TextView
    private lateinit var etCommand: TextInputEditText
    private lateinit var btnSend: MaterialButton

    private val outputBuilder = SpannableStringBuilder()
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        initViews()
        setupListeners()
        appendWelcome()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        outputScroll = findViewById(R.id.outputScroll)
        tvOutput = findViewById(R.id.tvOutput)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnSend.setOnClickListener {
            executeCommand()
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                executeCommand()
                true
            } else {
                false
            }
        }

        // Command history navigation with volume up/down or long press
        etCommand.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP && commandHistory.isNotEmpty()) {
                    if (historyIndex < commandHistory.size - 1) {
                        historyIndex++
                        etCommand.setText(commandHistory[commandHistory.size - 1 - historyIndex])
                        etCommand.setSelection(etCommand.text?.length ?: 0)
                    }
                    true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && commandHistory.isNotEmpty()) {
                    if (historyIndex > 0) {
                        historyIndex--
                        etCommand.setText(commandHistory[commandHistory.size - 1 - historyIndex])
                        etCommand.setSelection(etCommand.text?.length ?: 0)
                    } else if (historyIndex == 0) {
                        historyIndex = -1
                        etCommand.setText("")
                    }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun appendWelcome() {
        val config = dataManager.loadConfig()
        val mode = if (config.useShizuku) "Shizuku" else "Shell (App)"
        appendText("HydroStation Terminal\n", Typeface.BOLD, getColor(R.color.md_theme_primary))
        appendText("执行模式: $mode\n", Typeface.NORMAL, getColor(R.color.md_theme_outline))
        appendText("输入命令并按发送执行。\n\n", Typeface.NORMAL, getColor(R.color.md_theme_outline))
    }

    private fun executeCommand() {
        val cmd = etCommand.text?.toString()?.trim() ?: ""
        if (cmd.isEmpty()) return

        // Add to history
        commandHistory.add(cmd)
        historyIndex = -1
        etCommand.setText("")

        // Append command to output
        appendText("$ ", Typeface.BOLD, getColor(R.color.md_theme_primary))
        appendText("$cmd\n", Typeface.NORMAL, getColor(R.color.md_theme_onSurface))

        // Disable send button while executing
        btnSend.isEnabled = false
        appendText("执行中...\n", Typeface.ITALIC, getColor(R.color.md_theme_outline))

        Thread {
            val config = dataManager.loadConfig()
            var output: String
            var returnCode: Int
            var success: Boolean

            if (config.useShizuku) {
                val (s, out, rc) = ShizukuManager.executeCommandSync(cmd)
                success = s
                output = out
                returnCode = rc
            } else {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    output = BufferedReader(InputStreamReader(process.inputStream)).readText() +
                            BufferedReader(InputStreamReader(process.errorStream)).readText()
                    process.waitFor()
                    returnCode = process.exitValue()
                    success = true
                } catch (e: Exception) {
                    output = e.message ?: "执行失败"
                    returnCode = -1
                    success = false
                }
            }

            handler.post {
                // Remove "执行中..." text by rebuilding
                btnSend.isEnabled = true

                if (success) {
                    if (output.isNotEmpty()) {
                        appendText(output, Typeface.NORMAL, getColor(R.color.md_theme_onSurface))
                        if (!output.endsWith("\n")) appendText("\n", Typeface.NORMAL, getColor(R.color.md_theme_onSurface))
                    }
                    if (returnCode != 0) {
                        appendText("[退出码: $returnCode]\n", Typeface.BOLD, getColor(R.color.md_theme_error))
                    }
                } else {
                    appendText("错误: $output\n", Typeface.BOLD, getColor(R.color.md_theme_error))
                }
                appendText("\n", Typeface.NORMAL, getColor(R.color.md_theme_onSurface))
                scrollToBottom()
            }
        }.start()
    }

    private fun appendText(text: String, style: Int, color: Int) {
        val start = outputBuilder.length
        outputBuilder.append(text)
        outputBuilder.setSpan(StyleSpan(style), start, outputBuilder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputBuilder.setSpan(ForegroundColorSpan(color), start, outputBuilder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvOutput.text = outputBuilder
        scrollToBottom()
    }

    private fun scrollToBottom() {
        outputScroll.post {
            outputScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
