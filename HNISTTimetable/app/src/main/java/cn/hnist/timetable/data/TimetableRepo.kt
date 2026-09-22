package cn.hnist.timetable.data

import android.content.Context
import android.content.SharedPreferences
import cn.hnist.timetable.model.Timetable
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 课表与设置的本地存储，全部放在应用私有 SharedPreferences 中。 */
class TimetableRepo(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("timetable", Context.MODE_PRIVATE)

    fun load(): Timetable? {
        val raw = prefs.getString(KEY_TIMETABLE, null) ?: return null
        return try {
            val timetable = Timetable.fromJson(JSONObject(raw))
            // 旧版本导入时网页缓存了过期的“星期几”，可能存下非周一的开学日期，导致整周日期错位；
            // 读取时自动对齐回所在周的周一并回写。
            val start = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull()
            if (start != null && start.dayOfWeek != DayOfWeek.MONDAY) {
                val fixed = timetable.copy(startDate = start.with(DayOfWeek.MONDAY).toString())
                save(fixed)
                fixed
            } else {
                timetable
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(timetable: Timetable) {
        prefs.edit().putString(KEY_TIMETABLE, timetable.toJson().toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TIMETABLE).apply()
    }

    fun updateSemester(startDate: LocalDate, totalWeeks: Int) {
        val current = load() ?: return
        save(current.copy(startDate = startDate.toString(), totalWeeks = totalWeeks))
    }

    var showOtherWeeks: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OTHER_WEEKS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_OTHER_WEEKS, value).apply()

    var lastStudentId: String
        get() = prefs.getString(KEY_STUDENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STUDENT_ID, value).apply()

    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_PASSWORD, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_PASSWORD, value).apply()

    var savedPassword: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** 上课提醒开关，默认开启。 */
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    /** 提前多少分钟提醒。 */
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_LEAD, 20)
        set(value) = prefs.edit().putInt(KEY_REMINDER_LEAD, value).apply()

    /** 是否已经向用户申请过通知权限（避免每次打开都弹）。 */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    /** 内置浏览器是否使用电脑模式（桌面 User-Agent）。 */
    var browserDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER_DESKTOP, value).apply()

    /** 课表背景上白色遮罩的浓度 0f..1f（0 = 照片原样）。 */
    var backgroundOverlayAlpha: Float
        get() = prefs.getFloat(KEY_BG_OVERLAY, 0.6f)
        set(value) = prefs.edit().putFloat(KEY_BG_OVERLAY, value).apply()

    companion object {
        private const val KEY_TIMETABLE = "timetable_json"
        private const val KEY_SHOW_OTHER_WEEKS = "show_other_weeks"
        private const val KEY_STUDENT_ID = "student_id"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_LEAD = "reminder_lead_minutes"
        private const val KEY_NOTIF_ASKED = "notification_permission_asked"
        private const val KEY_BROWSER_DESKTOP = "browser_desktop_mode"
        private const val KEY_BG_OVERLAY = "background_overlay_alpha"

        /** 根据开学日期计算某一天属于第几周（可能小于 1 或大于总周数，由调用方裁剪）。 */
        fun weekOf(startDate: LocalDate, date: LocalDate): Int {
            val monday = startDate.with(DayOfWeek.MONDAY)
            val days = ChronoUnit.DAYS.between(monday, date)
            return Math.floorDiv(days, 7L).toInt() + 1
        }
    }
}
