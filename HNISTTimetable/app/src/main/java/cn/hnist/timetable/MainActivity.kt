package cn.hnist.timetable

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.importer.ExpImporter
import cn.hnist.timetable.model.Course
import cn.hnist.timetable.model.Timetable
import cn.hnist.timetable.reminder.Reminders
import cn.hnist.timetable.todo.TodoItem
import cn.hnist.timetable.todo.TodoRepo
import com.google.android.material.snackbar.Snackbar
import cn.hnist.timetable.ui.CrashGuard
import cn.hnist.timetable.ui.EdgeToEdge
import cn.hnist.timetable.ui.TimetableBackground
import cn.hnist.timetable.ui.TimetableView
import cn.hnist.timetable.ui.WeekSwipeScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var repo: TimetableRepo
    private lateinit var toolbar: MaterialToolbar
    private lateinit var weekScroll: HorizontalScrollView
    private lateinit var weekBar: LinearLayout
    private lateinit var dateHeader: LinearLayout
    private lateinit var timetableView: TimetableView
    private lateinit var scroll: WeekSwipeScrollView
    private lateinit var unscheduledBox: View
    private lateinit var unscheduledText: TextView
    private lateinit var todoBox: View
    private lateinit var todoBoxTitle: TextView
    private lateinit var todoRows: LinearLayout
    private lateinit var emptyState: View
    private lateinit var contentRoot: View

    private var timetable: Timetable? = null
    private var selectedWeek = 1
    private var showOtherMenuItem: MenuItem? = null
    private var clearBgMenuItem: MenuItem? = null
    private var overlayMenuItem: MenuItem? = null
    private var bgBitmap: android.graphics.Bitmap? = null
    private val todoRepo by lazy { TodoRepo(this) }

    private val pickBackground =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = TimetableBackground.saveFrom(this, uri)
            if (!ok) {
                Toast.makeText(this, "这张图片读取失败，换一张试试", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            applyBackground()
            Toast.makeText(this, "背景已更换", Toast.LENGTH_SHORT).show()
        }

    private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        try {
            initMain()
        } catch (e: Throwable) {
            crashed = true
            CrashGuard.save(this, e)
            showFatal(e)
        }
    }

    private var crashed = false

    private fun initMain() {
        setContentView(R.layout.activity_main)
        repo = TimetableRepo(this)

        toolbar = findViewById(R.id.toolbar)
        weekScroll = findViewById(R.id.weekScroll)
        weekBar = findViewById(R.id.weekBar)
        dateHeader = findViewById(R.id.dateHeader)
        timetableView = findViewById(R.id.timetable)
        scroll = findViewById(R.id.scroll)
        unscheduledBox = findViewById(R.id.unscheduledBox)
        unscheduledText = findViewById(R.id.unscheduledText)
        todoBox = findViewById(R.id.todoBox)
        todoBoxTitle = findViewById(R.id.todoBoxTitle)
        todoRows = findViewById(R.id.todoRows)
        emptyState = findViewById(R.id.emptyState)
        contentRoot = findViewById(R.id.contentRoot)

        setSupportActionBar(toolbar)
        toolbar.title = "湖理课表"
        EdgeToEdge.setup(this, findViewById(R.id.root))

        findViewById<MaterialButton>(R.id.btnGoImport).setOnClickListener { openImport() }
        findViewById<MaterialButton>(R.id.btnSample).apply {
            isVisible = DebugHooks.ENABLED
            setOnClickListener { if (DebugHooks.installSample(this@MainActivity, repo)) reload() }
        }
        timetableView.onCourseClick = { showCourseDetail(it) }
        timetableView.onEmptyClick = { day, node -> showAddCourseDialog(day, node) }
        applyBackground()

        // 左右滑动切换周次：课表区域用自定义 ScrollView 识别，星期表头用手势检测器
        scroll.onSwipe = { direction -> changeWeek(direction) }
        val headerGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) > dp(40f) && kotlin.math.abs(dx) > kotlin.math.abs(e2.y - e1.y)) {
                    changeWeek(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        dateHeader.setOnTouchListener { v, event ->
            headerGesture.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    /** 切换到相邻周，并让内容从滑动方向轻轻滑入。 */
    private fun changeWeek(direction: Int) {
        val data = timetable ?: return
        val target = (selectedWeek + direction).coerceIn(1, data.totalWeeks)
        if (target == selectedWeek) return
        selectedWeek = target
        render()
        val startOffset = direction * dp(48f).toFloat()
        listOf<View>(timetableView, dateHeader).forEach { v ->
            v.animate().cancel()
            v.translationX = startOffset
            v.alpha = 0.55f
            v.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (crashed) return
        try {
            reload()
            Reminders.reschedule(this)
            Reminders.rescheduleTodos(this)
            uiHandler.removeCallbacks(minuteTick)
            uiHandler.postDelayed(minuteTick, 60_000)
        } catch (e: Throwable) {
            crashed = true
            CrashGuard.save(this, e)
            showFatal(e)
        }
    }

    /** 启动崩溃后的兜底界面：把错误报告展示出来供分享，也可以清空数据自救。 */
    private fun showFatal(e: Throwable) {
        val report = CrashGuard.lastReport(this)
            ?: StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val density = resources.displayMetrics.density
        fun d(v: Float): Int = (v * density + 0.5f).toInt()

        val scroll = androidx.core.widget.NestedScrollView(this)
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(20f), d(40f), d(20f), d(32f))
        }
        val title = android.widget.TextView(this).apply {
            text = "软件启动出错了"
            textSize = 20f
            setTextColor(0xFF1A1A1A.toInt())
        }
        val tip = android.widget.TextView(this).apply {
            text = "抱歉！错误已经记录下来。点“分享错误报告”把内容发给开发者即可修复；也可以先试试“清空数据”（会删除已导入的课表、待办和背景）。"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, d(10f), 0, d(16f))
        }
        val body = android.widget.TextView(this).apply {
            text = report
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF444444.toInt())
            setTextIsSelectable(true)
        }
        val share = MaterialButton(this).apply {
            text = "分享错误报告"
            setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "湖理课表错误报告")
                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                }
                startActivity(android.content.Intent.createChooser(intent, "分享错误报告"))
            }
        }
        val reset = MaterialButton(this).apply {
            text = "清空数据并重启应用"
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEEEEEE.toInt())
            setTextColor(0xFFB71C1C.toInt())
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("清空数据")
                    .setMessage("将删除导入的课表、待办和背景照片（无法恢复），然后重新开始。确定吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空") { _, _ ->
                        TimetableRepo(this@MainActivity).clear()
                        TodoRepo(this@MainActivity).save(emptyList())
                        TimetableBackground.clear(this@MainActivity)
                        CrashGuard.clear(this@MainActivity)
                        recreate()
                    }
                    .show()
            }
        }
        col.addView(title)
        col.addView(tip)
        col.addView(share)
        col.addView(reset, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8f) })
        col.addView(body, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(16f) })
        scroll.addView(col)
        EdgeToEdge.setup(this, scroll)
        setContentView(scroll)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(minuteTick)
        super.onPause()
    }

    /** 课表背景：从私有目录读一张低分辨率位图铺在网格下面。 */
    private fun applyBackground() {
        val old = bgBitmap
        bgBitmap = TimetableBackground.loadBitmap(this)
        timetableView.setBackgroundPhoto(bgBitmap)
        timetableView.setOverlayAlpha(repo.backgroundOverlayAlpha)
        old?.recycle()
        clearBgMenuItem?.isVisible = bgBitmap != null
        overlayMenuItem?.isVisible = bgBitmap != null
    }

    /** 遮罩浓度滑杆：拖动实时预览，确定后保存。 */
    private fun showOverlayDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_overlay, null)
        val slider = view.findViewById<SeekBar>(R.id.slider)
        val tvValue = view.findViewById<TextView>(R.id.tvValue)
        slider.progress = (repo.backgroundOverlayAlpha * 100).toInt()
        tvValue.text = "遮罩 ${slider.progress}%"
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                tvValue.text = "遮罩 $value%"
                timetableView.setOverlayAlpha(value / 100f)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        MaterialAlertDialogBuilder(this)
            .setTitle("背景遮罩调节")
            .setView(view)
            .setNegativeButton("取消") { _, _ -> timetableView.setOverlayAlpha(repo.backgroundOverlayAlpha) }
            .setPositiveButton("保存") { _, _ ->
                repo.backgroundOverlayAlpha = slider.progress / 100f
            }
            .show()
    }

    /** 页面开着的时候，每分钟刷新一次“进行中的待办”，到开始时间会自动出现。 */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            if (timetable != null) renderStartedTodos()
            uiHandler.postDelayed(this, 60_000)
        }
    }

    /** Android 13+ 需要运行时通知权限；只在开着提醒且没问过的情况下问一次。 */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!repo.reminderEnabled || repo.notificationPermissionAsked || timetable == null) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        repo.notificationPermissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) Reminders.reschedule(this)
    }

    private fun reload() {
        val data = repo.load()
        timetable = data
        if (data == null) {
            emptyState.isVisible = true
            contentRoot.isVisible = false
            toolbar.subtitle = null
            return
        }
        emptyState.isVisible = false
        contentRoot.isVisible = true
        selectedWeek = realWeek(data).coerceIn(1, data.totalWeeks)
        render()
        maybeRequestNotificationPermission()
    }

    // ---------- 周次计算 ----------

    private fun startDate(data: Timetable): LocalDate =
        runCatching { LocalDate.parse(data.startDate) }.getOrElse { LocalDate.now() }

    /** 今天真实所处的周次（未裁剪，可能 <1 或 > 总周数）。 */
    private fun realWeek(data: Timetable): Int = TimetableRepo.weekOf(startDate(data), LocalDate.now())

    // ---------- 渲染 ----------

    private fun render() {
        val data = timetable ?: return
        val real = realWeek(data)
        val today = LocalDate.now()
        val todayColumn = if (selectedWeek == real) today.dayOfWeek.value else -1

        val weekLabel = when {
            real < 1 -> "还未开学"
            real > data.totalWeeks -> "本学期已结束"
            selectedWeek == real -> "第${selectedWeek}周（本周）"
            else -> "第${selectedWeek}周"
        }
        val who = if (data.studentName.isNotBlank()) " · ${data.studentName}" else ""
        toolbar.subtitle = "$weekLabel · ${data.semesterName}$who"

        buildWeekBar(data, real)
        buildDateHeader(data, today)
        timetableView.setData(
            courses = data.courses,
            nodeTimes = data.nodeTimes,
            week = selectedWeek,
            maxNode = data.maxNode,
            todayColumn = todayColumn,
            showOtherWeeks = repo.showOtherWeeks
        )

        if (data.unscheduled.isNotEmpty()) {
            unscheduledBox.isVisible = true
            unscheduledText.text = data.unscheduled.joinToString("\n") { u ->
                buildString {
                    append("• ").append(u.courseName)
                    if (u.teacher.isNotBlank()) append("  ").append(u.teacher)
                    if (u.weekDesc.isNotBlank()) append("  ").append(u.weekDesc)
                }
            }
        } else {
            unscheduledBox.isVisible = false
        }
        renderStartedTodos()
    }

    /** 课表下方：已经到开始时间、还没完成的待办，按紧急程度从高到低一条条列出，可直接勾选完成。 */
    private fun renderStartedTodos() {
        val items = todoRepo.startedPending(LocalDateTime.now())
        todoRows.removeAllViews()
        if (items.isEmpty()) {
            todoBox.isVisible = false
            return
        }
        todoBox.isVisible = true
        todoBoxTitle.text = "进行中的待办（${items.size}）"
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_todo, todoRows, false)
            TodoRowBinder.bind(
                row, item,
                onToggle = {
                    todoRepo.toggleDone(item)
                    Reminders.rescheduleTodos(this)
                    // 稍等一下再刷新，让勾选动画走完
                    todoRows.postDelayed({ renderStartedTodos() }, 250)
                },
                onClick = { startActivity(Intent(this, TodoActivity::class.java)) },
                onDelete = { confirmDeleteTodo(item) }
            )
            todoRows.addView(row)
        }
    }

    /** 长按删除主页下方的待办（含进行中和重复任务），可撤销。 */
    private fun confirmDeleteTodo(item: TodoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除待办")
            .setMessage("删除「${item.title}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                todoRepo.delete(item.id)
                Reminders.rescheduleTodos(this)
                renderStartedTodos()
                Snackbar.make(todoRows, "已删除「${item.title}」", Snackbar.LENGTH_LONG)
                    .setAction("撤销") {
                        todoRepo.update(item)
                        Reminders.rescheduleTodos(this)
                        renderStartedTodos()
                    }
                    .show()
            }
            .show()
    }

    private fun buildWeekBar(data: Timetable, realWeek: Int) {
        weekBar.removeAllViews()
        val size = dp(36f)
        val gap = dp(6f)
        var selectedView: View? = null
        for (w in 1..data.totalWeeks) {
            val tv = TextView(this)
            tv.text = w.toString()
            tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val selected = w == selectedWeek
            val isReal = w == realWeek
            tv.setTextColor(
                when {
                    selected -> Color.WHITE
                    isReal -> ContextCompat.getColor(this, R.color.primary)
                    else -> ContextCompat.getColor(this, R.color.text_secondary)
                }
            )
            tv.typeface = if (selected || isReal) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                when {
                    selected -> setColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                    isReal -> {
                        setColor(Color.WHITE)
                        setStroke(dp(1.5f), ContextCompat.getColor(this@MainActivity, R.color.primary))
                    }
                    else -> setColor(ContextCompat.getColor(this@MainActivity, R.color.chip_bg))
                }
            }
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            tv.layoutParams = lp
            tv.setOnClickListener {
                if (selectedWeek != w) {
                    selectedWeek = w
                    render()
                }
            }
            weekBar.addView(tv)
            if (selected) selectedView = tv
        }
        selectedView?.let { v ->
            weekScroll.post {
                val target = v.left - (weekScroll.width - v.width) / 2
                weekScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
            }
        }
    }

    private fun buildDateHeader(data: Timetable, today: LocalDate) {
        dateHeader.removeAllViews()
        val monday = startDate(data).plusWeeks((selectedWeek - 1).toLong())

        val spacer = TextView(this)
        spacer.text = "${monday.monthValue}\n月"
        spacer.gravity = Gravity.CENTER
        spacer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        spacer.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        spacer.layoutParams = LinearLayout.LayoutParams(timetableView.leftColumnWidth.toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        dateHeader.addView(spacer)

        val formatter = DateTimeFormatter.ofPattern("M/d")
        for (d in 1..7) {
            val date = monday.plusDays((d - 1).toLong())
            val isToday = date == today
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, dp(4f), 0, dp(4f))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(2f)
            lp.marginEnd = dp(2f)
            cell.layoutParams = lp

            val primary = ContextCompat.getColor(this, R.color.primary)
            val secondary = ContextCompat.getColor(this, R.color.text_secondary)

            val weekday = TextView(this)
            weekday.text = "周${weekdayNames[d - 1]}"
            weekday.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            weekday.setTextColor(if (isToday) primary else ContextCompat.getColor(this, R.color.text_primary))
            weekday.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            weekday.gravity = Gravity.CENTER

            val dateTv = TextView(this)
            dateTv.text = date.format(formatter)
            dateTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            dateTv.setTextColor(if (isToday) primary else secondary)
            dateTv.gravity = Gravity.CENTER

            if (isToday) {
                cell.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.primary_container))
                }
            }
            cell.addView(weekday)
            cell.addView(dateTv)
            dateHeader.addView(cell)
        }
    }

    // ---------- 课程详情 ----------

    /** 点课表空白格子：添加一门自定义课程，星期/节次预填为点中的位置。 */
    private fun showAddCourseDialog(day: Int, node: Int) {
        val data = timetable ?: return
        val view = layoutInflater.inflate(R.layout.dialog_course_add, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etTeacher = view.findViewById<TextInputEditText>(R.id.etTeacher)
        val etRoom = view.findViewById<TextInputEditText>(R.id.etRoom)
        val spDay = view.findViewById<Spinner>(R.id.spDay)
        val spStart = view.findViewById<Spinner>(R.id.spStart)
        val spCount = view.findViewById<Spinner>(R.id.spCount)
        val spWeeks = view.findViewById<Spinner>(R.id.spWeeks)

        fun spinner(sp: Spinner, items: List<String>, sel: Int) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(sel.coerceIn(0, items.size - 1))
        }
        spinner(spDay, weekdayNames.map { "周$it" }, day - 1)
        val maxNode = data.maxNode
        spinner(spStart, (1..maxNode).map { "$it" }, node - 1)
        spinner(spCount, (1..6).map { if (it == 1) "1 节" else "$it 节连上" }, 1)
        spinner(spWeeks, listOf("每周（整学期）", "单周", "双周", "仅本周（第${selectedWeek}周）"), 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("添加课程")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    etName.error = "写个课程名"
                    return@setOnClickListener
                }
                val weeks = when (spWeeks.selectedItemPosition) {
                    1 -> (1..data.totalWeeks).filter { it % 2 == 1 }
                    2 -> (1..data.totalWeeks).filter { it % 2 == 0 }
                    3 -> listOf(selectedWeek)
                    else -> (1..data.totalWeeks).toList()
                }
                val newCourse = Course(
                    courseName = name,
                    courseNumber = ExpImporter.MANUAL_COURSE_NUMBER,
                    sequence = "",
                    teacher = etTeacher.text?.toString()?.trim().orEmpty(),
                    credit = 0.0,
                    property = "手动",
                    examType = "",
                    day = spDay.selectedItemPosition + 1,
                    startNode = spStart.selectedItemPosition + 1,
                    nodeCount = spCount.selectedItemPosition + 1,
                    weeks = weeks,
                    weekDesc = "手动添加",
                    campus = "",
                    building = "",
                    room = etRoom.text?.toString()?.trim().orEmpty(),
                    colorIndex = (data.courses.map { it.colorIndex }.maxOrNull() ?: -1) + 1
                )
                val updated = data.copy(
                    courses = (data.courses + newCourse).sortedWith(compareBy({ it.day }, { it.startNode }))
                )
                repo.save(updated)
                dialog.dismiss()
                reload()
                Reminders.reschedule(this)
                Toast.makeText(this, "已添加「$name」", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showCourseDetail(course: Course) {        val data = timetable ?: return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_course, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.colorDot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(TimetableView.activeColorOf(course.colorIndex))
        }
        view.findViewById<TextView>(R.id.tvName).text = course.courseName
        val meta = buildList {
            if (course.property.isNotBlank()) add(course.property)
            if (course.credit > 0) add(trimCredit(course.credit) + "学分")
            if (course.examType.isNotBlank()) add(course.examType)
            if (course.sequence.isNotBlank()) add("课序号 ${course.sequence}")
        }.joinToString(" · ")
        view.findViewById<TextView>(R.id.tvMeta).text = meta

        val rows = view.findViewById<LinearLayout>(R.id.rows)
        val startTime = data.nodeTimes.firstOrNull { it.node == course.startNode }?.start
        val endTime = data.nodeTimes.firstOrNull { it.node == course.endNode }?.end
        val timeText = buildString {
            append("周${weekdayNames[course.day - 1]}  第${course.startNode}-${course.endNode}节")
            if (startTime != null && endTime != null) append("  $startTime-$endTime")
        }
        addRow(rows, "时间", timeText)
        addRow(rows, "周次", course.weekDesc.ifBlank { weeksSummary(course.weeks) })
        if (course.teacher.isNotBlank()) addRow(rows, "教师", course.teacher)
        if (course.locationFull.isNotBlank()) addRow(rows, "地点", course.locationFull)
        if (!course.isInWeek(selectedWeek)) addRow(rows, "提示", "本周（第${selectedWeek}周）没有这节课")

        // 手动添加的课程和导入的实验课可以删除（教务系统导入的课删了也没意义，重新导入会回来）
        if (course.courseNumber == ExpImporter.MANUAL_COURSE_NUMBER || course.courseNumber == ExpImporter.EXP_COURSE_NUMBER) {
            val del = com.google.android.material.button.MaterialButton(this).apply {
                text = "删除这门课"
                setTextColor(0xFFB71C1C.toInt())
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            del.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END; topMargin = dp(10f) }
            del.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除课程")
                    .setMessage("删除「${course.courseName}」？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        val updated = data.copy(courses = data.courses.filterNot { it === course })
                        repo.save(updated)
                        timetable = updated
                        dialog.dismiss()
                        reload()
                        Reminders.reschedule(this)
                        Toast.makeText(this, "已删除「${course.courseName}」", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            rows.addView(del)
        }

        dialog.show()
    }

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(7f), 0, dp(7f))
        val labelTv = TextView(this)
        labelTv.text = label
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        labelTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        labelTv.layoutParams = LinearLayout.LayoutParams(dp(56f), LinearLayout.LayoutParams.WRAP_CONTENT)
        val valueTv = TextView(this)
        valueTv.text = value
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        valueTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        valueTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(labelTv)
        row.addView(valueTv)
        parent.addView(row)
    }

    private fun weeksSummary(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "—"
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1) + listOf(Int.MIN_VALUE)) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = w
            prev = w
        }
        return parts.joinToString(", ") + "周"
    }

    private fun trimCredit(credit: Double): String =
        if (credit == credit.toLong().toDouble()) credit.toLong().toString() else credit.toString()

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (crashed) return false
        menuInflater.inflate(R.menu.menu_main, menu)
        showOtherMenuItem = menu.findItem(R.id.action_show_other)
        showOtherMenuItem?.isChecked = repo.showOtherWeeks
        clearBgMenuItem = menu.findItem(R.id.action_clear_background)
        clearBgMenuItem?.isVisible = TimetableBackground.exists(this)
        overlayMenuItem = menu.findItem(R.id.action_bg_overlay)
        overlayMenuItem?.isVisible = TimetableBackground.exists(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_todo -> startActivity(Intent(this, TodoActivity::class.java))
            R.id.action_site -> startActivity(Intent(this, BrowserActivity::class.java))
            R.id.action_today -> {
                val data = timetable ?: return true
                selectedWeek = realWeek(data).coerceIn(1, data.totalWeeks)
                render()
            }
            R.id.action_reminder -> showReminderDialog()
            R.id.action_reimport -> openImport()
            R.id.action_import_exp -> startActivity(android.content.Intent(this, ExpImportActivity::class.java))
            R.id.action_semester -> showSemesterDialog()
            R.id.action_background -> pickBackground.launch("image/*")
            R.id.action_bg_overlay -> showOverlayDialog()
            R.id.action_clear_background -> {
                TimetableBackground.clear(this)
                applyBackground()
                Toast.makeText(this, "已恢复白色背景", Toast.LENGTH_SHORT).show()
            }
            R.id.action_show_other -> {
                val newValue = !item.isChecked
                item.isChecked = newValue
                repo.showOtherWeeks = newValue
                render()
            }
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun openImport() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun showSemesterDialog() {
        val data = timetable ?: return
        val view = layoutInflater.inflate(R.layout.dialog_semester, null)
        val btnDate = view.findViewById<MaterialButton>(R.id.btnDate)
        val etWeeks = view.findViewById<TextInputEditText>(R.id.etWeeks)
        var picked = startDate(data)
        btnDate.text = picked.toString()
        etWeeks.setText(data.totalWeeks.toString())
        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    // 自动对齐到所选日期所在周的周一
                    picked = LocalDate.of(y, m + 1, d).with(java.time.DayOfWeek.MONDAY)
                    btnDate.text = picked.toString()
                },
                picked.year, picked.monthValue - 1, picked.dayOfMonth
            ).show()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("学期设置")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val weeks = etWeeks.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 30) ?: data.totalWeeks
                repo.updateSemester(picked, weeks)
                // 手动设置的开学日期是用户明确的意图，清除实验课的自动校准值，避免下次导入又被改回去
                repo.expCalibratedStart = ""
                reload()
                Reminders.reschedule(this)
            }
            .show()
    }

    private fun showReminderDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_reminder, null)
        val switch = view.findViewById<MaterialSwitch>(R.id.swEnable)
        val group = view.findViewById<RadioGroup>(R.id.rgLead)
        val hint = view.findViewById<TextView>(R.id.tvReminderHint)
        val leadIds = mapOf(10 to R.id.lead10, 15 to R.id.lead15, 20 to R.id.lead20, 30 to R.id.lead30, 60 to R.id.lead60)

        switch.isChecked = repo.reminderEnabled
        group.check(leadIds[repo.reminderLeadMinutes] ?: R.id.lead20)
        hint.text = nextReminderHint()

        MaterialAlertDialogBuilder(this)
            .setTitle("上课提醒")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                repo.reminderEnabled = switch.isChecked
                repo.reminderLeadMinutes = leadIds.entries.firstOrNull { it.value == group.checkedRadioButtonId }?.key ?: 20
                Reminders.reschedule(this)
                if (switch.isChecked) {
                    repo.notificationPermissionAsked = false
                    maybeRequestNotificationPermission()
                    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                        Toast.makeText(this, "系统里已关闭本应用的通知，请在设置中允许通知", Toast.LENGTH_LONG).show()
                    }
                }
                Toast.makeText(this, if (switch.isChecked) "已开启上课提醒" else "已关闭上课提醒", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun nextReminderHint(): String {
        val data = timetable ?: return "导入课表后生效。"
        val next = Reminders.nextClassAt(data, LocalDateTime.now(), repo.reminderLeadMinutes) ?: return "本学期没有更多课程了。"
        val course = data.courses.firstOrNull {
            it.day == next.dayOfWeek.value && Reminders.startOf(it, next.toLocalDate(), data) == next
        }
        val dayText = when (next.toLocalDate()) {
            LocalDate.now() -> "今天"
            LocalDate.now().plusDays(1) -> "明天"
            else -> next.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        return "下一次提醒：$dayText ${next.minusMinutes(repo.reminderLeadMinutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))}" +
            (course?.let { "（${it.courseName}）" } ?: "")
    }

    private fun showAbout() {
        val data = timetable
        val imported = data?.importedAt?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it))
        } ?: "—"
        MaterialAlertDialogBuilder(this)
            .setTitle("湖理课表")
            .setMessage(
                "版本 ${BuildConfig.VERSION_NAME}\n\n" +
                    "课表数据来自湖南理工学院 URP 教务系统（bkjw.hnist.cn），" +
                    "通过学校统一身份认证登录后一键导入，账号密码仅在本机使用。\n\n" +
                    "上次导入：$imported\n\n" +
                    "点击课程可查看详情；周次不对可在“学期设置”里修正开学日期。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
    }
}
