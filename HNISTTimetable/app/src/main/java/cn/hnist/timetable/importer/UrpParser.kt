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

        // 周次以教务处发布的校历为准（jwc.hnist.cn/bszn/xl.htm，本学期校历：2026.9.7-2027.1.10
        // 共 18 周，第一周 = 9 月 7 日-9 月 13 日，无第 0 周），页面顶栏文字与校历同源、页面新鲜时是准的；
        // csz 实测是页面选周控件的值而非实时周，不作依据。页面可能整页缓存数周之久，
        // 缓存问题在 LoginActivity 侧通过导入期间禁用缓存解决。
        val navWeek = Regex("第\\s*(\\d+)\\s*周").find(navText)?.groupValues?.get(1)?.toIntOrNull()
            ?: root.optString("csz").toIntOrNull()

        // 网页顶栏的“星期几”可能是缓存的旧值（周二还显示周一），会让开学日期错一天、
        // 整个网格的日期跟着偏移；星期一律按手机的实际日期算，页面只提供“第几周”。
        val weekday = today.dayOfWeek.value

        val startDate = if (navWeek != null && navWeek >= 1) {
            today.minusDays((weekday - 1).toLong()).minusWeeks((navWeek - 1).toLong())
        } else {
            today.minusDays((weekday - 1).toLong())
        }

        val maxWeekUsed = courses.maxOfOrNull { it.weeks.max() } ?: 0
        val totalWeeks = maxOf(20, maxWeekUsed).coerceAtMost(30)

        // 教务处校历（jwc.hnist.cn/bszn/xl.htm）核实的开学日期是权威源：学期名匹配到就
        // 直接采用，页面顶栏的周次计数无论滞后还是带偏都不影响开学日期；
        // 查不到（新学期校历还没核进 App）才用页面推算值，实验课导入会进一步自动校正。
        val semester = semesterName(navText, planNumber)
        val finalStartDate = CALENDAR_START_DATES[semester] ?: startDate.toString()

        return Timetable(
            semesterName = semester,
            startDate = finalStartDate,
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

    /**
     * 重新导入时裁决开学日期。教务页面的周次计数偶尔会滞后/带偏（差恰好几个整周），
     * 若新日期与本地已校准日期相差整周倍数（≤8 周），视为同一学期的计数漂移，保留原日期；
     * 相差更大或本地没有记录，视为学期更替，采用新日期。
     */
    fun reconcileStartDate(oldStart: String?, newStart: String): String {
        val new = runCatching { LocalDate.parse(newStart) }.getOrNull() ?: return newStart
        val old: LocalDate? = oldStart?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (old == null || old == new) return newStart
        val days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(old, new))
        return if (days % 7 == 0L && days / 7 in 1..8) old.toString() else newStart
    }

    /**
     * 教务处校历（jwc.hnist.cn/bszn/xl.htm）核实过的开学日期：学期名 → 第 1 周周一。
     * 页面顶栏的周次计数偶尔滞后/带偏（差恰好整周），学期名匹配到时以校历为准，
     * 自动纠正计数漂移；新学期发布校历后在此表加一行并更新 App 即可。
     */
    val CALENDAR_START_DATES = mapOf(
        // 校历：秋季学期 2026.9.7-2027.1.10 共 18 周，第一周 = 9 月 7 日-9 月 13 日
        "2026-2027 秋季学期" to "2026-09-07"
    )
}
