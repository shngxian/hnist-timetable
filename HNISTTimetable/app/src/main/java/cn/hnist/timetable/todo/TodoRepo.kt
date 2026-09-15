package cn.hnist.timetable.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 待办。支持两类：
 *  - 普通待办：可选开始时刻（到点提醒）和截止日期；
 *  - 重复待办（repeat = "daily" 或 "weekly:1,3,5"）：每天/每周固定几天出现，
 *    可用 fromDate/dueDate 限定只在某段日期内重复；勾选完成只对当天有效（lastDoneDate），
 *    第二天自动回到未完成，并记录连续天数；截止日过后自动结束。
 */
data class TodoItem(
    val id: Long,
    val title: String,
    val note: String,
    val dueDate: String,          // yyyy-MM-dd：普通待办的截止日；重复待办的最后一天。空串未设置
    val startAt: String,          // 普通待办：yyyy-MM-ddTHH:mm；重复待办：HH:mm（每天的时刻）；空串未设置
    val repeat: String,           // "" | "daily" | "weekly:1,3,5"（1=周一…7=周日）
    val fromDate: String,         // 重复待办的第一天 yyyy-MM-dd，空串表示从创建起
    val done: Boolean,            // 普通待办的完成标记
    val createdAt: Long,
    val doneAt: Long,
    val startNotified: Boolean,   // 普通待办的开始提醒是否已发
    val lastDoneDate: String,     // 重复待办：最近完成的日期 yyyy-MM-dd
    val lastNotifiedDate: String, // 重复待办：最近已提醒的日期 yyyy-MM-dd
    val streak: Int               // 重复待办：连续完成天数
) {
    val due: LocalDate? get() = if (dueDate.isBlank()) null else runCatching { LocalDate.parse(dueDate) }.getOrNull()

    val from: LocalDate? get() = if (fromDate.isBlank() || !isRecurring) null else runCatching { LocalDate.parse(fromDate) }.getOrNull()

    /** 普通待办的具体开始时刻。 */
    val start: LocalDateTime? get() =
        if (startAt.isBlank() || isRecurring) null else runCatching { LocalDateTime.parse(startAt) }.getOrNull()

    /** 重复待办每天的开始时刻（可空）。 */
    val startTime: LocalTime? get() =
        if (startAt.isBlank() || !isRecurring) null else runCatching { LocalTime.parse(startAt) }.getOrNull()

    val isRecurring: Boolean get() = repeat.isNotBlank()

    /** 重复的星期几（1=周一…7=周日）；每天返回全部。 */
    val repeatDays: Set<Int>
        get() = when {
            repeat == "daily" -> (1..7).toSet()
            repeat.startsWith("weekly:") -> repeat.removePrefix("weekly:")
                .split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
            else -> emptySet()
        }

    fun occursToday(today: LocalDate): Boolean = !isRecurring || occursTodayOn(today)

    /** 重复待办：截止日已过，整个任务结束。 */
    fun isEnded(today: LocalDate): Boolean {
        if (!isRecurring) return false
        val end = due ?: return false
        return today.isAfter(end)
    }

    /** 今天是否已完成（重复待办看 lastDoneDate）。 */
    fun doneEffective(today: LocalDate): Boolean =
        if (isRecurring) lastDoneDate == today.toString() else done

    /** 距截止日的天数：负数已过期，0 今天（重复待办也按最后一天算）。 */
    fun daysLeft(today: LocalDate): Long? = due?.let { ChronoUnit.DAYS.between(today, it) }

    /** 紧急程度等级，数字越小越紧急：0 已过期，1 今天，2 明天，3 两三天内，4 一周内，5 更远，6 没有截止日。 */
    fun urgencyRank(today: LocalDate): Int {
        val days = daysLeft(today) ?: return 6
        return when {
            days < 0 -> 0
            days == 0L -> 1
            days == 1L -> 2
            days <= 3 -> 3
            days <= 7 -> 4
            else -> 5
        }
    }

    /** 今天的这一次是否已经开始（重复待办按当天时刻算；没有时刻则视为已开始）。 */
    fun startedToday(now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        return if (isRecurring) {
            val time = startTime
            occursToday(today) && (time == null || !LocalDateTime.of(today, time).isAfter(now))
        } else {
            val s = start
            s != null && !s.isAfter(now)
        }
    }

    /** 给闹钟用：下一次需要提醒的时刻（普通待办只提醒一次；重复待办提醒时间段内的每一天）。 */
    fun nextStartAfter(now: LocalDateTime): LocalDateTime? {
        if (startAt.isBlank()) return null
        if (!isRecurring) {
            val s = start ?: return null
            return if (s.isAfter(now) && !startNotified) s else null
        }
        val time = startTime ?: return null
        val today = now.toLocalDate()
        val first = from?.let { if (it.isAfter(today)) it else today } ?: today
        for (offset in 0..7L) {
            val date = first.plusDays(offset)
            if (!occursTodayOn(date)) continue
            val at = date.atTime(time)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** 某一天是否在重复范围内（星期几 + 时间段）。 */
    private fun occursTodayOn(date: LocalDate): Boolean {
        if (!repeatDays.contains(date.dayOfWeek.value)) return false
        val start = from
        if (start != null && date.isBefore(start)) return false
        val end = due
        if (end != null && date.isAfter(end)) return false
        return true
    }

    /** 重复待办在 [before] 之前最近一次应出现的日期；没有则 null。 */
    fun previousOccurrence(before: LocalDate): LocalDate? {
        if (!isRecurring) return null
        for (offset in 1..7L) {
            val date = before.minusDays(offset)
            if (occursTodayOn(date)) return date
        }
        return null
    }

    private val createdDate: LocalDate
        get() = if (createdAt <= 0L) LocalDate.now()
        else java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /**
     * 错过的次数：上次完成（没完成过就是创建那天）之后、今天之前，应该出现却没完成的天数。
     * 每天各算各的，错过不需要补做，但会中断连续天数。
     */
    fun missedCount(today: LocalDate): Int {
        if (!isRecurring) return 0
        val lastDone = if (lastDoneDate.isBlank()) null else runCatching { LocalDate.parse(lastDoneDate) }.getOrNull()
        var date = (lastDone ?: createdDate).plusDays(1)
        var count = 0
        var guard = 0
        while (date.isBefore(today) && guard < 400) {
            if (occursTodayOn(date)) count++
            date = date.plusDays(1)
            guard++
        }
        return count
    }

    /** 当前有效的连续天数：中间错过过就视为 0。 */
    fun effectiveStreak(today: LocalDate): Int = if (missedCount(today) > 0) 0 else streak

    /** 今天完成后会变成连续多少天。 */
    fun nextStreakIfDone(today: LocalDate): Int {
        val prev = previousOccurrence(today)
        return if (prev != null && lastDoneDate == prev.toString()) streak + 1 else 1
    }

    /** 勾选完成后的状态：重复待办记到 today 并顺延连续天数；普通待办置 done。 */
    fun markedDone(today: LocalDate, nowMillis: Long): TodoItem =
        if (isRecurring) {
            copy(lastDoneDate = today.toString(), streak = nextStreakIfDone(today), doneAt = nowMillis)
        } else {
            copy(done = true, doneAt = nowMillis)
        }

    /** 取消勾选后的状态。 */
    fun markedUndone(): TodoItem =
        if (isRecurring) copy(lastDoneDate = "", streak = 0, doneAt = 0L) else copy(done = false, doneAt = 0L)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("note", note)
        put("dueDate", dueDate)
        put("startAt", startAt)
        put("repeat", repeat)
        put("fromDate", fromDate)
        put("done", done)
        put("createdAt", createdAt)
        put("doneAt", doneAt)
        put("startNotified", startNotified)
        put("lastDoneDate", lastDoneDate)
        put("lastNotifiedDate", lastNotifiedDate)
        put("streak", streak)
    }

    companion object {
        fun fromJson(o: JSONObject) = TodoItem(
            id = o.optLong("id"),
            title = o.optString("title"),
            note = o.optString("note"),
            dueDate = o.optString("dueDate"),
            startAt = o.optString("startAt"),
            repeat = o.optString("repeat"),
            fromDate = o.optString("fromDate"),
            done = o.optBoolean("done"),
            createdAt = o.optLong("createdAt"),
            doneAt = o.optLong("doneAt"),
            startNotified = o.optBoolean("startNotified", false),
            lastDoneDate = o.optString("lastDoneDate"),
            lastNotifiedDate = o.optString("lastNotifiedDate"),
            streak = o.optInt("streak", 0)
        )

        val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

        fun repeatLabel(repeat: String): String = when {
            repeat.isBlank() -> "不重复"
            repeat == "daily" -> "每天"
            repeat.startsWith("weekly:") -> {
                val days = repeat.removePrefix("weekly:").split(",")
                    .mapNotNull { it.trim().toIntOrNull() }.sorted()
                    .filter { it in 1..7 }
                    .joinToString("、") { "周${weekdayNames[it - 1]}" }
                if (days.isBlank()) "每周" else "每$days"
            }
            else -> "重复"
        }
    }
}

class TodoRepo(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("todos", Context.MODE_PRIVATE)

    fun list(): MutableList<TodoItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { TodoItem.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(items: List<TodoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun add(item: TodoItem): TodoItem {
        val items = list()
        val withId = item.copy(id = (items.maxOfOrNull { it.id } ?: 0L) + 1)
        items += withId
        save(items)
        return withId
    }

    fun update(item: TodoItem) {
        val items = list()
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item else items += item
        save(items)
    }

    fun delete(id: Long) {
        save(list().filterNot { it.id == id })
    }

    /**
     * 勾选/取消完成，返回更新后的条目。
     * 重复待办：完成 = 记到今天（连续天数顺延），取消 = 清掉今天的记录；
     * 普通待办：切换 done。
     */
    fun toggleDone(item: TodoItem): TodoItem {
        val today = LocalDate.now()
        val updated = if (item.doneEffective(today)) item.markedUndone() else item.markedDone(today, System.currentTimeMillis())
        update(updated)
        return updated
    }

    fun pendingCount(today: LocalDate = LocalDate.now()): Int =
        list().count { !it.doneEffective(today) && !it.isEnded(today) }

    /** 已经开始、今天还没完成的待办（课表主页下方展示）。 */
    /** 已经开始、今天还没完成的待办（课表主页下方展示），按紧急程度从高到低排。 */
    fun startedPending(now: LocalDateTime): List<TodoItem> {
        val today = now.toLocalDate()
        return list()
            .filter { it.startedToday(now) && !it.doneEffective(today) && !it.isEnded(today) }
            .sortedWith(
                compareBy<TodoItem> { it.urgencyRank(today) }
                    .thenBy { it.due }
                    .thenBy { it.createdAt }
            )
    }

    companion object {
        private const val KEY_ITEMS = "items"

        /** 未完成在前（按紧急程度、截止日、创建时间），已完成/已结束在后（最近完成的在前）。 */
        fun sorted(items: List<TodoItem>, today: LocalDate = LocalDate.now()): List<TodoItem> {
            val now = LocalDateTime.now()
            return items.sortedWith(
                compareBy<TodoItem> { it.doneEffective(today) || it.isEnded(today) }
                    .thenBy { if (it.doneEffective(today) || it.isEnded(today)) -it.doneAt else 0L }
                    .thenBy { it.urgencyRank(today) }
                    .thenBy { it.due }
                    .thenByDescending { it.startedToday(now) }
                    .thenBy { it.createdAt }
            )
        }
    }
}
