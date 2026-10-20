package cn.hnist.timetable.importer

import cn.hnist.timetable.model.Timetable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** 用教务系统真实返回的 JSON（2026-2027 秋季学期）验证解析结果。 */
class UrpParserTest {

    private val json: String =
        javaClass.classLoader!!.getResourceAsStream("schedule.json")!!.bufferedReader().readText()

    private val navText = "2026-2027 秋（两） 第1周 星期日"
    private val today: LocalDate = LocalDate.of(2026, 9, 13)   // 星期日

    @Test
    fun parsesAllCourses() {
        val tt = UrpParser.parse(json, navText, "张三", today)

        assertEquals(15, tt.courses.size)          // 12 门课里 10 门有固定时间，共 15 条安排
        assertEquals(2, tt.unscheduled.size)       // 两门实验课未排时间
        assertEquals(10, tt.nodeTimes.size)
        assertEquals("08:00", tt.nodeTimes.first().start)
        assertEquals("21:10", tt.nodeTimes.last().end)
        assertEquals("20240000001", tt.studentId)
        assertEquals("张三", tt.studentName)
        assertEquals("2026-2027 秋季学期", tt.semesterName)
        assertEquals(20, tt.totalWeeks)
    }

    @Test
    fun computesSemesterStartFromNavbar() {
        val tt = UrpParser.parse(json, navText, "", today)
        // 第 1 周星期日是 9 月 13 日，则第 1 周周一为 9 月 7 日
        assertEquals("2026-09-07", tt.startDate)

        // 第 3 周（csz=3，校历从第 0 周起算，第 3 周 = 9/21-9/27）
        val week3json = json.replaceFirst("\"csz\":\"1\"", "\"csz\":\"3\"")
        val week3 = UrpParser.parse(week3json, "2026-2027 秋（两） 第3周 星期三", "", LocalDate.of(2026, 9, 23))
        assertEquals("2026-09-07", week3.startDate)
    }

    @Test
    fun ignoresStaleWeekdayInNavbar() {
        // 周次优先取接口 csz（服务器实时算出的当前周）；页面顶栏的“星期几”即使缓存了旧值也不影响。
        // 实际 bug：2026-09-22（周二，第 3 周）导入时页面还显示“星期一”，开学日被算成 09-08。
        val week3json = json.replaceFirst("\"csz\":\"1\"", "\"csz\":\"3\"")
        val stale = UrpParser.parse(week3json, "2026-2027 秋（两） 第3周 星期一", "", LocalDate.of(2026, 9, 22))
        assertEquals("2026-09-07", stale.startDate)
    }

    @Test
    fun fallsBackToNavWeekWithoutCsz() {
        // 接口没给 csz 时，退回页面顶栏的“第 N 周”
        val noCsz = json.replaceFirst("\"csz\":\"1\"", "\"csz\":\"\"")
        val tt = UrpParser.parse(noCsz, "2026-2027 秋（两） 第3周 星期三", "", LocalDate.of(2026, 9, 23))
        assertEquals("2026-09-07", tt.startDate)
    }

    @Test
    fun reconcileKeepsLocalStartOnWeekMultipleDrift() {
        // 学校周次计数漂移整周（这里差 6 周）时，保留本地已校准的开学日期
        assertEquals(
            "2026-09-07",
            UrpParser.reconcileStartDate("2026-09-07", "2026-10-19")
        )
        // 相差不是整周倍数 → 学期更替，采用新日期
        assertEquals(
            "2027-03-01",
            UrpParser.reconcileStartDate("2026-09-07", "2027-03-01")
        )
        // 本地没有记录 → 采用新日期；相同 → 原样
        assertEquals("2026-09-07", UrpParser.reconcileStartDate(null, "2026-09-07"))
        assertEquals("2026-09-07", UrpParser.reconcileStartDate("2026-09-07", "2026-09-07"))
    }

    @Test
    fun knownCalendarOverridesCounterConfusion() {
        // 用户实际案例：教务页面顶栏的周次计数与校历差一周（页面说"第2周"，实际已是第 3 周；
        // 甚至显示"第0周"）。学期名匹配到校历表时，开学日期永远是权威的 9/7。
        val drift = UrpParser.parse(json, "2026-2027 秋（两） 第2周 星期二", "", LocalDate.of(2026, 9, 22))
        assertEquals("2026-09-07", drift.startDate)
        val zero = UrpParser.parse(json, "2026-2027 秋（两） 第0周 星期二", "", LocalDate.of(2026, 9, 22))
        assertEquals("2026-09-07", zero.startDate)
    }

    @Test
    fun parsesWeekBitmapsAndCleansText() {
        val tt = UrpParser.parse(json, navText, "", today)

        val physicsOdd = tt.courses.first { it.courseName == "大学物理(2)" && it.day == 4 }
        assertEquals((1..15 step 2).toList(), physicsOdd.weeks)
        assertEquals("1-16周单周", physicsOdd.weekDesc)
        assertEquals("15413", physicsOdd.room)                 // △ 已去掉
        assertEquals("徐正慰", physicsOdd.teacher)             // * 已去掉

        val marxEven = tt.courses.first { it.courseName == "马克思主义基本原理" && it.day == 4 }
        assertEquals((2..16 step 2).toList(), marxEven.weeks)

        val algo = tt.courses.filter { it.courseName == "算法与数据结构（二）" }
        assertEquals(2, algo.size)
        assertEquals("甘靖 邓琦", algo.first().teacher)
        assertTrue(algo.all { it.weeks == (1..8).toList() })

        val english = tt.courses.first { it.courseName == "大学外语(3)" }
        assertEquals(5, english.day)
        assertEquals(1, english.startNode)
        assertEquals(2, english.endNode)
        assertEquals((2..17).toList(), english.weeks)
        assertEquals("限选", english.property)

        // 同一门课的不同安排颜色一致，不同课程尽量不同色
        val colorsByName = tt.courses.groupBy { it.courseName }.mapValues { (_, v) -> v.map { it.colorIndex }.toSet() }
        assertTrue(colorsByName.values.all { it.size == 1 })
        assertEquals(colorsByName.size, colorsByName.values.map { it.first() }.toSet().size)
    }

    @Test
    fun jsonRoundTripAndSampleOutput() {
        val tt = UrpParser.parse(json, navText, "张三", today)
        val restored = Timetable.fromJson(JSONObject(tt.toJson().toString()))
        assertEquals(tt.courses, restored.courses)
        assertEquals(tt.nodeTimes, restored.nodeTimes)
        assertEquals(tt.unscheduled, restored.unscheduled)
        assertEquals(tt.startDate, restored.startDate)

        // 导出一份 App 内部格式的课表，方便推到模拟器里验证界面
        val out = File("build/timetable-sample.json")
        out.parentFile.mkdirs()
        out.writeText(tt.toJson().toString())
    }
}
