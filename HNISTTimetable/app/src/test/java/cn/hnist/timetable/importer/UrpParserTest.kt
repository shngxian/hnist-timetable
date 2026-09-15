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

        val week3 = UrpParser.parse(json, "2026-2027 秋（两） 第3周 星期三", "", LocalDate.of(2026, 9, 23))
        assertEquals("2026-09-07", week3.startDate)
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
