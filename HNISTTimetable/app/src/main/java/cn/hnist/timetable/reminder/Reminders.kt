package cn.hnist.timetable.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.hnist.timetable.BuildConfig
import cn.hnist.timetable.MainActivity
import cn.hnist.timetable.R
import cn.hnist.timetable.TodoActivity
import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.model.Course
import cn.hnist.timetable.model.Timetable
import cn.hnist.timetable.todo.TodoItem
import cn.hnist.timetable.todo.TodoRepo
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒：始终只挂一个闹钟，指向“下一节课开始时间 - 提前分钟数”。
 * 闹钟触发后发通知，再重新计算下一次；App 打开、导入课表、开机后也会重算。
 *
 * 待办提醒：另挂一个闹钟指向最近一个未完成待办的开始时间，到点通知。
 */
object Reminders {

    private const val CHANNEL_ID = "class_reminders"
    private const val TODO_CHANNEL_ID = "todo_reminders"
    private const val REQUEST_ALARM = 1001
    private const val REQUEST_TODO_ALARM = 1002
    const val ACTION_TEST = "cn.hnist.timetable.action.TEST_REMINDER"
    const val ACTION_TODO_START = "cn.hnist.timetable.action.TODO_START"

    private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "临近上课时提醒课程、节次和教室"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** 某门课在某天的上课时刻。 */
    fun startOf(course: Course, date: LocalDate, timetable: Timetable): LocalDateTime? {
        val node = timetable.nodeTimes.firstOrNull { it.node == course.startNode } ?: return null
        val time = runCatching { LocalTime.parse(node.start) }.getOrNull() ?: return null
        return date.atTime(time)
    }

    /** 下一节需要提醒的课的开始时间（其提醒时刻仍在未来）。 */
    fun nextClassAt(timetable: Timetable, now: LocalDateTime, leadMinutes: Int): LocalDateTime? {
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull() ?: return null
        val end = startDate.plusWeeks(timetable.totalWeeks.toLong())
        var date = now.toLocalDate()
        while (date.isBefore(end)) {
            val week = TimetableRepo.weekOf(startDate, date)
            var best: LocalDateTime? = null
            if (week in 1..timetable.totalWeeks) {
                for (course in timetable.courses) {
                    if (course.day != date.dayOfWeek.value || !course.isInWeek(week)) continue
                    val at = startOf(course, date, timetable) ?: continue
                    if (!at.minusMinutes(leadMinutes.toLong()).isAfter(now)) continue
                    if (best == null || at.isBefore(best)) best = at
                }
            }
            // 按天顺序扫描，当天找到的最早一节就是全局最早的
            if (best != null) return best
            date = date.plusDays(1)
        }
        return null
    }

