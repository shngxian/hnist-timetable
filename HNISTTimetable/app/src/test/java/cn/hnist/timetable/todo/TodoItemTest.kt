package cn.hnist.timetable.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TodoItemTest {

    private fun item(
        repeat: String, startAt: String, lastDoneDate: String = "", streak: Int = 0,
        done: Boolean = false, fromDate: String = "", dueDate: String = "", createdOn: LocalDate? = null,
        dueTime: String = ""
    ) = TodoItem(
        id = 1, title = "背单词", note = "", dueDate = dueDate, dueTime = dueTime, startAt = startAt, repeat = repeat,
        fromDate = fromDate, done = done,
        createdAt = createdOn?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L,
        doneAt = 0, startNotified = false,
        lastDoneDate = lastDoneDate, lastNotifiedDate = "", streak = streak
    )

    private val monday = LocalDate.of(2026, 9, 14)   // 周一

    @Test
    fun dailyTaskResetsNextDay() {
        val daily = item("daily", "07:30").markedDone(monday, 1L)
        assertTrue(daily.doneEffective(monday))
        assertFalse(daily.doneEffective(monday.plusDays(1)))   // 第二天自动恢复未完成
        assertEquals(1, daily.streak)
    }

    @Test
    fun streakCountsConsecutiveDays() {
        var t = item("daily", "")
        t = t.markedDone(monday, 1L)
        t = t.markedDone(monday.plusDays(1), 2L)
        t = t.markedDone(monday.plusDays(2), 3L)
        assertEquals(3, t.streak)
        t = t.markedDone(monday.plusDays(5), 4L)        // 中间断了两天
        assertEquals(1, t.streak)
        assertEquals(0, t.markedUndone().streak)
    }

    @Test
    fun weeklyStreakFollowsOccurrencesNotCalendarDays() {
        var t = item("weekly:1,3", "")                  // 周一、周三
        t = t.markedDone(monday, 1L)
        assertEquals(monday, t.previousOccurrence(monday.plusDays(2)))
        t = t.markedDone(monday.plusDays(2), 2L)         // 周三：和周一是连续的两次
        assertEquals(2, t.streak)
        assertEquals(0, t.missedCount(monday.plusDays(4)))   // 周五：周四本来就不用做，不算错过
    }

    @Test
    fun missedDaysBreakStreakButNeedNoMakeUp() {
        // 9月10日创建、从没完成过：11、12、13 三天错过
        val never = item("daily", "", createdOn = LocalDate.of(2026, 9, 10))
        assertEquals(3, never.missedCount(monday))
        assertEquals(1, never.nextStreakIfDone(monday))

        // 连续 5 天后 13 号没做：错过 1 天，连续天数视为 0，今天完成从 1 重新计
        val lapsed = item("daily", "", lastDoneDate = "2026-09-12", streak = 5)
        assertEquals(1, lapsed.missedCount(monday))
        assertEquals(0, lapsed.effectiveStreak(monday))
        assertEquals(1, lapsed.nextStreakIfDone(monday))
        assertTrue(lapsed.doneEffective(LocalDate.of(2026, 9, 12)))
        assertFalse(lapsed.doneEffective(monday))              // 今天只需要完成今天这一次

        // 昨天做了：没错过，今天完成后连续 6 天
        val fine = item("daily", "", lastDoneDate = "2026-09-13", streak = 5)
        assertEquals(0, fine.missedCount(monday))
        assertEquals(5, fine.effectiveStreak(monday))
        assertEquals(6, fine.nextStreakIfDone(monday))
    }

    @Test
    fun dailyStartedAndNextOccurrence() {
        val daily = item("daily", "07:30")
        assertFalse(daily.startedToday(monday.atTime(7, 0)))
        assertTrue(daily.startedToday(monday.atTime(7, 30)))
        assertTrue(item("daily", "").startedToday(monday.atTime(0, 1)))   // 没设时刻：全天都算开始

        assertEquals(monday.atTime(7, 30), daily.nextStartAfter(monday.atTime(7, 0)))
        assertEquals(monday.plusDays(1).atTime(7, 30), daily.nextStartAfter(monday.atTime(8, 0)))
        assertNull(item("daily", "").nextStartAfter(monday.atTime(8, 0)))  // 没设时刻不提醒
    }

    @Test
    fun weeklyTaskOnlyOnChosenDays() {
        val weekly = item("weekly:1,3", "20:00")        // 周一、周三
        assertTrue(weekly.occursToday(monday))
        assertFalse(weekly.occursToday(monday.plusDays(1)))
        assertFalse(weekly.startedToday(monday.plusDays(1).atTime(21, 0)))   // 周二不出现
        assertEquals(monday.plusDays(2).atTime(20, 0), weekly.nextStartAfter(monday.atTime(21, 0)))
        assertEquals("每周一、周三", TodoItem.repeatLabel(weekly.repeat))
        assertEquals("每天", TodoItem.repeatLabel("daily"))
    }

    @Test
    fun recurringTaskLimitedToPeriod() {
        // 考试周任务：9月21日到9月25日每天 20:00
        val exam = item("daily", "20:00", fromDate = "2026-09-21", dueDate = "2026-09-25")
        assertFalse(exam.occursToday(monday))                            // 还没到开始日期
        assertFalse(exam.startedToday(monday.atTime(21, 0)))
        assertEquals(LocalDate.of(2026, 9, 21).atTime(20, 0), exam.nextStartAfter(monday.atTime(21, 0)))

        val inside = LocalDate.of(2026, 9, 23)
        assertTrue(exam.occursToday(inside))
        assertTrue(exam.startedToday(inside.atTime(20, 0)))
        assertFalse(exam.isEnded(inside.atTime(12, 0)))

        val lastDay = LocalDate.of(2026, 9, 25)
        assertEquals(lastDay.atTime(20, 0), exam.nextStartAfter(LocalDate.of(2026, 9, 24).atTime(21, 0)))
        assertNull(exam.nextStartAfter(lastDay.atTime(21, 0)))          // 最后一天过后不再提醒

        val after = LocalDate.of(2026, 9, 26)
        assertTrue(exam.isEnded(after.atTime(12, 0)))
        assertFalse(exam.occursToday(after))
        assertFalse(exam.startedToday(after.atTime(21, 0)))            // 结束后不再出现在主页
        assertEquals(2L, exam.daysLeft(inside))
    }

    @Test
    fun recurringWithoutPeriodRunsForever() {
        val forever = item("daily", "08:00")
        assertFalse(forever.isEnded(monday.plusYears(1).atTime(12, 0)))
        assertTrue(forever.occursToday(monday.plusYears(1)))
        assertNull(forever.daysLeft(monday))
    }

    @Test
    fun oneOffTaskNotifiesOnce() {
        val once = item("", "2026-09-14T22:25")
        assertEquals(monday.atTime(22, 25), once.nextStartAfter(monday.atTime(22, 0)))
        assertNull(once.copy(startNotified = true).nextStartAfter(monday.atTime(22, 0)))
        assertTrue(once.startedToday(monday.atTime(22, 30)))
        assertTrue(once.markedDone(monday, 1L).done)
        assertTrue(once.markedDone(monday, 1L).doneEffective(monday.plusDays(3)))   // 普通待办不会自动恢复
        assertFalse(once.copy(dueDate = "2026-09-10").isEnded(monday.atTime(12, 0)))   // 普通待办过期不算“结束”
    }

    @Test
    fun jsonRoundTrip() {
        val original = item("weekly:2,5", "09:00", lastDoneDate = "2026-09-13", streak = 4, fromDate = "2026-09-01", dueDate = "2026-12-31")
        val restored = TodoItem.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun dueTimeMakesOverduePrecise() {
        val t = item("", "", dueDate = "2026-09-22", dueTime = "09:00")
        val today = LocalDate.of(2026, 9, 22)
        assertTrue(t.isOverdueAt(today.atTime(9, 1)))          // 过了截止时刻 → 过期
        assertFalse(t.isOverdueAt(today.atTime(8, 59)))
        assertEquals(0, t.urgencyRank(today, today.atTime(10, 0)))   // 已过时刻：过期级
        assertEquals(1, t.urgencyRank(today, today.atTime(8, 0)))    // 没到时刻：仍是今天
        assertEquals("09:00", t.dueTime)
        assertEquals(java.time.LocalTime.of(9, 0), t.dueTimeOfDay)
        assertEquals(today.atTime(9, 0), t.dueAt)
    }

    @Test
    fun noDueTimeMeansWholeDay() {
        val allDay = item("", "", dueDate = "2026-09-22")
        val today = LocalDate.of(2026, 9, 22)
        assertFalse(allDay.isOverdueAt(today.atTime(22, 0)))   // 没设时刻：当天结束前都不算过期
        assertTrue(allDay.isOverdueAt(today.plusDays(1).atStartOfDay()))
        assertEquals(1, allDay.urgencyRank(today, today.atTime(22, 0)))
        assertNull(allDay.dueTimeOfDay)
    }

    @Test
    fun recurringEndsAtDueTimeOnLastDay() {
        val t = item("daily", "07:00", dueDate = "2026-09-25", dueTime = "18:00")
        assertFalse(t.isEnded(LocalDate.of(2026, 9, 25).atTime(17, 59)))  // 最后一天没到时刻：未结束
        assertTrue(t.isEnded(LocalDate.of(2026, 9, 25).atTime(18, 1)))    // 过了时刻：结束
        assertFalse(t.isEnded(LocalDate.of(2026, 9, 24).atTime(23, 0)))   // 最后一天之前：未结束
    }

    @Test
    fun dueTimeRoundTripsThroughJson() {
        val original = item("", "", dueDate = "2026-09-30", dueTime = "17:30")
        val restored = TodoItem.fromJson(original.toJson())
        assertEquals("17:30", restored.dueTime)
        assertEquals(java.time.LocalTime.of(17, 30), restored.dueTimeOfDay)
        // 旧数据没有 dueTime 字段，读出来是空串，行为不变
        val legacy = TodoItem.fromJson(item("", "", dueDate = "2026-09-30").toJson().apply { remove("dueTime") })
        assertEquals("", legacy.dueTime)
        assertNull(legacy.dueTimeOfDay)
    }
}
