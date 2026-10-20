package cn.hnist.timetable

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cn.hnist.timetable.importer.ExpImporter
import cn.hnist.timetable.ui.CrashGuard
import cn.hnist.timetable.ui.EdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

/** 实验课一键导入：登录教学信息化管理系统，把已选实验合并进现有课表。 */
class ExpImportActivity : AppCompatActivity() {

    private lateinit var etStudentId: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var cbRemember: MaterialCheckBox
    private lateinit var btnImport: MaterialButton
    private lateinit var progress: View
    private lateinit var tvStatus: TextView

    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        // 实验课系统登录会先后下发两个 Cookie，交给系统 CookieManager 统一携带
        if (java.net.CookieHandler.getDefault() == null) {
            java.net.CookieHandler.setDefault(java.net.CookieManager())
        }
        setContentView(R.layout.activity_exp_import)
        EdgeToEdge.setup(this, findViewById(R.id.root))

        etStudentId = findViewById(R.id.etStudentId)
        etPassword = findViewById(R.id.etPassword)
        cbRemember = findViewById(R.id.cbRemember)
        btnImport = findViewById(R.id.btnImport)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        etStudentId.setText(prefs.getString(KEY_ID, ""))
        etPassword.setText(prefs.getString(KEY_PWD, ""))
        cbRemember.isChecked = prefs.getBoolean(KEY_REMEMBER, false)

        btnImport.setOnClickListener { startImport() }
    }

    private fun startImport() {
        if (running) return
        val sid = etStudentId.text?.toString()?.trim().orEmpty()
        val pwd = etPassword.text?.toString() ?: ""
        if (sid.isEmpty()) {
            tvStatus.text = "请输入学号"
            return
        }
        if (pwd.isEmpty()) {
            tvStatus.text = "请输入密码"
            return
        }
        running = true
        btnImport.isEnabled = false
        progress.isVisible = true
        tvStatus.text = "正在登录实验课系统…"

        thread {
            try {
                val sessions = ExpImporter.fetchSessions(sid, pwd)
                runOnUiThread { tvStatus.text = "登录成功，查到 ${sessions.size} 条实验，正在合并到课表…" }
                val repo = cn.hnist.timetable.data.TimetableRepo(this)
                val timetable = repo.load()
                    ?: throw ExpImporter.ExpImportException("请先在主页导入课表，再导入实验课")
                val merged = ExpImporter.merge(timetable, sessions)
                repo.save(merged)
                // 实验系统自带的官方周次是权威值：把按它校准出的开学日期存为本机权威值，
                // 之后重新导入主课表时，页面周次计数即使漂移也不会再冲掉它
                repo.expCalibratedStart = merged.startDate
                // 实验系统的官方周次若和本地开学日期对不上（本地被带偏过），这里已自动校正
                val corrected = merged.startDate != timetable.startDate
                if (cbRemember.isChecked) saveCredentials(sid, pwd) else clearCredentials()
                runOnUiThread {
                    progress.isVisible = false
                    val note = if (corrected) "\n检测到开学日期有偏差，已自动校正为 ${merged.startDate}。" else ""
                    MaterialAlertDialogBuilder(this)
                        .setTitle("导入完成")
                        .setMessage("已把 ${sessions.size} 条实验课合并到课表，返回主页即可看到。$note")
                        .setCancelable(false)
                        .setPositiveButton("好的") { _, _ -> finish() }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.isVisible = false
                    tvStatus.text = e.message ?: "导入失败，请稍后重试"
                }
            } finally {
                runOnUiThread {
                    running = false
                    btnImport.isEnabled = true
                }
            }
        }
    }

    private fun saveCredentials(sid: String, pwd: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, sid)
            .putString(KEY_PWD, pwd)
            .putBoolean(KEY_REMEMBER, true)
            .apply()
    }

    private fun clearCredentials() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, etStudentId.text?.toString()?.trim().orEmpty())
            .remove(KEY_PWD)
            .putBoolean(KEY_REMEMBER, false)
            .apply()
    }

    companion object {
        private const val PREFS = "exp_import"
        private const val KEY_ID = "student_id"
        private const val KEY_PWD = "password"
        private const val KEY_REMEMBER = "remember_password"
    }
}
