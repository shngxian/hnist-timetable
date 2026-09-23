package cn.hnist.timetable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import cn.hnist.timetable.model.Course
import cn.hnist.timetable.model.NodeTime

/**
 * 课表网格：左侧节次列 + 7 天列，课程卡片按节次跨行摆放。
 * 表头（星期/日期）由外部绘制，使用 [leftColumnWidth] 对齐。
 */
class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    companion object {
        /** 课程配色（白字可读）。 */
        val PALETTE = intArrayOf(
            0xFF5B8DEF.toInt(), 0xFF4FB6A5.toInt(), 0xFFF09A5B.toInt(), 0xFFE88FB4.toInt(),
            0xFF9B85D9.toInt(), 0xFF78B96B.toInt(), 0xFF55B7D4.toInt(), 0xFFE36565.toInt(),
            0xFFD4A64B.toInt(), 0xFF6E8DE6.toInt(), 0xFF5FBF9F.toInt(), 0xFFC783C7.toInt()
        )

        fun colorOf(index: Int): Int = PALETTE[Math.floorMod(index, PALETTE.size)]

        /** 本周课程用更饱和、更深一档的颜色；非本周占位仍用原色 + 透明度。 */
        fun activeColorOf(index: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(colorOf(index), hsv)
            hsv[1] = (hsv[1] * 1.18f).coerceAtMost(1f)
            hsv[2] = hsv[2] * 0.84f
            return Color.HSVToColor(hsv)
        }
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    val leftColumnWidth: Float = dp(34f)
    private val rowHeight: Float = dp(60f)
    private val bottomPadding: Float = dp(16f)
    private val cardMargin: Float = dp(1.5f)
    private val cardRadius: Float = dp(8f)

    private var maxNode = 12
    private var week = 1
    private var todayColumn = -1
    private var showOtherWeeks = true
    private var courses: List<Course> = emptyList()
    private var nodeTimes: Map<Int, NodeTime> = emptyMap()

    /** 背景照片与白色遮罩；遮罩透明度可调（0 = 照片原样，1 = 纯白）。 */
    private var backgroundBitmap: Bitmap? = null
    private val overlayPaint = Paint().apply { color = 0xB8FFFFFF.toInt() }
    private val bgSrc = Rect(0, 0, 0, 0)
    private val bgDst = Rect(0, 0, 0, 0)

    var onCourseClick: ((Course) -> Unit)? = null

