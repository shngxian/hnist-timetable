package cn.hnist.timetable.importer

import cn.hnist.timetable.model.Course
import cn.hnist.timetable.model.NodeTime
import cn.hnist.timetable.model.Timetable
import cn.hnist.timetable.model.UnscheduledCourse
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 解析湖南理工学院 URP 教务系统的课表接口：
 * POST /student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback
 *
 * 关键字段：
 *  - xkxx[*][课程号_课序号].timeAndPlaceList[*]：classDay(1-7)、classSessions(起始节)、
 *    continuingSession(连续节数)、classWeek(24 位周次位图，下标 0 = 第 1 周)
 *  - jcsjbs：节次时间表，kssj/jssj 为 HHmm
 *  - csz：当前周
 */
object UrpParser {

    const val PALETTE_SIZE = 12

    class ParseException(message: String) : Exception(message)

    fun parse(json: String, navText: String, studentName: String, today: LocalDate): Timetable {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ParseException("教务系统返回的数据不是有效的课表格式")
        }

        val xkxx = root.optJSONArray("xkxx")
            ?: throw ParseException("课表数据中没有找到选课信息(xkxx)")

        val nodeTimes = parseNodeTimes(root.optJSONArray("jcsjbs"))

        val courses = mutableListOf<Course>()
        val unscheduled = mutableListOf<UnscheduledCourse>()
        var studentId = ""
        var planNumber = ""

        val colorByName = LinkedHashMap<String, Int>()
        fun colorFor(name: String): Int = colorByName.getOrPut(name) { colorByName.size % PALETTE_SIZE }

        for (i in 0 until xkxx.length()) {
            val group = xkxx.optJSONObject(i) ?: continue
            val keys = group.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val c = group.optJSONObject(key) ?: continue
                val id = c.optJSONObject("id")
                if (studentId.isEmpty()) studentId = id?.optString("studentNumber").orEmpty()
                if (planNumber.isEmpty()) planNumber = id?.optString("executiveEducationPlanNumber").orEmpty()

                val name = c.optString("courseName").trim()
                val teacher = cleanTeacher(c.optString("attendClassTeacher"))
                val credit = c.optDouble("unit", 0.0)
                val property = c.optString("coursePropertiesName").trim()
                val examType = c.optString("examTypeName").trim()
                val weekDescAll = c.optString("skzcs").trim()

                val places = c.optJSONArray("timeAndPlaceList")
                if (places == null || places.length() == 0) {
                    unscheduled += UnscheduledCourse(name, teacher, weekDescAll, credit)
                    continue
                }

                for (j in 0 until places.length()) {
                    val p = places.optJSONObject(j) ?: continue
                    val day = p.optInt("classDay", 0)
                    val start = p.optInt("classSessions", 0)
                    val count = p.optInt("continuingSession", 0)
                    val weeks = parseWeekBits(p.optString("classWeek"))
                    if (day !in 1..7 || start < 1 || count < 1 || weeks.isEmpty()) {
                        // 时间信息不完整的安排同样放到“未安排”列表里，避免丢课
                        unscheduled += UnscheduledCourse(name, teacher, p.optString("weekDescription", weekDescAll), credit)
                        continue
                    }
                    courses += Course(
                        courseName = name,
                        courseNumber = id?.optString("coureNumber").orEmpty(),
                        sequence = id?.optString("coureSequenceNumber").orEmpty(),
                        teacher = teacher,
                        credit = credit,
                        property = property,
                        examType = examType,
                        day = day,
                        startNode = start,
                        nodeCount = count,
                        weeks = weeks,
                        weekDesc = p.optString("weekDescription").ifBlank { weekDescAll },
                        campus = p.optString("campusName").trim(),
                        building = p.optString("teachingBuildingName").trim(),
                        room = cleanRoom(p.optString("classroomName")),
                        colorIndex = colorFor(name)
                    )
                }
            }
        }

        if (courses.isEmpty() && unscheduled.isEmpty()) {
            throw ParseException("本学期没有查询到任何课程")
        }

        val navWeek = Regex("第\\s*(\\d+)\\s*周").find(navText)?.groupValues?.get(1)?.toIntOrNull()
            ?: root.optString("csz").toIntOrNull()
        val navWeekday = parseWeekday(navText) ?: today.dayOfWeek.value

        val startDate = if (navWeek != null && navWeek >= 1) {
            today.minusDays((navWeekday - 1).toLong()).minusWeeks((navWeek - 1).toLong())
        } else {
            today.minusDays((today.dayOfWeek.value - 1).toLong())
        }

        val maxWeekUsed = courses.maxOfOrNull { it.weeks.max() } ?: 0
        val totalWeeks = maxOf(20, maxWeekUsed).coerceAtMost(30)

        return Timetable(
            semesterName = semesterName(navText, planNumber),
            startDate = startDate.toString(),
            totalWeeks = totalWeeks,
            studentName = studentName.trim(),
            studentId = studentId,
            nodeTimes = nodeTimes,
            courses = courses.sortedWith(compareBy({ it.day }, { it.startNode })),
            unscheduled = unscheduled.distinct(),
            importedAt = System.currentTimeMillis()
        )
    }

    private fun parseNodeTimes(arr: JSONArray?): List<NodeTime> {
        if (arr == null) return emptyList()
        val list = mutableListOf<NodeTime>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val node = o.optString("jc").toIntOrNull() ?: continue
            list += NodeTime(node, formatHHmm(o.optString("kssj")), formatHHmm(o.optString("jssj")))
        }
        return list.sortedBy { it.node }
    }

    private fun formatHHmm(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 4) "${digits.substring(0, 2)}:${digits.substring(2)}" else raw
    }

    internal fun parseWeekBits(bits: String): List<Int> =
        bits.mapIndexedNotNull { index, ch -> if (ch == '1') index + 1 else null }

    internal fun cleanTeacher(raw: String): String =
        raw.replace("*", " ").split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")

    internal fun cleanRoom(raw: String): String = raw.replace("△", "").trim()

    private fun parseWeekday(text: String): Int? {
        val m = Regex("星期([一二三四五六日天])").find(text) ?: return null
        return when (m.groupValues[1]) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; else -> 7
        }
    }

    private fun semesterName(navText: String, planNumber: String): String {
        Regex("(\\d{4}-\\d{4})\\s*([春夏秋冬])?").find(navText)?.let { m ->
            val year = m.groupValues[1]
            val season = m.groupValues[2]
            return if (season.isNotEmpty()) "$year ${season}季学期" else "$year 学年"
        }
        Regex("(\\d{4}-\\d{4})-(\\d)").find(planNumber)?.let { m ->
            return "${m.groupValues[1]} 第${m.groupValues[2]}学期"
        }
        return "本学期"
    }
}
