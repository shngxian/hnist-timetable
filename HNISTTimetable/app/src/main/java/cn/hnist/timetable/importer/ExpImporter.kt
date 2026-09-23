package cn.hnist.timetable.importer

import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.model.Course
import cn.hnist.timetable.model.NodeTime
import cn.hnist.timetable.model.Timetable
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 实验课一键导入（湖南理工学院教学信息化管理系统，实验选课 211.69.224.126:7100）。
 *
 * 流程与网页端一致：
 *  1. POST /BaseInfo/Login/ValidateLogin（uname + 加密密码，密码 = AES-ECB 后做两次 base64）
 *  2. POST /BaseInfo/Login/GetRoleUser（选择学生角色，建立会话）
 *  3. POST /XPK/StuCourseElectiveLook/LoadTableInfo（本学期已选的全部实验，即“课表查看”页）
 */
object ExpImporter {

    /** 实验课在课表里的标记：重复导入时先移除上一批，再追加新的。 */
    const val EXP_COURSE_NUMBER = "EXP"

    private const val HOST = "http://211.69.224.126:7100"
    private const val AES_KEY = "1234567891234567"

    class ExpImportException(message: String) : Exception(message)

    /** 一条实验安排（学生的一次选课）。 */
    data class ExpSession(
        val labName: String,
        val courseName: String,
        val teacher: String,
        val date: LocalDate,
        val start: LocalTime,
        val end: LocalTime,
        val room: String,
        val seat: String
    )

    /** 网页端 Login.js 的密码加密：haha.he(Encrypt3(pwd)) = base64(base64(AES-ECB-PKCS7(pwd)))。 */
    fun encryptPassword(raw: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        val b64 = java.util.Base64.getEncoder()
        val first = b64.encodeToString(cipher.doFinal(raw.toByteArray(Charsets.UTF_8)))
        return b64.encodeToString(first.toByteArray(Charsets.UTF_8))
    }

    // ---------- 解析与合并（不依赖网络，可单测） ----------

    /** 解析 LoadTableInfo 的返回 JSON。 */
    fun parseSessions(json: String): List<ExpSession> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("rows") ?: return emptyList()
        val list = mutableListOf<ExpSession>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dateText = o.optString("ClassDate").trim().split(" ").firstOrNull() ?: continue
            // ClassDate 形如 2026/9/22（非零填充），手动拆解成年月日
            val date = runCatching {
                val p = dateText.split("/").map { it.toInt() }
                LocalDate.of(p[0], p[1], p[2])
            }.getOrNull() ?: continue
            val start = parseTime(o.optString("StartTime")) ?: continue
            val end = parseTime(o.optString("EndTime")) ?: continue
            list += ExpSession(
                labName = o.optString("LabName").trim(),
                courseName = o.optString("CourseName").trim(),
                teacher = cleanTeacher(o.optString("UserName")),
                date = date,
                start = start,
                end = end,
                room = o.optString("ClassRoom").trim(),
                seat = o.optString("SeatNo").trim()
            )
        }
        return list.distinctBy { "${it.labName}|${it.courseName}|${it.date}|${it.start}|${it.room}|${it.seat}" }
    }

    private fun parseTime(text: String): LocalTime? {
        val parts = text.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    fun cleanTeacher(raw: String): String =
        raw.replace("*", " ").split(" ").filter { it.isNotBlank() }.joinToString(" ")

    /**
     * 把实验的上课时间段映射到节次：与某节重叠超过该节时长一半的都算进去。
     * 如晚上 19:30-21:00 恰好落在第 9-10 节（19:30-20:15 / 20:25-21:10）。
     */
    fun mapNodes(start: LocalTime, end: LocalTime, nodeTimes: List<NodeTime>): Pair<Int, Int>? {
        var first: Int? = null
        var last = 0
        for (n in nodeTimes.sortedBy { it.node }) {
            val ns = parseTime(n.start) ?: continue
            val ne = parseTime(n.end) ?: continue
            val overlapStart = maxOf(start, ns)
            val overlapEnd = minOf(end, ne)
            if (!overlapEnd.isAfter(overlapStart)) continue
            val nodeLen = Duration.between(ns, ne).seconds
            val overlap = Duration.between(overlapStart, overlapEnd).seconds
            if (nodeLen == 0L || overlap * 2 >= nodeLen) {
                if (first == null) first = n.node
                last = n.node
            }
        }
        first ?: return null
        return first to (last - first + 1)
    }

    /** 合并进现有课表：移除上一批实验课，按开学日期折算周次后追加。 */
    fun merge(timetable: Timetable, sessions: List<ExpSession>): Timetable {
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull()
        val kept = timetable.courses.filterNot { it.courseNumber == EXP_COURSE_NUMBER }
        val expCourses = mutableListOf<Course>()
        for (s in sessions) {
            val week = startDate?.let { TimetableRepo.weekOf(it, s.date) } ?: 0
            if (week < 1) continue
            val nodes = mapNodes(s.start, s.end, timetable.nodeTimes) ?: continue
            val roomText = listOf(s.room, if (s.seat.isBlank()) "" else "${s.seat}座")
                .filter { it.isNotBlank() }.joinToString(" ")
            expCourses += Course(
                courseName = s.labName,
                courseNumber = EXP_COURSE_NUMBER,
                sequence = "",
                teacher = s.teacher,
                credit = 0.0,
                property = "实验",
                examType = "",
                day = s.date.dayOfWeek.value,
                startNode = nodes.first,
                nodeCount = nodes.second,
                weeks = listOf(week),
                weekDesc = "第${week}周 ${s.start}-${s.end}" + if (s.room.isBlank()) "" else " ${s.room}",
                campus = "",
                building = "",
                room = roomText,
                colorIndex = 11
            )
        }
        return timetable.copy(
            courses = (kept + expCourses).sortedWith(compareBy({ it.day }, { it.startNode }))
        )
    }

    // ---------- 网络 ----------

    fun fetchSessions(uname: String, password: String): List<ExpSession> {
        fun post(path: String, body: String): String {
            val conn = URL(HOST + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true   // IIS 要求 POST 必须带 Content-Length，空体也要写 0 字节
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                // Cookie 由全局 CookieHandler（见 ExpImportActivity）自动携带：登录会先后发
                // ASP.NET_SessionId 和 .ASPXAUTH 两个 Cookie，缺一个都会被当成未登录
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                return stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } finally {
                conn.disconnect()
            }
        }

        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val login = JSONObject(
            post(
                "/BaseInfo/Login/ValidateLogin",
                "uname=${enc(uname)}&pwd=${enc(encryptPassword(password))}&checkcode="
            )
        )
        if (!login.optBoolean("IsSuccess")) {
            val reason = login.optString("Data")
            throw ExpImportException(
                when {
                    reason.contains("验证码") -> "学校要求输验证码，请用“湖理网站”里的实验课系统入口登录网页版"
                    reason.isNotBlank() -> reason
                    else -> "实验课系统登录失败，请检查账号密码"
                }
            )
        }
        post("/BaseInfo/Login/GetRoleUser", "user=${enc(uname)}&js=")

        val data = post(
            "/XPK/StuCourseElectiveLook/LoadTableInfo",
            "page=1&rows=200&CourseID=&weeks=&labID=&weekID=&ddCourseName=&ddLabName=&ddWeeks=&ddWeek="
        )
        val sessions = parseSessions(data)
        if (sessions.isEmpty()) throw ExpImportException("实验课系统里没有查到本学期已选的实验")
        return sessions
    }
}