    /** 点击没有课程卡片的空白格子时回调（星期 1-7，节次 1..maxNode），用于手动添加课程。 */
    var onEmptyClick: ((day: Int, node: Int) -> Unit)? = null

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val day = ((e.x - leftColumnWidth) / ((width - leftColumnWidth) / 7f)).toInt() + 1
                val node = (e.y / rowHeight).toInt() + 1
                if (e.x > leftColumnWidth && day in 1..7 && node in 1..maxNode) {
                    onEmptyClick?.invoke(day.coerceIn(1, 7), node.coerceIn(1, maxNode))
                }
                return true
            }
        }
    )

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E4553.toInt()
        textSize = dp(12.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A8194.toInt()
        textSize = dp(8.5f)
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint().apply {
        color = 0x18000000
        strokeWidth = 1f
    }
    private val todayPaint = Paint().apply { color = 0x184361EE }
    private val todayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x664361EE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.WHITE)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 设置/清除背景照片（传 null 恢复纯白）。 */
    fun setBackgroundPhoto(bitmap: Bitmap?) {
        backgroundBitmap?.recycle()
        backgroundBitmap = bitmap
        setBackgroundColor(if (bitmap != null) Color.TRANSPARENT else Color.WHITE)
        invalidate()
    }

    /** 调节背景上白色遮罩的浓度：0 = 照片原样，1 = 完全白。 */
    fun setOverlayAlpha(alpha: Float) {
        overlayPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        invalidate()
    }

    fun setData(
        courses: List<Course>,
        nodeTimes: List<NodeTime>,
        week: Int,
        maxNode: Int,
        todayColumn: Int,
        showOtherWeeks: Boolean
    ) {
        this.courses = courses
        this.nodeTimes = nodeTimes.associateBy { it.node }
        this.week = week
        this.maxNode = maxNode.coerceAtLeast(1)
        this.todayColumn = todayColumn
        this.showOtherWeeks = showOtherWeeks
        rebuildCards()
        requestLayout()
        invalidate()
    }

    fun setWeek(week: Int, todayColumn: Int) {
        this.week = week
        this.todayColumn = todayColumn
        rebuildCards()
        requestLayout()
        invalidate()
    }

    // ---------- 卡片构建 ----------

    private class CardInfo(val course: Course, val active: Boolean, val slot: Int, val slotCount: Int)

    private fun rebuildCards() {
        removeAllViews()
        val active = courses.filter { it.isInWeek(week) }
        val occupied = HashSet<Int>()
        active.forEach { c -> for (n in c.startNode..c.endNode) occupied += c.day * 100 + n }

        if (showOtherWeeks) {
            // 非本周课程：只在没有本周课程占用的格子里显示一个占位，避免层层叠叠
            courses.filter { !it.isInWeek(week) }
                .filter { c -> (c.startNode..c.endNode).none { occupied.contains(c.day * 100 + it) } }
                .distinctBy { it.day * 100 + it.startNode }
                .forEach { addView(createCard(CardInfo(it, false, 0, 1))) }
        }

        // 本周课程若时间冲突，则在同一列内左右并排显示
        for (day in 1..7) {
            val dayCourses = active.filter { it.day == day }.sortedBy { it.startNode }
            val clusters = mutableListOf<MutableList<Course>>()
            for (c in dayCourses) {
                val cluster = clusters.lastOrNull()
                val clusterEnd = cluster?.maxOf { it.endNode } ?: -1
                if (cluster != null && c.startNode <= clusterEnd) cluster += c else clusters += mutableListOf(c)
            }
            clusters.forEach { cluster ->
                cluster.forEachIndexed { index, c -> addView(createCard(CardInfo(c, true, index, cluster.size))) }
            }
        }
    }

    private fun createCard(info: CardInfo): TextView {
        val c = info.course
        val tv = TextView(context)
        tv.tag = info
        tv.text = if (c.roomShort.isNotBlank()) "${c.courseName}\n@${c.roomShort}" else c.courseName
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (info.slotCount > 1) 9f else 10.5f)
        tv.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        tv.setPadding(dp(3f).toInt(), dp(6f).toInt(), dp(3f).toInt(), dp(4f).toInt())
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setLineSpacing(0f, 1.08f)
        tv.includeFontPadding = false
        tv.background = GradientDrawable().apply {
            cornerRadius = cardRadius
            // 卡片略微加深并加一点描边，压在背景图上也能看清边缘
            setColor(if (info.active) activeColorOf(c.colorIndex) else colorOf(c.colorIndex))
            if (backgroundBitmap != null) {
                setStroke(dp(1f).toInt(), 0x66000000)
            }
        }
        if (!info.active) tv.alpha = if (backgroundBitmap != null) 0.38f else 0.3f
        tv.isClickable = true
        tv.setOnClickListener { onCourseClick?.invoke(c) }
        return tv
    }

    // ---------- 测量与布局 ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (maxNode * rowHeight + bottomPadding).toInt()
        val colWidth = (width - leftColumnWidth) / 7f

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val info = child.tag as? CardInfo ?: continue
            val slotWidth = colWidth / info.slotCount
            val cardW = (slotWidth - cardMargin * 2).toInt().coerceAtLeast(1)
            val cardH = (info.course.nodeCount * rowHeight - cardMargin * 2).toInt().coerceAtLeast(1)
            (child as? TextView)?.let { it.maxLines = estimateMaxLines(it, cardH) }
            child.measure(
                MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    private fun estimateMaxLines(tv: TextView, cardHeight: Int): Int {
        val lineHeight = tv.paint.fontSpacing * 1.08f
        val usable = cardHeight - tv.paddingTop - tv.paddingBottom
        return (usable / lineHeight).toInt().coerceIn(1, 8)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val colWidth = (measuredWidth - leftColumnWidth) / 7f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val info = child.tag as? CardInfo ?: continue
            val c = info.course
            val slotWidth = colWidth / info.slotCount
            val left = leftColumnWidth + (c.day - 1) * colWidth + info.slot * slotWidth + cardMargin
            val top = (c.startNode - 1) * rowHeight + cardMargin
            child.layout(
                left.toInt(), top.toInt(),
                left.toInt() + child.measuredWidth, top.toInt() + child.measuredHeight
            )
        }
    }

    // ---------- 背景绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val colWidth = (w - leftColumnWidth) / 7f
        val gridBottom = maxNode * rowHeight

        // 背景照片：居中裁剪铺满，再按用户调节的浓度叠加白色遮罩
        val bg = backgroundBitmap
        if (bg != null && !bg.isRecycled) {
            val scale = maxOf(w / bg.width, h / bg.height)
            val dw = bg.width * scale
            val dh = bg.height * scale
            bgSrc.set(0, 0, bg.width, bg.height)
            bgDst.set(((w - dw) / 2f).toInt(), ((h - dh) / 2f).toInt(), ((w + dw) / 2f).toInt(), ((h + dh) / 2f).toInt())
            canvas.drawBitmap(bg, bgSrc, bgDst, null)
            if (overlayPaint.alpha > 0) {
                canvas.drawRect(0f, 0f, w, h, overlayPaint)
            }
        }

        if (todayColumn in 1..7) {
            val left = leftColumnWidth + (todayColumn - 1) * colWidth
            val hasPhoto = bg != null && !bg.isRecycled
            // 有背景照片时把高亮加深一档并加描边，保证任何照片上都能一眼认出“今天”这一列
            todayPaint.alpha = if (hasPhoto) 0x38 else 0x18
            canvas.drawRect(left, 0f, left + colWidth, gridBottom, todayPaint)
            if (hasPhoto) {
                val inset = 1.5f * resources.displayMetrics.density
                canvas.drawRect(
                    left + inset, inset,
                    left + colWidth - inset, gridBottom - inset,
                    todayStrokePaint
                )
            }
        }

        for (n in 1..maxNode) {
            val top = (n - 1) * rowHeight
            canvas.drawLine(leftColumnWidth, top, w, top, linePaint)

            val cx = leftColumnWidth / 2f
            val time = nodeTimes[n]
            if (time != null) {
                canvas.drawText(n.toString(), cx, top + rowHeight * 0.40f, nodePaint)
                canvas.drawText(time.start, cx, top + rowHeight * 0.62f, timePaint)
                canvas.drawText(time.end, cx, top + rowHeight * 0.82f, timePaint)
            } else {
                canvas.drawText(n.toString(), cx, top + rowHeight * 0.58f, nodePaint)
            }
        }
        canvas.drawLine(leftColumnWidth, gridBottom, w, gridBottom, linePaint)
    }
}
