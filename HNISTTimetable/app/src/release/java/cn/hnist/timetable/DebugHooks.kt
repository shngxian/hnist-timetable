package cn.hnist.timetable

import android.content.Context
import cn.hnist.timetable.data.TimetableRepo

/** 正式版：不包含任何示例数据。 */
object DebugHooks {
    const val ENABLED = false

    @Suppress("UNUSED_PARAMETER")
    fun installSample(context: Context, repo: TimetableRepo): Boolean = false
}
