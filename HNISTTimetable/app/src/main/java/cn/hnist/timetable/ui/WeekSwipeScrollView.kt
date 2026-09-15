package cn.hnist.timetable.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 竖向照常滚动的 ScrollView，额外识别横向滑动：
 * 手指明显横向移动时接管手势（子 View 会收到 CANCEL，不会误触课程卡片），
 * 抬手时按位移或速度判定为“上一周 / 下一周”。
 */
class WeekSwipeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** -1 = 上一周（向右滑），+1 = 下一周（向左滑）。 */
    var onSwipe: ((direction: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val swipeDistance = 56f * density          // 慢慢拖过这个距离也算
    private val flingVelocity = 320f * density          // 快速一甩：px/s
    private val flingMinDistance = 16f * density

    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private var vertical = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                horizontal = false
                vertical = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (horizontal) return true
                if (!vertical && decideDirection(ev)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (horizontal) {
                    finishSwipe(ev)
                    return true
                }
                reset()
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        if (!horizontal && !vertical && ev.actionMasked == MotionEvent.ACTION_MOVE && decideDirection(ev)) {
            return true
        }
        if (horizontal) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP -> finishSwipe(ev)
                MotionEvent.ACTION_CANCEL -> reset()
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    /** 根据当前位移判定手势方向；返回 true 表示已确定为横向。 */
    private fun decideDirection(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        val dy = abs(ev.y - downY)
        when {
            dx > touchSlop && dx > dy * 1.5f -> {
                horizontal = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            dy > touchSlop -> vertical = true
        }
        return false
    }

    private fun finishSwipe(ev: MotionEvent) {
        val dx = ev.x - downX
        var vx = 0f
        velocityTracker?.let {
            it.computeCurrentVelocity(1000)
            vx = it.xVelocity
        }
        val fling = abs(vx) >= flingVelocity && abs(dx) >= flingMinDistance && (vx < 0) == (dx < 0)
        if (abs(dx) >= swipeDistance || fling) {
            onSwipe?.invoke(if (dx < 0) 1 else -1)
        }
        reset()
    }

    private fun reset() {
        horizontal = false
        vertical = false
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
