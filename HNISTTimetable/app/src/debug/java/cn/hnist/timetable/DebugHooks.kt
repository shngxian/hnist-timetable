package cn.hnist.timetable

import android.content.Context
import cn.hnist.timetable.data.TimetableRepo
import cn.hnist.timetable.model.Timetable
import cn.hnist.timetable.todo.TodoItem
import cn.hnist.timetable.todo.TodoRepo
import org.json.JSONObject
import java.time.LocalDate

/** 调试版：从 assets 载入一份示例课表和几条示例待办，方便不登录也能验证界面。 */
object DebugHooks {
    const val ENABLED = true

    fun installSample(context: Context, repo: TimetableRepo): Boolean = try {
        val json = context.assets.open("sample_timetable.json").bufferedReader().readText()
        repo.save(Timetable.fromJson(JSONObject(json)))
        seedTodos(context)
        pickViaProductionCode(context)   // 用生产函数导入用户的原图，走真实解码/裁剪/保存路径
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 把 assets 里那张真实照片复制到外部私有目录，再用和生产“相册选图”完全相同的
     * [TimetableBackground.saveFrom] 导入（只把系统选图器换成 Uri.fromFile）。
     */
    private fun pickViaProductionCode(context: Context) {
        val f = java.io.File(context.getExternalFilesDir(null), "pick_sample.jpg")
        context.assets.open("pick_sample.jpg").use { input ->
            f.outputStream().use { output -> input.copyTo(output) }
        }
        val ok = cn.hnist.timetable.ui.TimetableBackground.saveFrom(context, android.net.Uri.fromFile(f))
        android.util.Log.i("DebugHooks", "saveFrom ok=$ok size=${f.length()}")
        f.delete()
    }

    /** 覆盖不同紧急程度和重复状态的示例待办（列表为空时才写入）。 */
    private fun seedTodos(context: Context) {
        val todos = TodoRepo(context)
        if (todos.list().isNotEmpty()) return
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val startedYesterday = today.minusDays(1).atTime(9, 0).toString()
        fun todo(title: String, due: LocalDate? = null, repeat: String = "", startAt: String = startedYesterday,
                 lastDone: LocalDate? = null, streak: Int = 0, createdDaysAgo: Long = 0) = TodoItem(
            id = 0, title = title, note = "", dueDate = due?.toString().orEmpty(), startAt = startAt,
            repeat = repeat, fromDate = "", done = false,
            createdAt = now - createdDaysAgo * 86_400_000L, doneAt = 0L, startNotified = true,
            lastDoneDate = lastDone?.toString().orEmpty(), lastNotifiedDate = "", streak = streak
        )
        listOf(
            todo("上周的作业（已过期）", due = today.minusDays(2)),
            todo("交实验报告", due = today),
            todo("预习第三章", due = today.plusDays(1)),
            todo("小组作业分工", due = today.plusDays(3)),
            todo("英语作文", due = today.plusDays(6)),
            todo("期中论文选题", due = today.plusDays(12)),
            todo("背单词 20 个", repeat = "daily", startAt = "08:00", lastDone = today.minusDays(1), streak = 4),
            todo("晨跑", repeat = "daily", startAt = "06:40", lastDone = today.minusDays(3), streak = 6),
            todo("考试周复习", repeat = "daily", startAt = "20:00", createdDaysAgo = 0)
                .copy(fromDate = today.toString(), dueDate = today.plusDays(1).toString())
        ).forEach { todos.add(it) }
    }
}
