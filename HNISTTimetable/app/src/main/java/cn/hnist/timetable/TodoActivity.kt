package cn.hnist.timetable

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.hnist.timetable.reminder.Reminders
import cn.hnist.timetable.todo.TodoItem
import cn.hnist.timetable.todo.TodoRepo
import cn.hnist.timetable.ui.EdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TodoActivity : AppCompatActivity() {

    private lateinit var repo: TodoRepo
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var root: View
    private val adapter = TodoAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cn.hnist.timetable.ui.CrashGuard.install(this)
        setContentView(R.layout.activity_todo)
        repo = TodoRepo(this)

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        EdgeToEdge.setup(this, root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ItemTouchHelper(SwipeToDelete()).attachToRecyclerView(list)

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showEditDialog(null) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val items = TodoRepo.sorted(repo.list(), today)
        adapter.submit(items)
        empty.isVisible = items.isEmpty()
        val pending = items.count { !it.doneEffective(today) && !it.isEnded(now) }
        toolbar.subtitle = when {
            items.isEmpty() -> null
            pending == 0 -> "今天全部完成 🎉"
            else -> "还有 $pending 项未完成"
        }
    }

    /** 数据有变化后：刷新列表并重排“待办开始”闹钟。 */
    private fun onDataChanged() {
        refresh()
        Reminders.rescheduleTodos(this)
    }

    // ---------- 新增 / 编辑 ----------

    private fun showEditDialog(existing: TodoItem?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_todo_edit, null)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etTitle)
        val etNote = view.findViewById<TextInputEditText>(R.id.etNote)
        val rgRepeat = view.findViewById<RadioGroup>(R.id.rgRepeat)
        val weeklyDays = view.findViewById<View>(R.id.weeklyDays)
        val dayBoxes = listOf(R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6, R.id.day7)
            .map { view.findViewById<CheckBox>(it) }
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStart)
        val btnClearStart = view.findViewById<MaterialButton>(R.id.btnClearStart)
        val fromRow = view.findViewById<View>(R.id.fromRow)
        val btnFrom = view.findViewById<MaterialButton>(R.id.btnFrom)
        val btnClearFrom = view.findViewById<MaterialButton>(R.id.btnClearFrom)
        val dueRow = view.findViewById<View>(R.id.dueRow)
        val btnDue = view.findViewById<MaterialButton>(R.id.btnDue)
        val btnDueTime = view.findViewById<MaterialButton>(R.id.btnDueTime)
        val btnClearDue = view.findViewById<MaterialButton>(R.id.btnClearDue)
        val tvHint = view.findViewById<TextView>(R.id.tvRepeatHint)

        var start: LocalDateTime? = existing?.start          // 普通待办的开始时刻
        var startTime: LocalTime? = existing?.startTime      // 重复待办每天的时刻
        var from: LocalDate? = existing?.from                // 重复待办的第一天
        var due: LocalDate? = existing?.due
        var dueTimeOfDay: LocalTime? = existing?.dueTimeOfDay   // 截止当天的时刻，null = 全天

        // 回填重复设置
        when {
            existing == null || existing.repeat.isBlank() -> rgRepeat.check(R.id.rpNone)
            existing.repeat == "daily" -> rgRepeat.check(R.id.rpDaily)
            else -> {
                rgRepeat.check(R.id.rpWeekly)
                existing.repeatDays.forEach { dayBoxes[it - 1].isChecked = true }
            }
        }
        fun isRecurring() = rgRepeat.checkedRadioButtonId != R.id.rpNone
        val dateFmt = DateTimeFormatter.ofPattern("M月d日")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        fun renderPickers() {
            val recurring = isRecurring()
            weeklyDays.isVisible = rgRepeat.checkedRadioButtonId == R.id.rpWeekly
            fromRow.isVisible = recurring
            dueRow.isVisible = true
            val dueTimeText = dueTimeOfDay?.let { " ${it.format(timeFmt)}" } ?: ""
            if (recurring) {
                btnStart.text = startTime?.let { "每次 ${it.format(timeFmt)} 开始" } ?: "设置开始时刻（可选）"
                btnClearStart.isVisible = startTime != null
                btnFrom.text = from?.let { "从 ${it.format(dateFmt)} ${weekdayOf(it)} 开始" } ?: "开始日期（可选，默认从今天起）"
                btnClearFrom.isVisible = from != null
                btnDue.text = due?.let { "重复到 ${it.format(dateFmt)} ${weekdayOf(it)}$dueTimeText 为止" }
                    ?: "截止日期（可选，不填则一直重复）"
                btnClearDue.isVisible = due != null
                tvHint.text = "重复任务在开始日期到截止日期之间每天出现一次（都不填就一直重复）；勾选完成只算当天，第二天自动恢复并记录连续天数；设了开始时刻会到点提醒。"
            } else {
                btnStart.text = start?.let { "开始 ${it.format(dateFmt)} ${weekdayOf(it.toLocalDate())} ${it.format(timeFmt)}" }
                    ?: "设置开始时间"
                btnClearStart.isVisible = start != null
                btnDue.text = due?.let { "截止 ${it.format(dateFmt)} ${weekdayOf(it)}$dueTimeText" } ?: "设置截止日期"
                btnClearDue.isVisible = due != null
                tvHint.text = "到了开始时间会发通知；开始后未完成的待办会一直显示在课表下方。"
            }
            // 截止时刻只有先设了截止日期才有意义
            btnDueTime.text = when {
                due == null -> "截止时刻（全天）"
                dueTimeOfDay == null -> "全天（点按设时刻）"
                else -> "当天 ${dueTimeOfDay!!.format(timeFmt)} 截止"
            }
            btnDueTime.isEnabled = due != null
        }
        renderPickers()
        etTitle.setText(existing?.title ?: "")
        etNote.setText(existing?.note ?: "")
        rgRepeat.setOnCheckedChangeListener { _, _ -> renderPickers() }

        btnStart.setOnClickListener {
            if (isRecurring()) {
                val init = startTime ?: LocalTime.of(8, 0)
                TimePickerDialog(this, { _, hour, minute ->
                    startTime = LocalTime.of(hour, minute)
                    renderPickers()
                }, init.hour, init.minute, true).show()
            } else {
                val init = start ?: LocalDateTime.now().plusHours(1).withMinute(0)
                DatePickerDialog(this, { _, y, m, d ->
                    val date = LocalDate.of(y, m + 1, d)
                    TimePickerDialog(this, { _, hour, minute ->
                        start = LocalDateTime.of(date, LocalTime.of(hour, minute))
                        renderPickers()
                    }, init.hour, init.minute, true).show()
                }, init.year, init.monthValue - 1, init.dayOfMonth).show()
            }
        }
        btnClearStart.setOnClickListener {
            start = null
            startTime = null
            renderPickers()
        }
        btnFrom.setOnClickListener {
            val init = from ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, d ->
                from = LocalDate.of(y, m + 1, d)
                renderPickers()
            }, init.year, init.monthValue - 1, init.dayOfMonth).show()
        }
        btnClearFrom.setOnClickListener {
            from = null
            renderPickers()
        }
        btnDue.setOnClickListener {
            val init = due ?: from ?: start?.toLocalDate() ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, d ->
                due = LocalDate.of(y, m + 1, d)
                renderPickers()
            }, init.year, init.monthValue - 1, init.dayOfMonth).show()
        }
        btnDueTime.setOnClickListener {
            if (due == null) return@setOnClickListener
            val init = dueTimeOfDay ?: LocalTime.of(22, 0)
            TimePickerDialog(this, { _, hour, minute ->
                dueTimeOfDay = LocalTime.of(hour, minute)
                renderPickers()
            }, init.hour, init.minute, true).show()
        }
        btnDueTime.setOnLongClickListener {
            // 长按恢复“全天”
            if (dueTimeOfDay != null) {
                dueTimeOfDay = null
                renderPickers()
                true
            } else false
        }
        btnClearDue.setOnClickListener {
            due = null
            dueTimeOfDay = null
            renderPickers()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "新增待办" else "编辑待办")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            etTitle.requestFocus()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    etTitle.error = "写点什么吧"
                    return@setOnClickListener
                }
                val repeat = when (rgRepeat.checkedRadioButtonId) {
                    R.id.rpDaily -> "daily"
                    R.id.rpWeekly -> {
                        val days = dayBoxes.mapIndexedNotNull { i, cb -> if (cb.isChecked) i + 1 else null }
                        if (days.isEmpty()) {
                            Snackbar.make(view, "每周重复至少选一天", Snackbar.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        "weekly:" + days.joinToString(",")
                    }
                    else -> ""
                }
                val recurring = repeat.isNotBlank()
                val fromValue = if (recurring) from else null
                if (recurring && fromValue != null && due != null && due!!.isBefore(fromValue)) {
                    Snackbar.make(view, "截止日期不能早于开始日期", Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val startVal = start
                if (!recurring && startVal != null && due != null && due == startVal.toLocalDate() &&
                    dueTimeOfDay != null && startVal.toLocalTime().isAfter(dueTimeOfDay)
                ) {
                    Snackbar.make(view, "截止时刻不能早于开始时刻", Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val note = etNote.text?.toString()?.trim().orEmpty()
                val now = LocalDateTime.now()
                val today = now.toLocalDate()
                val startText = if (recurring) {
                    startTime?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                } else {
                    start?.toString().orEmpty()
                }
                val dueText = due?.toString().orEmpty()
                val dueTimeText = if (due != null) dueTimeOfDay?.format(timeFmt).orEmpty() else ""
                val fromText = fromValue?.toString().orEmpty()
                val timingUnchanged = existing != null && existing.startAt == startText &&
                    existing.repeat == repeat && existing.fromDate == fromText && existing.dueDate == dueText

                // 普通待办：开始时间已过去的视为不用再提醒；重复待办：今天时刻已过就当今天已提醒
                val startNotified = if (!recurring) {
                    if (timingUnchanged) existing!!.startNotified else start?.let { !it.isAfter(now) } ?: false
                } else false
                val lastNotified = if (recurring) {
                    if (timingUnchanged) existing!!.lastNotifiedDate
                    else {
                        val t = startTime
                        val inPeriod = (fromValue == null || !today.isBefore(fromValue)) && (due == null || !today.isAfter(due!!))
                        if (t != null && inPeriod && !LocalDateTime.of(today, t).isAfter(now)) today.toString() else ""
                    }
                } else ""

                val item = TodoItem(
                    id = existing?.id ?: 0L,
                    title = title,
                    note = note,
                    dueDate = dueText,
                    dueTime = dueTimeText,
                    startAt = startText,
                    repeat = repeat,
                    fromDate = fromText,
                    done = if (recurring) false else existing?.done ?: false,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    doneAt = existing?.doneAt ?: 0L,
                    startNotified = startNotified,
                    lastDoneDate = if (recurring) existing?.lastDoneDate.orEmpty() else "",
                    lastNotifiedDate = lastNotified,
                    streak = if (recurring) existing?.streak ?: 0 else 0
                )
                if (existing == null) repo.add(item) else repo.update(item)
                onDataChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun toggleDone(item: TodoItem) {
        repo.toggleDone(item)
        onDataChanged()
    }

    private fun deleteWithUndo(item: TodoItem) {
        repo.delete(item.id)
        onDataChanged()
        Snackbar.make(root, "已删除「${item.title}」", Snackbar.LENGTH_LONG)
            .setAction("撤销") {
                repo.update(item)
                onDataChanged()
            }
            .show()
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR_DONE, 0, "清除已完成")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR_DONE) {
            // 清掉已完成的普通待办和已结束（过了截止时刻）的重复任务
            val now = LocalDateTime.now()
            val done = repo.list().filter { (!it.isRecurring && it.done) || it.isEnded(now) }
            if (done.isEmpty()) {
                Snackbar.make(root, "没有可清除的待办（未结束的重复任务不会被清除）", Snackbar.LENGTH_SHORT).show()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("清除已完成")
                    .setMessage("删除 ${done.size} 项已完成/已结束的待办？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        repo.save(repo.list().filterNot { (!it.isRecurring && it.done) || it.isEnded(now) })
                        onDataChanged()
                    }
                    .show()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- 列表 ----------

    private inner class TodoAdapter : RecyclerView.Adapter<TodoAdapter.Holder>() {
        private val items = mutableListOf<TodoItem>()

        fun submit(newItems: List<TodoItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): TodoItem? = items.getOrNull(position)

        inner class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            TodoRowBinder.bind(holder.itemView, item, onToggle = { toggleDone(item) }, onClick = { showEditDialog(item) })
        }
    }

    private inner class SwipeToDelete : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val item = adapter.itemAt(viewHolder.adapterPosition)
            if (item != null) deleteWithUndo(item) else adapter.notifyDataSetChanged()
        }
    }

    private fun weekdayOf(date: LocalDate): String = "周" + TodoItem.weekdayNames[date.dayOfWeek.value - 1]

    companion object {
        private const val MENU_CLEAR_DONE = 1
    }
}

/** 待办一行的绑定逻辑，待办页和课表主页共用。 */
object TodoRowBinder {

    private const val COLOR_OVERDUE = 0xFFB71C1C.toInt()   // 已过期 / 已错过：深红
    private const val COLOR_TODAY = 0xFFD32F2F.toInt()     // 今天到期：红
    private const val COLOR_TOMORROW = 0xFFE64A19.toInt()  // 明天：深橙
    private const val COLOR_SOON = 0xFFF57C00.toInt()      // 2–3 天：橙
    private const val COLOR_WEEK = 0xFFF9A825.toInt()      // 4–7 天：琥珀

    /** 未完成任务按剩余天数分级着色，越近颜色越深。 */
    fun urgencyColor(daysLeft: Long, fallback: Int): Int = when {
        daysLeft < 0 -> COLOR_OVERDUE
        daysLeft == 0L -> COLOR_TODAY
        daysLeft == 1L -> COLOR_TOMORROW
        daysLeft <= 3 -> COLOR_SOON
        daysLeft <= 7 -> COLOR_WEEK
        else -> fallback
    }

    /** 勾选完成前先确认，避免误触；取消勾选不用确认。 */
    fun confirmComplete(context: Context, item: TodoItem, onConfirmed: () -> Unit) {
        val today = LocalDate.now()
        val message = if (item.isRecurring) {
            val missed = item.missedCount(today)
            val missedText = if (missed > 0) "之前错过了 $missed 天，连续天数重新计。\n" else ""
            "「${item.title}」今天完成了吗？\n$missedText" + "完成后连续 ${item.nextStreakIfDone(today)} 天。"
        } else {
            "「${item.title}」已经完成了吗？"
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("标记为完成")
            .setMessage(message)
            .setNegativeButton("还没有", null)
            .setPositiveButton("完成了") { _, _ -> onConfirmed() }
            .show()
    }

    fun bind(row: View, item: TodoItem, onToggle: () -> Unit, onClick: () -> Unit) {
        val context = row.context
        val check = row.findViewById<MaterialCheckBox>(R.id.check)
        val title = row.findViewById<TextView>(R.id.title)
        val note = row.findViewById<TextView>(R.id.note)
        val meta = row.findViewById<TextView>(R.id.meta)
        val due = row.findViewById<TextView>(R.id.due)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val primary = ContextCompat.getColor(context, R.color.primary)
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val doneToday = item.doneEffective(today)
        val ended = item.isEnded(now)

        check.setOnCheckedChangeListener(null)
        check.isChecked = doneToday || ended
        check.isEnabled = !ended
        check.setOnClickListener {
            if (!doneToday) {
                // 先退回未勾选状态，确认后再真正完成
                check.isChecked = false
                confirmComplete(context, item, onToggle)
            } else {
                onToggle()
            }
        }

        title.text = item.title
        title.paintFlags = if (doneToday || ended) {
            title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        title.setTextColor(if (doneToday || ended) secondary else ContextCompat.getColor(context, R.color.text_primary))

        note.isVisible = item.note.isNotBlank()
        note.text = item.note

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val dateFmt = DateTimeFormatter.ofPattern("M月d日")
        val days = item.daysLeft(today)
        val overdue = !ended && item.isOverdueAt(now)   // 设了截止时刻时，过了时刻当天也算过期
        val urgent = days != null && !doneToday && !ended
        val urgency = when {
            urgent && overdue -> COLOR_OVERDUE
            urgent -> urgencyColor(days!!, secondary)
            else -> secondary
        }

        if (item.isRecurring) {
            meta.isVisible = true
            val from = item.from
            val end = item.due
            val period = when {
                from != null && end != null -> "${from.format(dateFmt)}–${end.format(dateFmt)} "
                from != null -> "${from.format(dateFmt)}起 "
                end != null -> "到${end.format(dateFmt)} "
                else -> ""
            }
            val schedule = period + TodoItem.repeatLabel(item.repeat) + (item.startTime?.let { " ${it.format(timeFmt)}" } ?: "")
            val missed = if (doneToday || ended) 0 else item.missedCount(today)
            val streak = if (doneToday) item.streak else item.effectiveStreak(today)
            val tail = when {
                missed > 0 -> " · 错过 $missed 天"
                streak > 0 -> " · 连续 $streak 天"
                else -> ""
            }
            val started = !doneToday && item.startedToday(now)
            val notYet = from != null && today.isBefore(from)
            meta.text = when {
                ended -> "已结束 · $schedule" + (if (item.streak > 0) " · 连续 ${item.streak} 天" else "")
                doneToday -> "今天已完成 · $schedule$tail"
                started -> "已开始 · $schedule$tail"
                notYet -> "未开始 · $schedule"
                else -> "$schedule$tail"
            }
            meta.setTextColor(
                when {
                    missed > 0 -> COLOR_SOON
                    started -> primary
                    else -> secondary
                }
            )
        } else {
            val start = item.start
            if (start == null) {
                meta.isVisible = false
            } else {
                meta.isVisible = true
                val timeText = start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                val started = !doneToday && item.startedToday(now)
                meta.text = if (started) "已开始 · $timeText" else "开始 $timeText"
                meta.setTextColor(if (started) primary else secondary)
            }
        }

        if (days == null) {
            due.isVisible = false
        } else {
            due.isVisible = true
            val dueTimeText = item.dueTimeOfDay?.let { " ${it.format(timeFmt)}" } ?: ""
            val text = when {
                item.isRecurring && ended -> "已结束"
                item.isRecurring -> when {
                    days == 0L -> "最后一天$dueTimeText"
                    days <= 7 -> "还剩${days}天"
                    else -> "到 " + item.due!!.format(DateTimeFormatter.ofPattern("M/d")) + dueTimeText
                }
                doneToday -> "已完成"
                overdue && days!! < 0L -> "过期${-days}天"
                overdue -> "今天${dueTimeText}已过"
                days == 0L -> "今天${dueTimeText}截止"
                days == 1L -> "明天${dueTimeText}截止"
                days <= 7 -> "${days}天后截止"
                else -> item.due!!.format(DateTimeFormatter.ofPattern("M/d")) + dueTimeText + " 截止"
            }
            val color = urgency
            due.text = text
            due.setTextColor(color)
            due.background = GradientDrawable().apply {
                cornerRadius = 20f * context.resources.displayMetrics.density
                setColor((color and 0x00FFFFFF) or (if (urgent && (overdue || days!! <= 1)) 0x26000000 else 0x1A000000))
            }
        }

        // 今天到期、明天到期或已过期的未完成任务，标题也跟着变色，一眼能看到
        if (urgent && (overdue || days!! <= 1L)) {
            title.setTextColor(urgency)
        }

        row.setOnClickListener { onClick() }
    }
}
