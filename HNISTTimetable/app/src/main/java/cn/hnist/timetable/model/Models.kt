package cn.hnist.timetable.model

import org.json.JSONArray
import org.json.JSONObject

/** 一次具体的上课安排（同一门课在不同时间/地点会拆成多条）。 */
data class Course(
    val courseName: String,
    val courseNumber: String,
    val sequence: String,
    val teacher: String,
    val credit: Double,
    val property: String,      // 必修 / 限选 ...
    val examType: String,      // 考试 / 考查
    val day: Int,              // 1=周一 ... 7=周日
    val startNode: Int,        // 起始节次，从 1 开始
    val nodeCount: Int,        // 连续节数
    val weeks: List<Int>,      // 上课周次
    val weekDesc: String,      // 如 "1-16周单周"
    val campus: String,
    val building: String,
    val room: String,
    val colorIndex: Int
) {
    val endNode: Int get() = startNode + nodeCount - 1

    fun isInWeek(week: Int): Boolean = weeks.contains(week)

    /** 教室简称，用于课表格子里显示。 */
    val roomShort: String
        get() = when {
            room.isNotBlank() -> room
            building.isNotBlank() -> building
            else -> campus
        }

    /** 完整地点，用于详情页。 */
    val locationFull: String
        get() = listOf(campus, building, room).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("courseName", courseName)
        put("courseNumber", courseNumber)
        put("sequence", sequence)
        put("teacher", teacher)
        put("credit", credit)
        put("property", property)
        put("examType", examType)
        put("day", day)
        put("startNode", startNode)
        put("nodeCount", nodeCount)
        put("weeks", JSONArray(weeks))
        put("weekDesc", weekDesc)
        put("campus", campus)
        put("building", building)
        put("room", room)
        put("colorIndex", colorIndex)
    }

    companion object {
        fun fromJson(o: JSONObject): Course {
            val weeksArr = o.optJSONArray("weeks") ?: JSONArray()
            val weeks = (0 until weeksArr.length()).map { weeksArr.getInt(it) }
            return Course(
                courseName = o.optString("courseName"),
                courseNumber = o.optString("courseNumber"),
                sequence = o.optString("sequence"),
                teacher = o.optString("teacher"),
                credit = o.optDouble("credit", 0.0),
                property = o.optString("property"),
                examType = o.optString("examType"),
                day = o.optInt("day", 1),
                startNode = o.optInt("startNode", 1),
                nodeCount = o.optInt("nodeCount", 1),
                weeks = weeks,
                weekDesc = o.optString("weekDesc"),
                campus = o.optString("campus"),
                building = o.optString("building"),
                room = o.optString("room"),
                colorIndex = o.optInt("colorIndex", 0)
            )
        }
    }
}

/** 没有固定上课时间的课程（如实验课），只在列表里展示。 */
data class UnscheduledCourse(
    val courseName: String,
    val teacher: String,
    val weekDesc: String,
    val credit: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("courseName", courseName)
        put("teacher", teacher)
        put("weekDesc", weekDesc)
        put("credit", credit)
    }

    companion object {
        fun fromJson(o: JSONObject) = UnscheduledCourse(
            courseName = o.optString("courseName"),
            teacher = o.optString("teacher"),
            weekDesc = o.optString("weekDesc"),
            credit = o.optDouble("credit", 0.0)
        )
    }
}

/** 节次对应的上下课时间。 */
data class NodeTime(val node: Int, val start: String, val end: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("node", node)
        put("start", start)
        put("end", end)
    }

    companion object {
        fun fromJson(o: JSONObject) = NodeTime(o.optInt("node"), o.optString("start"), o.optString("end"))
    }
}

/** 一整份课表：学期信息 + 课程列表。 */
data class Timetable(
    val semesterName: String,
    val startDate: String,          // 第 1 周周一，yyyy-MM-dd
    val totalWeeks: Int,
    val studentName: String,
    val studentId: String,
    val nodeTimes: List<NodeTime>,
    val courses: List<Course>,
    val unscheduled: List<UnscheduledCourse>,
    val importedAt: Long
) {
    val maxNode: Int
        get() {
            val fromCourses = courses.maxOfOrNull { it.endNode } ?: 0
            val fromTimes = nodeTimes.maxOfOrNull { it.node } ?: 0
            return maxOf(fromCourses, fromTimes, 10)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("semesterName", semesterName)
        put("startDate", startDate)
        put("totalWeeks", totalWeeks)
        put("studentName", studentName)
        put("studentId", studentId)
        put("nodeTimes", JSONArray().also { arr -> nodeTimes.forEach { arr.put(it.toJson()) } })
        put("courses", JSONArray().also { arr -> courses.forEach { arr.put(it.toJson()) } })
        put("unscheduled", JSONArray().also { arr -> unscheduled.forEach { arr.put(it.toJson()) } })
        put("importedAt", importedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Timetable {
            fun <T> readArray(key: String, mapper: (JSONObject) -> T): List<T> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { mapper(arr.getJSONObject(it)) }
            }
            return Timetable(
                semesterName = o.optString("semesterName"),
                startDate = o.optString("startDate"),
                totalWeeks = o.optInt("totalWeeks", 20),
                studentName = o.optString("studentName"),
                studentId = o.optString("studentId"),
                nodeTimes = readArray("nodeTimes") { NodeTime.fromJson(it) },
                courses = readArray("courses") { Course.fromJson(it) },
                unscheduled = readArray("unscheduled") { UnscheduledCourse.fromJson(it) },
                importedAt = o.optLong("importedAt", 0L)
            )
        }
    }
}
