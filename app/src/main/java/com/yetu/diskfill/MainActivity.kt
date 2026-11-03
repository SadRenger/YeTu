package com.yetu.diskfill

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.StatFs
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class MainActivity : Activity() {

    companion object {
        const val MB = 1024L * 1024L
        const val CHUNK_MB = 50          // 每块写入大小
        const val MIN_RESERVE_MB = 100   // 预留量下限（上架安全限制）
        const val PREFS = "yetu_prefs"
        const val KEY_AGREED = "disclaimer_agreed"
    }

    private val fillFile by lazy { File(filesDir, "fill_file.bin") }
    private val writeBuf = ByteArray(8 * 1024 * 1024) // 8MB 零缓冲

    @Volatile private var busy = false
    @Volatile private var filling = false
    @Volatile private var stopRequested = false

    private lateinit var tvFreeSpace: TextView
    private lateinit var etReserve: EditText
    private lateinit var etAmount: EditText
    private lateinit var btnFill: Button
    private lateinit var btnFree: Button
    private lateinit var btnGrow: Button
    private lateinit var btnClean: Button
    private lateinit var btnRefresh: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFreeSpace = findViewById(R.id.tvFreeSpace)
        etReserve = findViewById(R.id.etReserve)
        etAmount = findViewById(R.id.etAmount)
        btnFill = findViewById(R.id.btnFill)
        btnFree = findViewById(R.id.btnFree)
        btnGrow = findViewById(R.id.btnGrow)
        btnClean = findViewById(R.id.btnClean)
        btnRefresh = findViewById(R.id.btnRefresh)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        btnFill.setOnClickListener { if (filling) stopRequested = true else startFill() }
        btnFree.setOnClickListener { adjust(free = true) }
        btnGrow.setOnClickListener { adjust(free = false) }
        btnClean.setOnClickListener { cleanup() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshFreeSpace() }

        // 仿真按压效果：按下缩小、抬起弹回
        applyPressEffect(btnFill)
        applyPressEffect(btnRefresh)
        applyPressEffect(btnFree)
        applyPressEffect(btnGrow)
        applyPressEffect(btnClean)

        refreshFreeSpace()
        checkDisclaimer()
        checkResidual()
    }

    // ---------- 免责声明 ----------

    private fun checkDisclaimer() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AGREED, false)) return
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(
                "野兔是一款磁盘压力测试工具，仅供开发测试使用。\n\n" +
                "本工具会主动占满设备存储空间，可能导致：\n" +
                "· 部分应用无法保存数据或闪退\n" +
                "· 系统功能异常（截图、下载等）\n\n" +
                "测试结束后请点击「一键清理」恢复空间。\n" +
                "因使用本工具造成的数据丢失，作者不承担任何责任。\n\n" +
                "点击「同意并继续」即表示你已知晓并接受上述风险。"
            )
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                prefs.edit().putBoolean(KEY_AGREED, true).apply()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    // ---------- 残留检测 ----------

    private fun checkResidual() {
        if (fillFile.exists() && fillFile.length() > 0) {
            val sizeMb = fillFile.length() / MB
            AlertDialog.Builder(this)
                .setTitle("检测到残留数据")
                .setMessage("发现上次未清理的填充文件（约 ${sizeMb}MB），是否立即清理？")
                .setPositiveButton("清理") { _, _ -> cleanup() }
                .setNegativeButton("保留", null)
                .show()
        }
    }

    // ---------- 空间查询 ----------

    private fun availMb(): Long = StatFs(filesDir.absolutePath).availableBytes / MB

    private fun fileSizeMb(): Long = if (fillFile.exists()) fillFile.length() / MB else 0

    private fun refreshFreeSpace() {
        runOnUiThread { tvFreeSpace.text = "${availMb()} MB" }
    }

    private fun setStatus(text: String, pct: Int = -1) {
        runOnUiThread {
            tvStatus.text = text
            if (pct >= 0) progress.progress = pct
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        runOnUiThread {
            // 填充进行中 btnFill 保留可点（充当停止按钮），其余操作时禁用
            btnFill.isEnabled = !value || filling
            btnFree.isEnabled = !value
            btnGrow.isEnabled = !value
            btnClean.isEnabled = !value
            btnRefresh.isEnabled = !value
        }
    }

    // ---------- 按压效果 & 按钮状态切换 ----------

    private fun applyPressEffect(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false // 不拦截点击事件
        }
    }

    private fun setFillButtonAsStop() {
        btnFill.text = "停止填充"
        btnFill.setBackgroundResource(R.drawable.bg_button_stop)
        btnFill.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun setFillButtonNormal() {
        btnFill.text = "开始填充"
        btnFill.setBackgroundResource(R.drawable.bg_button_primary)
        btnFill.setTextColor(0xFFFFFFFF.toInt())
    }

    // ---------- 填充（闭环：边写边测，直到达到预留量） ----------

    private fun startFill() {
        if (busy) return
        var reserve = etReserve.text.toString().toLongOrNull() ?: MIN_RESERVE_MB.toLong()
        if (reserve < MIN_RESERVE_MB) {
            toast("预留量最低 ${MIN_RESERVE_MB}MB，已自动调整")
            reserve = MIN_RESERVE_MB.toLong()
            etReserve.setText(reserve.toString())
        }
        val reserveMb = reserve
        val avail = availMb()
        if (avail <= reserveMb) {
            toast("当前剩余 ${avail}MB，已小于预留量，无需填充")
            return
        }
        val totalPlan = avail - reserveMb
        filling = true
        stopRequested = false
        setBusy(true)
        runOnUiThread { setFillButtonAsStop() }
        Thread {
            var failCount = 0
            try {
                while (true) {
                    if (stopRequested) {
                        setStatus("已手动停止，剩余 ${availMb()}MB（目标 ${reserveMb}MB）")
                        break
                    }
                    val cur = availMb()
                    val remaining = cur - reserveMb
                    if (remaining <= 0) break
                    val chunk = minOf(CHUNK_MB.toLong(), remaining)
                    val ok = appendZeros(chunk)
                    if (!ok) {
                        failCount++
                        if (failCount >= 3) {
                            setStatus("连续写入失败，已停止")
                            break
                        }
                        continue // 磁盘满时下一轮重新校准
                    }
                    failCount = 0
                    val written = totalPlan - (availMb() - reserveMb)
                    val pct = (written.coerceIn(0, totalPlan) * 100 / totalPlan).toInt()
                    setStatus(
                        "已写入 ${fileSizeMb()}MB | 当前剩余 ${availMb()}MB | 目标预留 ${reserveMb}MB",
                        pct
                    )
                }
                setStatus("填充完成，剩余 ${availMb()}MB（目标 ${reserveMb}MB）", 100)
            } finally {
                filling = false
                stopRequested = false
                setBusy(false)
                runOnUiThread { setFillButtonNormal() }
                refreshFreeSpace()
            }
        }.start()
    }

    /** 追加写入 zeros，返回是否成功（磁盘满返回 false） */
    private fun appendZeros(sizeMb: Long): Boolean {
        return try {
            FileOutputStream(fillFile, true).use { fos ->
                var left = sizeMb * MB
                while (left > 0) {
                    val n = minOf(writeBuf.size.toLong(), left).toInt()
                    fos.write(writeBuf, 0, n)
                    left -= n
                }
                fos.fd.sync()
            }
            true
        } catch (e: Exception) {
            false // 通常是 ENOSPC，由闭环逻辑重新校准
        }
    }

    // ---------- 微调 ----------

    private fun adjust(free: Boolean) {
        if (busy) return
        val n = etAmount.text.toString().toLongOrNull()
        if (n == null || n <= 0) {
            toast("请输入大于 0 的数值")
            return
        }
        setBusy(true)
        Thread {
            try {
                if (free) freeSpace(n) else occupySpace(n)
            } finally {
                setBusy(false)
                refreshFreeSpace()
            }
        }.start()
    }

    private fun freeSpace(nMb: Long) {
        val size = fileSizeMb()
        if (size <= 0) {
            setStatus("填充文件不存在，无法释放")
            return
        }
        val actual = minOf(nMb, size)
        if (actual < nMb) setStatus("文件只有 ${size}MB，将释放整个文件")
        val newSize = size - actual
        try {
            if (newSize == 0L) {
                fillFile.delete()
            } else {
                RandomAccessFile(fillFile, "rw").use { it.setLength(newSize * MB) }
            }
            setStatus("已释放 ${size - fileSizeMb()}MB（填充文件剩余 ${fileSizeMb()}MB）")
        } catch (e: Exception) {
            setStatus("释放失败: ${e.message}")
        }
    }

    private fun occupySpace(nMb: Long) {
        val avail = availMb()
        if (avail <= 0) {
            setStatus("磁盘已无剩余空间")
            return
        }
        val actual = minOf(nMb, avail)
        if (actual < nMb) setStatus("剩余只有 ${avail}MB，将占用全部剩余空间")
        val before = fileSizeMb()
        var written = 0L
        while (written < actual) {
            val chunk = minOf(CHUNK_MB.toLong(), actual - written)
            if (!appendZeros(chunk)) {
                setStatus("磁盘已满，本次占用了 ${fileSizeMb() - before}MB")
                return
            }
            written += chunk
            setStatus("占用中: ${written}/${actual}MB", (written * 100 / actual).toInt())
        }
        setStatus("已占用 ${fileSizeMb() - before}MB（填充文件当前 ${fileSizeMb()}MB）")
    }

    // ---------- 清理 ----------

    private fun cleanup() {
        if (busy) return
        Thread {
            val size = fileSizeMb()
            fillFile.delete()
            setStatus(if (size > 0) "已清理 ${size}MB，磁盘空间已恢复" else "无需清理")
            progress.post { progress.progress = 0 }
            refreshFreeSpace()
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
