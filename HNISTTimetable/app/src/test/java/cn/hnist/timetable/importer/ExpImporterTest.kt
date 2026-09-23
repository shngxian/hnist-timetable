package cn.hnist.timetable.importer

import cn.hnist.timetable.model.NodeTime
import cn.hnist.timetable.model.Timetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ExpImporterTest {

    // 教学信息化管理系统“课表查看”接口的真实返回（截取两条，学号已替换）
    private val apiJson = """
        {"total":"2","rows":[
          {"SeatNo":"16","LabName":"电表的改装与定标","CourseName":"大学物理实验(2)","UserName":"戴佳男",
           "Weeks":"3","WeekName":"星期二","TimePartName":"晚上","ClassDate":"2026/9/22 0:00:00",
           "StartTime":"19:30","EndTime":"21:00","ClassRoom":"6411","StudentID":"20240000001"},
          {"SeatNo":"20","LabName":"气垫导轨测重力加速度","CourseName":"大学物理实验(2)","UserName":"朱曙华",
           "Weeks":"3","WeekName":"星期四","TimePartName":"晚上","ClassDate":"2026/9/24 0:00:00",
           "StartTime":"19:30","EndTime":"21:00","ClassRoom":"6410","StudentID":"20240000001"}
        ]}
    """.trimIndent()

    private val nodeTimes = listOf(
        NodeTime(1, "08:00", "08:45"), NodeTime(2, "08:55", "09:40"),
        NodeTime(3, "10:00", "10:45"), NodeTime(4, "10:55", "11:40"),
        NodeTime(5, "14:30", "15:15"), NodeTime(6, "15:25", "16:10"),
        NodeTime(7, "16:20", "17:05"), NodeTime(8, "17:15", "18:00"),
        NodeTime(9, "19:30", "20:15"), NodeTime(10, "20:25", "21:10")
    )

    @Test
    fun parsesSessionsFromApiJson() {
        val sessions = ExpImporter.parseSessions(apiJson)
        assertEquals(2, sessions.size)
        val first = sessions.first()
        assertEquals("电表的改装与定标", first.labName)
        assertEquals("大学物理实验(2)", first.courseName)
        assertEquals("戴佳男", first.teacher)
        assertEquals(LocalDate.of(2026, 9, 22), first.date)
        assertEquals(LocalTime.of(19, 30), first.start)
        assertEquals(LocalTime.of(21, 0), first.end)
        assertEquals("6411", first.room)
        assertEquals("16", first.seat)
    }

    @Test
    fun encryptPasswordMatchesWebPageAlgorithm() {
        // AES-ECB("14256200019") 的密文是 Vp26RCoB0KhZzq9W2nGSPA==，再 base64 一次后提交给服务器
        val first = java.util.Base64.getDecoder().decode(
            ExpImporter.encryptPassword("14256200019")
        ).toString(Charsets.UTF_8)
        assertEquals("Vp26RCoB0KhZzq9W2nGSPA==", first)
    }

    @Test
    fun mapsEveningExperimentToNodes9And10() {
        val nodes = ExpImporter.mapNodes(LocalTime.of(19, 30), LocalTime.of(21, 0), nodeTimes)
        assertEquals(9 to 2, nodes)
    }

    @Test
    fun mapsDaytimeExperiment() {
        // 14:40-16:05 覆盖第 5 节大半和第 6 节大半 → 5-6
        val nodes = ExpImporter.mapNodes(LocalTime.of(14, 40), LocalTime.of(16, 5), nodeTimes)
        assertEquals(5 to 2, nodes)
    }

    @Test
    fun mergeAppendsExperimentsAndReplacesPreviousBatch() {
        val timetable = Timetable(
            semesterName = "2026-2027 秋季学期",
            startDate = "2026-09-07",
            totalWeeks = 20,
            studentName = "张三",
            studentId = "20240000001",
            nodeTimes = nodeTimes,
            courses = listOf(
                cn.hnist.timetable.model.Course(
                    courseName = "概率论与数理统计A", courseNumber = "71A5G0110", sequence = "01",
                    teacher = "吴专保", credit = 3.0, property = "必修", examType = "考试",
                    day = 1, startNode = 1, nodeCount = 2, weeks = (1..16).toList(),
                    weekDesc = "1-16周", campus = "南院", building = "15号教学楼", room = "15213",
                    colorIndex = 1
                )
            ),
            unscheduled = emptyList(),
            importedAt = 0L
        )
        val sessions = ExpImporter.parseSessions(apiJson)

        val first = ExpImporter.merge(timetable, sessions)
        assertEquals(3, first.courses.size)
        val exp = first.courses.filter { it.courseNumber == ExpImporter.EXP_COURSE_NUMBER }
        assertEquals(2, exp.size)
        val dianbiao = exp.first { it.courseName == "电表的改装与定标" }
        assertEquals(2, dianbiao.day)                       // 9/22 是周二
        assertEquals(3, dianbiao.weeks.single())            // 开学 9/7 → 9/22 是第 3 周
        assertEquals(9, dianbiao.startNode)
        assertEquals(2, dianbiao.nodeCount)
        assertTrue(dianbiao.room.contains("6411"))
        assertTrue(dianbiao.room.contains("16座"))

        // 再导入一次：旧实验被清掉，不会翻倍
        val second = ExpImporter.merge(first, sessions)
        assertEquals(3, second.courses.size)
        assertEquals(
            first.courses.filter { it.courseNumber == ExpImporter.EXP_COURSE_NUMBER }.size,
            second.courses.filter { it.courseNumber == ExpImporter.EXP_COURSE_NUMBER }.size
        )
        // 普通课还在
        assertTrue(second.courses.any { it.courseName == "概率论与数理统计A" })
    }
}
