package cn.hnist.timetable

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.importer.ExpImporter
import cn.hnist.timetable.importer.UrpParser
import cn.hnist.timetable.reminder.Reminders
import cn.hnist.timetable.ui.EdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * 一键导入：在 WebView 里走完「统一身份认证 → 门户 → 教务系统」的跳转链，
 * 进入教务系统后轮询课表 JSON 接口，把结果交给 [UrpParser]。
 *
 * 导入期间网页占满屏幕，遇到验证码、绑定手机等页面用户可以直接手动处理，处理完自动继续。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val SSO_HOST = "sso.hnist.edu.cn"
        private const val JW_HOST = "bkjw.hnist.cn"
        private const val JW_APP_SN = "APP025"
        private const val CURRICULUM_URL =
            "https://$JW_HOST/student/courseSelect/thisSemesterCurriculum/index"
        private const val SCHEDULE_API =
            "https://$JW_HOST/student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback"
        private const val MAX_HOPS = 60
        private const val MAX_EXTRACT_ATTEMPTS = 15      // 2 秒一次，约 30 秒
        private const val EXTRACT_RETRY_MS = 2_000L
        private const val EXTRACT_WATCHDOG_MS = 8_000L   // JS 没有回调时的兜底
        private const val LONG_WAIT_MS = 45_000L
        private const val IMPORT_DEADLINE_MS = 180_000L  // 超过 3 分钟弹框让用户决定

        private fun acctSelectUrl(): String =
            "https://$SSO_HOST/sso/acctSelect?safetyCode=${UUID.randomUUID()}+&appSn=$JW_APP_SN&appRedirectUrl=&appUrl="
    }

    private lateinit var repo: TimetableRepo
    private lateinit var formScroll: View
    private lateinit var importPanel: View
    private lateinit var tilUser: TextInputLayout
    private lateinit var tilPass: TextInputLayout
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var cbRemember: MaterialCheckBox
    private lateinit var btnImport: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView

    private val handler = Handler(Looper.getMainLooper())
    private var importing = false
    private var finished = false
    private var autoLoginAttempted = false
    private var mainFrameFailed = false
    private var hops = 0
    private var extractAttempts = 0
    private var extractPolling = false
    private var lastExtractError = ""
    private var username = ""
    private var password = ""
    private var deadlineDialog: AlertDialog? = null

    private val longWaitRunnable = Runnable {
        if (importing && !finished && !extractPolling) {
            setStatus(
                "等待时间较长。如果下方网页停在登录页，请在网页里手动登录；" +
                    "如果网页已经显示课表却没有继续，请点“重新导入”。",
                warn = true
            )
            btnRetry.isVisible = true
        }
    }

    /** 到了最长等待时间还没结果：弹框让用户决定，避免无限转圈。 */
    private val deadlineRunnable = Runnable {
        if (importing && !finished) showDeadlineDialog()
    }

    private val extractRunnable = Runnable { runExtractAttempt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cn.hnist.timetable.ui.CrashGuard.install(this)
        setContentView(R.layout.activity_login)
        repo = TimetableRepo(this)
        EdgeToEdge.setup(this, findViewById(R.id.root))

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        formScroll = findViewById(R.id.formScroll)
        importPanel = findViewById(R.id.importPanel)
        tilUser = findViewById(R.id.tilUser)
        tilPass = findViewById(R.id.tilPass)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        cbRemember = findViewById(R.id.cbRemember)
        btnImport = findViewById(R.id.btnImport)
        btnCancel = findViewById(R.id.btnCancel)
        btnRetry = findViewById(R.id.btnRetry)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        webView = findViewById(R.id.webView)

        etUser.setText(repo.lastStudentId)
        cbRemember.isChecked = repo.rememberPassword
        if (repo.rememberPassword) etPass.setText(repo.savedPassword)

        setupWebView()
        btnImport.setOnClickListener { startImport() }
        etPass.setOnEditorActionListener { _, _, _ -> startImport(); true }
        btnCancel.setOnClickListener { showForm() }
        btnRetry.setOnClickListener { startImport() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (importPanel.isVisible) showForm() else finish()
            }
        })
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 教务系统未登录时会经过 http://bkjw.hnist.cn/gotoLogin 这类明文跳转，
                // 服务器随后又 301 回 https，这里直接升级协议，避免被明文策略拦截。
                val target = request.url
                val host = target.host.orEmpty()
                if (target.scheme == "http" && (host.endsWith("hnist.cn") || host.endsWith("hnist.edu.cn"))) {
                    view.loadUrl(target.buildUpon().scheme("https").build().toString())
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (mainFrameFailed || url == null) return
                route(view, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !importing || finished) return
                // 只有真正的网络类错误才算失败；被我们改写地址导致的取消加载等一律忽略
                val networkErrors = setOf(
                    ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO,
                    ERROR_PROXY_AUTHENTICATION, ERROR_FAILED_SSL_HANDSHAKE
                )
                if (error.errorCode in networkErrors) {
                    mainFrameFailed = true
                    fail(
                        "网页加载失败：${error.description}。校外请先在浏览器打开 https://vpn.hnist.cn " +
                            "（学校统一认证外网入口）登录一次试试；点“重新导入”可重试。"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
                if (importing && !finished && url.contains(SSO_HOST)) {
                    setStatus("登录提示：$message。请核对学号密码，或在下方网页手动登录（如出现验证码请填写）", warn = true)
                }
                result.confirm()
                return true
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onSchedule(json: String, navText: String, studentName: String) {
            runOnUiThread { onScheduleReceived(json, navText, studentName) }
        }

        @JavascriptInterface
        fun onNotReady(reason: String) {
            runOnUiThread { onExtractNotReady(reason) }
        }
    }

    // ---------- 界面状态 ----------

    private fun showForm() {
        stopImport()
        importPanel.isVisible = false
        formScroll.isVisible = true
        btnImport.isEnabled = true
    }

    private fun showImportPanel() {
        formScroll.isVisible = false
        importPanel.isVisible = true
        progress.isVisible = true
        btnRetry.isVisible = false
        btnCancel.text = "取消导入"
    }

    private fun stopImport() {
        importing = false
        extractPolling = false
        handler.removeCallbacksAndMessages(null)
        deadlineDialog?.dismiss()
        deadlineDialog = null
        webView.stopLoading()
    }

    private fun showDeadlineDialog() {
        deadlineDialog?.dismiss()
        deadlineDialog = MaterialAlertDialogBuilder(this)
            .setTitle("导入还没有完成")
            .setMessage(
                "已经等了 3 分钟。\n\n" +
                    "· 如果你正在网页里手动登录或填验证码，点“继续等待”；\n" +
                    "· 如果网页卡住或已经显示课表却没有反应，点“重新导入”；\n" +
                    "· 也可以直接取消。"
            )
            .setPositiveButton("继续等待") { _, _ ->
                handler.postDelayed(deadlineRunnable, IMPORT_DEADLINE_MS)
            }
            .setNeutralButton("重新导入") { _, _ -> startImport() }
            .setNegativeButton("取消导入") { _, _ -> showForm() }
            .setOnCancelListener { handler.postDelayed(deadlineRunnable, IMPORT_DEADLINE_MS) }
            .show()
    }

    // ---------- 导入流程 ----------

    private fun startImport() {
        username = etUser.text?.toString()?.trim().orEmpty()
        password = etPass.text?.toString().orEmpty()
        tilUser.error = null
        tilPass.error = null
        if (username.isEmpty()) {
            showForm()
            tilUser.error = "请输入学号"
            return
        }
        if (password.isEmpty()) {
            showForm()
            tilPass.error = "请输入密码"
            return
        }
        hideKeyboard()

        repo.lastStudentId = username
        repo.rememberPassword = cbRemember.isChecked
        repo.savedPassword = if (cbRemember.isChecked) password else ""

        stopImport()
        importing = true
        finished = false
        autoLoginAttempted = false
        mainFrameFailed = false
        hops = 0
        extractAttempts = 0
        lastExtractError = ""

        showImportPanel()
        setStatus("正在连接教务系统…")

        // 清掉旧会话，保证导入的是当前输入账号的课表
        CookieManager.getInstance().removeAllCookies {
            webView.clearHistory()
            webView.loadUrl(CURRICULUM_URL)
        }
        handler.postDelayed(longWaitRunnable, LONG_WAIT_MS)
        handler.postDelayed(deadlineRunnable, IMPORT_DEADLINE_MS)
    }

    /** 根据当前页面地址决定下一步。 */
    private fun route(view: WebView, url: String) {
        if (!importing || finished) return
        hops++
        if (hops > MAX_HOPS) {
            fail("页面跳转次数过多，请点“重新导入”重试")
            return
        }
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val path = uri.path ?: ""

        when {
            host == SSO_HOST && path.contains("login") -> {
                if (!autoLoginAttempted) {
                    autoLoginAttempted = true
                    setStatus("正在登录统一身份认证…")
                    injectLogin(view)
                } else {
                    setStatus("自动登录未完成，请在下方网页手动登录（如出现验证码请填写）", warn = true)
                }
            }

            host == SSO_HOST && (path.contains("main") || path.trimEnd('/') == "/portal") -> {
                setStatus("登录成功，正在进入教务系统…")
                view.loadUrl(acctSelectUrl())
            }

            host == SSO_HOST -> {
                setStatus("请在下方网页完成当前步骤，完成后会自动继续导入", warn = true)
            }

            host == JW_HOST && path.contains("thisSemesterCurriculum") -> startExtractPolling()

            host == JW_HOST && path.lowercase().contains("login") -> {
                setStatus("正在跳转到统一身份认证…")
            }

            host == JW_HOST -> {
                setStatus("正在打开课表页面…")
                view.loadUrl(CURRICULUM_URL)
            }
        }
    }

    private fun injectLogin(view: WebView) {
        val js = """
            (function () {
              try {
                var u = document.getElementById('username');
                var p = document.getElementById('password');
                var b = document.getElementById('fromsubmit');
                if (!u || !p || !b) { return 'no-form'; }
                var tab = document.querySelector('.tab-btn[data-tab="account"]');
                if (tab && !tab.classList.contains('active')) { tab.click(); }
                var adv = document.querySelector('.advbox .closebtn');
                if (adv) { adv.click(); }
                u.value = ${JSONObject.quote(username)};
                p.value = ${JSONObject.quote(password)};
                u.dispatchEvent(new Event('input', { bubbles: true }));
                p.dispatchEvent(new Event('input', { bubbles: true }));
                setTimeout(function () { b.click(); }, 300);
                return 'ok';
              } catch (e) { return 'err:' + e; }
            })()
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            if (result != null && result.contains("no-form")) {
                // 表单还没渲染出来（多半是被改写的跳转触发的假加载），保留自动登录机会
                autoLoginAttempted = false
                setStatus("正在等待登录页面…")
                return@evaluateJavascript
            }
            // 统一认证页用自绘弹窗提示“密码错误/账号不存在”，不会触发 onJsAlert，
            // 若几秒后仍停留在登录页，就提示用户看网页里的信息并手动处理。
            handler.postDelayed({
                val stillOnLogin = webView.url?.let { Uri.parse(it) }?.let {
                    it.host == SSO_HOST && (it.path ?: "").contains("login")
                } ?: false
                if (importing && !finished && stillOnLogin) {
                    setStatus("自动登录未成功，请查看下方网页的提示（如账号或密码错误、需要验证码），手动登录后会自动继续", warn = true)
                }
            }, 8000)
        }
    }

    // ---------- 取课表：进入教务系统后轮询接口，不依赖页面文字 ----------

    private fun startExtractPolling() {
        if (!importing || finished || extractPolling) return
        extractPolling = true
        extractAttempts = 0
        handler.removeCallbacks(extractRunnable)
        handler.post(extractRunnable)
    }

    private fun runExtractAttempt() {
        if (!importing || finished) {
            extractPolling = false
            return
        }
        val currentHost = webView.url?.let { Uri.parse(it).host }
        if (currentHost != JW_HOST) {
            // 页面已经离开教务系统（例如会话失效被踢回登录页），交回给 route 处理
            extractPolling = false
            return
        }
        extractAttempts++
        if (extractAttempts > MAX_EXTRACT_ATTEMPTS) {
            extractPolling = false
            fail(
                "已进入教务系统，但课表接口连续 $MAX_EXTRACT_ATTEMPTS 次没有返回数据" +
                    (if (lastExtractError.isNotBlank()) "（最近一次：$lastExtractError）" else "") +
                    "。请点“重新导入”重试；若仍失败，请在电脑浏览器确认教务系统能正常显示课表。"
            )
            return
        }
        setStatus(if (extractAttempts == 1) "已进入教务系统，正在获取课表…" else "正在获取课表…（第 $extractAttempts 次尝试）")
        injectExtract(webView)
        // JS 万一没有任何回调（页面正在跳转等），到点再试一次
        handler.removeCallbacks(extractRunnable)
        handler.postDelayed(extractRunnable, EXTRACT_WATCHDOG_MS)
    }

    private fun injectExtract(view: WebView) {
        val js = """
            (function () {
              try {
                fetch('$SCHEDULE_API', {
                  method: 'POST',
                  headers: { 'X-Requested-With': 'XMLHttpRequest' },
                  credentials: 'include'
                }).then(function (r) {
                  if (!r.ok) { throw new Error('HTTP ' + r.status); }
                  return r.text();
                }).then(function (text) {
                  var t = text.trim();
                  if (t.charAt(0) !== '{' || t.indexOf('xkxx') < 0) {
                    AndroidBridge.onNotReady('接口没有返回课表数据，可能尚未登录成功');
                    return;
                  }
                  // 顶栏的“第 N 周 星期 X”和“欢迎您，姓名”在手机宽度下会被隐藏，
                  // 所以用 textContent（包含隐藏元素）而不是 innerText
                  var body = document.body || document.documentElement;
                  var visible = body ? (body.innerText || '') : '';
                  var all = body ? (body.textContent || '') : '';
                  var navRe = /(\d{4}-\d{4}[^第\n]{0,20})?第\s*\d+\s*周\s*星期[一二三四五六日天]/;
                  var m = navRe.exec(visible) || navRe.exec(all);
                  var nav = m ? m[0].replace(/\s+/g, ' ') : '';
                  var nm = /欢迎您[，,:：]?\s*([^\s，,<]{1,20})/.exec(all);
                  var name = nm ? nm[1] : '';
                  AndroidBridge.onSchedule(text, nav, name);
                }).catch(function (e) {
                  AndroidBridge.onNotReady('' + (e && e.message ? e.message : e));
                });
              } catch (e) {
                AndroidBridge.onNotReady('' + e);
              }
            })()
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun onExtractNotReady(reason: String) {
        if (!importing || finished) return
        lastExtractError = reason
        handler.removeCallbacks(extractRunnable)
        handler.postDelayed(extractRunnable, EXTRACT_RETRY_MS)
    }

    private fun onScheduleReceived(json: String, navText: String, studentName: String) {
        if (finished) return
        try {
            val timetable = UrpParser.parse(json, navText, studentName, LocalDate.now())
            // 手动添加的课程和实验课不属于教务系统数据，重新导入课表时保留它们
            val preserved = repo.load()?.courses?.filter {
                it.courseNumber == ExpImporter.MANUAL_COURSE_NUMBER || it.courseNumber == ExpImporter.EXP_COURSE_NUMBER
            } ?: emptyList()
            val finalTimetable = if (preserved.isEmpty()) timetable
            else timetable.copy(
                courses = (timetable.courses + preserved).sortedWith(compareBy({ it.day }, { it.startNode }))
            )
            repo.save(finalTimetable)
            finished = true
            stopImport()
            progress.isVisible = false
            Reminders.reschedule(this)
            val summary = "导入成功：${finalTimetable.courses.size} 条课程安排" +
                if (timetable.unscheduled.isNotEmpty()) "，另有 ${timetable.unscheduled.size} 门未排课" else ""
            setStatus(summary)
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
            handler.postDelayed({ finish() }, 600)
        } catch (e: UrpParser.ParseException) {
            fail(e.message ?: "课表解析失败")
        } catch (e: Exception) {
            fail("课表解析失败：${e.message}")
        }
    }

    private fun fail(message: String) {
        importing = false
        extractPolling = false
        handler.removeCallbacksAndMessages(null)
        deadlineDialog?.dismiss()
        deadlineDialog = null
        progress.isVisible = false
        btnRetry.isVisible = true
        btnCancel.text = "返回修改账号"
        setStatus(message, warn = true)
    }

    private fun setStatus(text: String, warn: Boolean = false) {
        tvStatus.text = text
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (warn) R.color.warn else R.color.text_secondary)
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        deadlineDialog?.dismiss()
        deadlineDialog = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
