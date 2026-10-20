package cn.hnist.timetable

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.ui.EdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 内置的学校网站浏览器。针对学校网站常见的问题做了处理：
 *  - 手机 / 电脑模式（User-Agent）一键切换，网页打不开时提示换模式；
 *  - 证书有问题时询问是否继续（按主机记住本次选择）；
 *  - window.open 弹窗改为在当前页打开；允许 http 页面、混合内容、跨站 Cookie；
 *  - 文件上传/下载、微信/电话等外部链接交给系统处理。
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val QUICK_LINKS = listOf(
            "学校官网" to "https://www.hnist.edu.cn/",
            "统一认证（校内）" to "https://sso.hnist.edu.cn/portal/login.html",
            "统一认证（校外）" to "https://vpn.hnist.cn/",
            "教务系统" to "https://bkjw.hnist.cn/",
            "本学期课表" to "https://bkjw.hnist.cn/student/personalSenate/giveLessonInfo/thisSemesterSchedule/weekLySchedule",
            "信息中心" to "https://ic.hnist.cn/",
            "实验课系统" to "http://211.69.224.126:7100/BaseInfo/Index/"
        )

        /** 电脑模式下强制使用的排版宽度（CSS 像素），和普通笔记本浏览器差不多。 */
        private const val DESKTOP_VIEWPORT_WIDTH = 1280
    }

    private lateinit var repo: TimetableRepo
    private lateinit var toolbar: MaterialToolbar
    private lateinit var etUrl: EditText
    private lateinit var btnMode: MaterialButton
    private lateinit var quickLinks: LinearLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var webView: WebView
    private lateinit var errorPanel: View
    private lateinit var tvErrorDetail: TextView
    private lateinit var btnSwitchMode: MaterialButton

    private var desktopMode = false
    private var mobileUa = ""
    private val sslTrustedHosts = HashSet<String>()
    private var lastFailedUrl: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cn.hnist.timetable.ui.CrashGuard.install(this)
        setContentView(R.layout.activity_browser)
        repo = TimetableRepo(this)

        toolbar = findViewById(R.id.toolbar)
        etUrl = findViewById(R.id.etUrl)
        btnMode = findViewById(R.id.btnMode)
        quickLinks = findViewById(R.id.quickLinks)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)
        errorPanel = findViewById(R.id.errorPanel)
        tvErrorDetail = findViewById(R.id.tvErrorDetail)
        btnSwitchMode = findViewById(R.id.btnSwitchMode)
        EdgeToEdge.setup(this, findViewById(R.id.root))

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        setupWebView()
        desktopMode = repo.browserDesktopMode
        applyMode(reload = false)

        btnMode.setOnClickListener { toggleMode() }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateTo(etUrl.text.toString())
                true
            } else false
        }
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { retry() }
        btnSwitchMode.setOnClickListener { toggleMode() }
        findViewById<MaterialButton>(R.id.btnExternal).setOnClickListener { openExternal(lastFailedUrl ?: webView.url) }
        buildQuickLinks()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (errorPanel.isVisible && webView.canGoBack()) {
                    errorPanel.isVisible = false
                    webView.goBack()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val initial = intent.getStringExtra(EXTRA_URL) ?: QUICK_LINKS.first().second
        if (savedInstanceState == null) webView.loadUrl(initial) else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 按页面原始大小显示，不把整页缩小去适配屏幕；比屏幕宽就左右滑动
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            mobileUa = userAgentString
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                // 微信、电话、邮件、intent 等交给系统
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@BrowserActivity, "手机上没有能打开这个链接的应用", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                errorPanel.isVisible = false
                progress.isVisible = true
                progress.progress = 5
                if (url != null && !etUrl.hasFocus()) etUrl.setText(url)
                toolbar.subtitle = url?.let { Uri.parse(it).host }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                applyPageTweaks(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.isVisible = false
                if (url != null && !etUrl.hasFocus()) etUrl.setText(url)
                toolbar.subtitle = url?.let { Uri.parse(it).host }
                applyPageTweaks(view)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (error.errorCode == ERROR_UNKNOWN) return   // 多为被取消的加载
                lastFailedUrl = request.url.toString()
                showError("${error.description}（${describeError(error.errorCode)}）")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = Uri.parse(error.url).host ?: ""
                if (sslTrustedHosts.contains(host)) {
                    handler.proceed()
                    return
                }
                MaterialAlertDialogBuilder(this@BrowserActivity)
                    .setTitle("证书有问题")
                    .setMessage(
                        "$host 的安全证书${describeSsl(error)}。\n\n" +
                            "学校网站经常是证书过期或没配好中间证书，普通浏览器会直接拒绝打开。" +
                            "如果你确认是学校网站，可以继续；在不熟悉的网络下继续访问有被劫持的风险。"
                    )
                    .setNegativeButton("取消") { _, _ ->
                        handler.cancel()
                        showError("已取消访问证书有问题的网站")
                    }
                    .setPositiveButton("继续访问") { _, _ ->
                        sslTrustedHosts += host
                        handler.proceed()
                    }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                toolbar.title = title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "湖理网站"
            }

            /** 学校门户点“教务系统”是 window.open 开新窗口，这里改成在当前页打开。 */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val probe = WebView(this@BrowserActivity)
                probe.settings.javaScriptEnabled = true
                probe.settings.userAgentString = view.settings.userAgentString
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        webView.loadUrl(request.url.toString())
                        v.post { v.destroy() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = probe
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            Toast.makeText(this, "已交给系统浏览器下载", Toast.LENGTH_SHORT).show()
            openExternal(url)
        }
    }

    private fun describeError(code: Int): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP -> "找不到服务器，可能是网址错误或 DNS 问题"
        WebViewClient.ERROR_CONNECT -> "连接不上服务器"
        WebViewClient.ERROR_TIMEOUT -> "连接超时"
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "安全连接握手失败，服务器可能只支持很旧的加密协议"
        WebViewClient.ERROR_REDIRECT_LOOP -> "页面在反复跳转"
        WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "不支持的链接类型"
        WebViewClient.ERROR_BAD_URL -> "网址格式不对"
        else -> "错误码 $code"
    }

    private fun describeSsl(error: SslError): String = when (error.primaryError) {
        SslError.SSL_EXPIRED -> "已过期"
        SslError.SSL_IDMISMATCH -> "与域名不匹配"
        SslError.SSL_UNTRUSTED -> "不被信任（自签名或缺少中间证书）"
        SslError.SSL_NOTYETVALID -> "尚未生效"
        SslError.SSL_DATE_INVALID -> "日期无效"
        else -> "无效"
    }

    private fun showError(detail: String) {
        progress.isVisible = false
        tvErrorDetail.text = detail
        btnSwitchMode.text = if (desktopMode) "切换到手机模式试试" else "切换到电脑模式试试"
        errorPanel.isVisible = true
    }

    private fun retry() {
        errorPanel.isVisible = false
        val url = lastFailedUrl ?: webView.url
        if (url != null) webView.loadUrl(url) else webView.reload()
    }

    // ---------- 模式切换 ----------

    /**
     * 每个页面加载后做的调整：
     *  - 电脑模式：把视口固定成电脑宽度，网站就按电脑版排版（不再“自适应”）；
     *  - 两种模式：重新打开横向滚动。不少手机页面用 CSS 把横向溢出藏掉了
     *    （overflow-x:hidden），超宽的表格、图片会被直接裁掉又滑不动。
     *    这里一方面强制 html/body 允许横向滚动，另一方面扫描页面里
     *    “内容比自己宽、却隐藏了溢出”的容器，把它们改成可以左右滑动。
     */
    private fun applyPageTweaks(view: WebView) {
        val desktopPart = if (desktopMode) {
            """
              var m = document.querySelector('meta[name="viewport"]');
              if (!m) { m = document.createElement('meta'); m.name = 'viewport'; head.appendChild(m); }
              var want = 'width=$DESKTOP_VIEWPORT_WIDTH';
              if (m.getAttribute('content') !== want) m.setAttribute('content', want);
            """
        } else ""
        val js = """
            (function () {
              var head = document.head || document.getElementsByTagName('head')[0];
              if (!head) return;
              $desktopPart
              if (!document.getElementById('hnist-scroll-fix')) {
                var s = document.createElement('style');
                s.id = 'hnist-scroll-fix';
                s.textContent = 'html,body{overflow-x:auto !important;}';
                head.appendChild(s);
              }
              function unclip() {
                var els = document.body ? document.body.getElementsByTagName('*') : [];
                for (var i = 0; i < els.length; i++) {
                  var e = els[i];
                  if (e.scrollWidth <= e.clientWidth + 2) continue;
                  var cs = window.getComputedStyle(e);
                  if (cs.overflowX === 'hidden' || cs.overflowX === 'clip') {
                    e.style.setProperty('overflow-x', 'auto', 'important');
                    e.style.setProperty('-webkit-overflow-scrolling', 'touch');
                  }
                }
              }
              unclip();
              setTimeout(unclip, 1500);
              if (!window.__hnistUnclipBound) {
                window.__hnistUnclipBound = true;
                window.addEventListener('resize', function () { setTimeout(unclip, 300); });
              }
            })()
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun applyMode(reload: Boolean) {
        webView.settings.userAgentString = if (desktopMode) DESKTOP_UA else mobileUa
        btnMode.text = if (desktopMode) "电脑" else "手机"
        btnMode.setIconResource(if (desktopMode) R.drawable.ic_desktop else R.drawable.ic_phone)
        repo.browserDesktopMode = desktopMode
        if (reload) {
            errorPanel.isVisible = false
            val url = lastFailedUrl ?: webView.url
            if (url != null) webView.loadUrl(url) else webView.reload()
        }
    }

    private fun toggleMode() {
        desktopMode = !desktopMode
        Toast.makeText(this, if (desktopMode) "已切换到电脑模式，重新加载中" else "已切换到手机模式，重新加载中", Toast.LENGTH_SHORT).show()
        applyMode(reload = true)
    }

    // ---------- 导航 ----------

    private fun navigateTo(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            else -> "https://$text"
        }
        hideKeyboard()
        etUrl.clearFocus()
        lastFailedUrl = null
        webView.loadUrl(url)
    }

    private fun buildQuickLinks() {
        quickLinks.removeAllViews()
        val density = resources.displayMetrics.density
        QUICK_LINKS.forEach { (name, url) ->
            val chip = TextView(this)
            chip.text = name
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            chip.gravity = Gravity.CENTER
            chip.setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            chip.background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(ContextCompat.getColor(this@BrowserActivity, R.color.primary_container))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8 * density).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener {
                lastFailedUrl = null
                webView.loadUrl(url)
            }
            quickLinks.addView(chip)
        }
    }

    private fun openExternal(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> retry()
            R.id.action_external -> openExternal(webView.url)
            R.id.action_copy -> {
                val url = webView.url ?: return true
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
            }
            R.id.action_clear -> MaterialAlertDialogBuilder(this)
                .setTitle("清除网站数据")
                .setMessage("会清掉所有网站的登录状态、缓存和本地数据（包括课表导入用的登录）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    webView.clearCache(true)
                    webView.clearHistory()
                    sslTrustedHosts.clear()
                    Toast.makeText(this, "已清除，正在重新加载", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
                .show()
            R.id.action_help -> MaterialAlertDialogBuilder(this)
                .setTitle("网页打不开怎么办")
                .setMessage(
                    "1. 点右上角刷新，或换个网络（流量 / Wi‑Fi）再试。\n\n" +
                        "2. 切换手机 / 电脑模式：有些学校页面只有电脑版能正常用，或者手机版跳转有问题。\n\n" +
                        "3. 提示“证书有问题”时，确认是学校网站就点“继续访问”。\n\n" +
                        "4. 登录后来回跳转、一直让你重新登录：菜单里“清除网站数据”后重试。\n\n" +
                        "5. 页面上点了没反应：多半是弹窗，这里已经改为在当前页打开；仍不行可“用系统浏览器打开”。"
                )
                .setPositiveButton("知道了", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