    fun reschedule(context: Context) {
        val repo = TimetableRepo(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        alarmManager.cancel(pending)
        if (!repo.reminderEnabled) return
        val timetable = repo.load() ?: return
        val lead = repo.reminderLeadMinutes
        val next = nextClassAt(timetable, LocalDateTime.now(), lead) ?: return
        val triggerAt = next.minusMinutes(lead.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_ALARM,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 闹钟触发：把接下来 lead 分钟内开始的课都通知一遍（闹钟可能略有延迟，窗口放宽一点）。 */
    fun notifyUpcoming(context: Context) {
        val repo = TimetableRepo(context)
        if (!repo.reminderEnabled) return
        val timetable = repo.load() ?: return
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull() ?: return
        val lead = repo.reminderLeadMinutes
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val week = TimetableRepo.weekOf(startDate, today)
        if (week !in 1..timetable.totalWeeks) return

        for (course in timetable.courses) {
            if (course.day != today.dayOfWeek.value || !course.isInWeek(week)) continue
            val at = startOf(course, today, timetable) ?: continue
            val minutes = Duration.between(now, at).toMinutes()
            if (minutes < -1 || minutes > lead + 2) continue
            notifyCourse(context, course, timetable, at, minutes.toInt().coerceAtLeast(0))
        }
    }

    /** 调试用：不管时间窗口，直接把下一节课的提醒发出来。 */
    fun notifyNextForTest(context: Context) {
        val repo = TimetableRepo(context)
        val timetable = repo.load() ?: return
        val now = LocalDateTime.now()
        val next = nextClassAt(timetable, now, 0) ?: return
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull() ?: return
        val week = TimetableRepo.weekOf(startDate, next.toLocalDate())
        val minutes = Duration.between(now, next).toMinutes().toInt()
        timetable.courses
            .filter { it.day == next.dayOfWeek.value && it.isInWeek(week) && startOf(it, next.toLocalDate(), timetable) == next }
            .forEach { notifyCourse(context, it, timetable, next, minutes) }
    }

    private fun notifyCourse(context: Context, course: Course, timetable: Timetable, at: LocalDateTime, minutesLeft: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)

        val endTime = timetable.nodeTimes.firstOrNull { it.node == course.endNode }?.end
        val timeText = at.format(DateTimeFormatter.ofPattern("HH:mm")) + (endTime?.let { "-$it" } ?: "")
        val countdown = when {
            minutesLeft <= 0 -> "马上上课"
            minutesLeft >= 60 -> "${minutesLeft / 60}小时${minutesLeft % 60}分钟后上课"
            else -> "${minutesLeft}分钟后上课"
        }
        val whereText = course.locationFull.ifBlank { "地点未知" }
        val detail = buildString {
            append("周${weekdayNames[course.day - 1]} 第${course.startNode}-${course.endNode}节 $timeText")
            append("\n").append(whereText)
            if (course.teacher.isNotBlank()) append("\n").append(course.teacher)
        }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${course.courseName} · $countdown")
            .setContentText("第${course.startNode}-${course.endNode}节 $timeText · $whereText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        val id = "${at.toLocalDate()}#${course.day}#${course.startNode}#${course.courseName}".hashCode()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限
        }
    }

    // ---------- 待办开始提醒 ----------

    private fun ensureTodoChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(TODO_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(TODO_CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "待办事项到了开始时间时提醒"
                enableVibration(true)
            }
        )
    }

    /** 重新安排“下一个待办开始”的闹钟，并把已经到点但还没提醒过的先补发。 */
    fun rescheduleTodos(context: Context) {
        val repo = TodoRepo(context)
        val now = LocalDateTime.now()
        notifyStartedTodos(context, repo, now)

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = todoAlarmIntent(context)
        alarmManager.cancel(pending)

        val next = repo.list()
            .filter { it.isRecurring || !it.done }
            .mapNotNull { it.nextStartAfter(now) }
            .minOrNull() ?: return
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun notifyStartedTodos(context: Context, repo: TodoRepo = TodoRepo(context), now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        val todayKey = today.toString()
        var changed = false
        val updated = repo.list().map { item ->
            when {
                // 重复待办：每天到时刻提醒一次，当天已完成就不打扰
                item.isRecurring -> {
                    val time = item.startTime
                    val due = time != null && item.occursToday(today) &&
                        !LocalDateTime.of(today, time).isAfter(now) &&
                        item.lastNotifiedDate != todayKey && !item.doneEffective(today)
                    if (due) {
                        notifyTodo(context, item)
                        changed = true
                        item.copy(lastNotifiedDate = todayKey)
                    } else item
                }
                // 普通待办：只提醒一次
                !item.done && !item.startNotified && item.startedToday(now) -> {
                    notifyTodo(context, item)
                    changed = true
                    item.copy(startNotified = true)
                }
                else -> item
            }
        }
        if (changed) repo.save(updated)
    }

    private fun todoAlarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_TODO_ALARM,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_TODO_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notifyTodo(context: Context, item: TodoItem) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureTodoChannel(context)

        val dueText = item.due?.let { "截止 ${it.format(DateTimeFormatter.ofPattern("M月d日"))}" }
        val repeatText = if (item.isRecurring) {
            TodoItem.repeatLabel(item.repeat) +
                (item.startTime?.let { " ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" } ?: "") +
                (if (item.streak > 0) " · 已连续 ${item.streak} 天" else "")
        } else null
        val text = listOfNotNull(item.note.takeIf { it.isNotBlank() }, repeatText, dueText).joinToString(" · ")
            .ifBlank { "点击查看待办事项" }
        val openTodo = PendingIntent.getActivity(
            context, 1,
            Intent(context, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, TODO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("待办开始：${item.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openTodo)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("todo#${item.id}".hashCode(), notification)
        } catch (e: SecurityException) {
            // 未授予通知权限
        }
    }
}

/** 闹钟到点：发通知，然后安排下一次。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_TODO_START -> {
                Reminders.rescheduleTodos(context)   // 内部会先补发已到点的待办
                return
            }
            Reminders.ACTION_TEST -> if (BuildConfig.DEBUG) Reminders.notifyNextForTest(context)
            else -> Reminders.notifyUpcoming(context)
        }
        Reminders.reschedule(context)
    }
}

/** 开机后闹钟会被清掉，重新安排。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED) {
            Reminders.reschedule(context)
            Reminders.rescheduleTodos(context)
        }
    }
}
