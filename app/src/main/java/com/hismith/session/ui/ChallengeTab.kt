package com.hismith.session.ui

import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.hismith.session.MainActivity
import com.hismith.session.R
import com.hismith.session.Reminders
import com.hismith.session.UiState
import com.hismith.session.core.Challenge
import com.hismith.session.core.DayStatus
import com.hismith.session.core.DayTarget
import com.hismith.session.core.MissedPolicy
import com.hismith.session.core.Progression
import com.hismith.session.core.SessionMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Challenge Builder: create a multi-day challenge, run today's session, follow the calendar. */
class ChallengeTab(private val a: MainActivity) {
    private val settings = a.settings

    private val create: View = a.findViewById(R.id.chCreate)
    private val active: View = a.findViewById(R.id.chActive)

    private val name: EditText = a.findViewById(R.id.chName)
    private val days: EditText = a.findViewById(R.id.chDays)
    private val mode: Spinner = a.findViewById(R.id.chMode)
    private val progression: Spinner = a.findViewById(R.id.chProgression)
    private val missed: Spinner = a.findViewById(R.id.chMissed)
    private val startMin: EditText = a.findViewById(R.id.chStartMin)
    private val startInt: EditText = a.findViewById(R.id.chStartInt)
    private val startMl: EditText = a.findViewById(R.id.chStartMl)
    private val endMin: EditText = a.findViewById(R.id.chEndMin)
    private val endInt: EditText = a.findViewById(R.id.chEndInt)
    private val endMl: EditText = a.findViewById(R.id.chEndMl)
    private val nonstop: Switch = a.findViewById(R.id.chNonstop)
    private val reminder: Switch = a.findViewById(R.id.chReminder)
    private val reminderTime: EditText = a.findViewById(R.id.chReminderTime)

    private val title: TextView = a.findViewById(R.id.chTitle)
    private val subtitle: TextView = a.findViewById(R.id.chSubtitle)
    private val progress: ProgressBar = a.findViewById(R.id.chProgress)
    private val today: TextView = a.findViewById(R.id.chToday)
    private val btnStart: Button = a.findViewById(R.id.btnChStart)
    private val btnDone: Button = a.findViewById(R.id.btnChDone)
    private val btnMissed: Button = a.findViewById(R.id.btnChMissed)
    private val grid: ChallengeGridView = a.findViewById(R.id.chGrid)
    private val activeReminder: Switch = a.findViewById(R.id.chActiveReminder)
    private val activeReminderTime: EditText = a.findViewById(R.id.chActiveReminderTime)
    private val btnPause: Button = a.findViewById(R.id.btnChPause)

    private var pickedMode = SessionMode.ANAL
    private var pickedProgression = Progression.LINEAR
    private var pickedMissed = MissedPolicy.MARK_MISSED
    private var challenge: Challenge? = null
    private var rendering = false

    private val dateFmt = SimpleDateFormat("EEE d MMM", Ui.SV).apply { timeZone = TimeZone.getTimeZone("UTC") }

    init {
        mode.setup(a, SessionMode.values().toList(), { it.label }) { pickedMode = it }
        mode.setSelection(SessionMode.ANAL.ordinal)
        progression.setup(a, Progression.values().toList(), { it.label }) { pickedProgression = it }
        missed.setup(a, MissedPolicy.values().toList(), { it.label }) { pickedMissed = it }

        a.findViewById<Button>(R.id.btnChCreate).setOnClickListener { createChallenge() }
        btnStart.setOnClickListener {
            if (a.state?.anyMotionConnected != true) return@setOnClickListener Ui.toast(a, "Anslut en maskin först")
            a.service?.startChallengeDay()
            a.showTab(MainActivity.TAB_SESSION)
        }
        btnDone.setOnClickListener { recordToday(DayStatus.DONE) }
        btnMissed.setOnClickListener { recordToday(DayStatus.MISSED) }
        grid.onDayClick = { day -> dayDialog(day) }
        btnPause.setOnClickListener {
            val t = Reminders.epochDayToday()
            update { c -> if (c.isPaused) c.resume(t) else c.pause(t) }
        }
        a.findViewById<Button>(R.id.btnChDelete).setOnClickListener {
            Ui.dialog(a)
                .setTitle("Avsluta utmaningen?")
                .setMessage("Kalendern och all progress för utmaningen tas bort.")
                .setPositiveButton("Avsluta") { _, _ -> a.service?.updateChallenge { null }; refreshSoon() }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        activeReminder.setOnCheckedChangeListener { _, checked ->
            if (!rendering) update { it.copy(reminderEnabled = checked, reminderMinuteOfDay = parseTime(activeReminderTime.text.toString()) ?: it.reminderMinuteOfDay) }
        }
        activeReminderTime.setOnFocusChangeListener { _, has ->
            if (!has) parseTime(activeReminderTime.text.toString())?.let { m -> update { it.copy(reminderMinuteOfDay = m) } }
        }
        refresh()
    }

    private fun parseTime(s: String): Int? {
        val p = s.trim().split(':', '.')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun fmtTime(min: Int) = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    private fun createChallenge() {
        val n = days.intOr(30).coerceIn(2, 365)
        val c = Challenge(
            name = name.text.toString().trim().ifEmpty { "$n-dagarsutmaning" },
            days = n,
            startEpochDay = Reminders.epochDayToday(),
            progression = pickedProgression,
            start = DayTarget(startMin.intOr(10), startInt.intOr(30), startMl.intOr(15)).sanitized(),
            end = DayTarget(endMin.intOr(45), endInt.intOr(85), endMl.intOr(50)).sanitized(),
            lastDayNonstop = nonstop.isChecked,
            missedPolicy = pickedMissed,
            mode = pickedMode,
            reminderEnabled = reminder.isChecked,
            reminderMinuteOfDay = parseTime(reminderTime.text.toString()) ?: (20 * 60),
        )
        a.service?.updateChallenge { c } ?: return Ui.toast(a, "Tjänsten startar – försök igen")
        challenge = c
        render(a.state)
        refreshSoon()
        Ui.toast(a, "Utmaningen är skapad – dag 1 är i dag")
    }

    private fun update(transform: (Challenge) -> Challenge) {
        val s = a.service ?: return
        s.updateChallenge { it?.let(transform) }
        refreshSoon()
    }

    private fun recordToday(status: DayStatus) {
        val c = challenge ?: return
        val t = Reminders.epochDayToday()
        val day = c.currentDay(t) ?: return Ui.toast(a, "Ingen dag att markera i dag")
        update { it.record(day, status, if (status == DayStatus.DONE) 100 else 0, t) }
    }

    /** The service writes on its own thread – read back shortly after. */
    private fun refreshSoon() {
        a.window.decorView.postDelayed({ refresh() }, 250)
    }

    fun refresh() {
        challenge = settings.challenge?.rollover(Reminders.epochDayToday())
        render(a.state)
    }

    private fun dateOf(epochDay: Long): String = dateFmt.format(Date(TimeUnit.DAYS.toMillis(epochDay)))

    private fun dayDialog(day: Int) {
        val c = challenge ?: return
        val t = c.target(day)
        val msg = buildString {
            append("${dateOf(c.dateOf(day))}\n")
            append("${t.minutes} min · intensitet ${t.intensity} % · final ${t.finalMl} ml")
            if (c.isNonstopDay(day)) append("\nNonstop-mål")
            append("\nStatus: ${c.status(day).label}")
            c.records[day]?.let { if (it.status == DayStatus.PARTIAL) append(" (${it.progressPct} %)") }
        }
        val b = Ui.dialog(a)
            .setTitle("Dag $day av ${c.days}")
            .setMessage(msg)
            .setPositiveButton("Markera klar") { _, _ -> update { it.record(day, DayStatus.DONE, 100, Reminders.epochDayToday()) } }
            .setNegativeButton("Stäng", null)
        if (c.progression == Progression.MANUAL) {
            b.setNeutralButton("Ändra värden") { _, _ -> editDayDialog(day, t) }
        } else {
            b.setNeutralButton("Markera missad") { _, _ -> update { it.record(day, DayStatus.MISSED, 0, Reminders.epochDayToday()) } }
        }
        b.show()
    }

    private fun editDayDialog(day: Int, t: DayTarget) {
        fun field(hint: String, value: Int) = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            this.hint = hint
            setText(value.toString())
            setTextColor(a.getColor(R.color.text))
        }
        val min = field("Minuter", t.minutes)
        val int = field("Intensitet %", t.intensity)
        val ml = field("Final ml", t.finalMl)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0)
            listOf("Minuter" to min, "Intensitet (%)" to int, "Final (ml, 0 = ingen)" to ml).forEach { (label, f) ->
                addView(TextView(a).apply { text = label; setTextColor(a.getColor(R.color.muted)) })
                addView(f)
            }
        }
        Ui.dialog(a)
            .setTitle("Dag $day")
            .setView(box)
            .setPositiveButton("Spara") { _, _ ->
                update { it.withManualTarget(day, DayTarget(min.intOr(t.minutes), int.intOr(t.intensity), ml.intOr(t.finalMl))) }
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    fun render(st: UiState?) {
        val c = challenge
        create.visibility = if (c == null) View.VISIBLE else View.GONE
        active.visibility = if (c == null) View.GONE else View.VISIBLE
        if (c == null) return
        rendering = true
        val t = Reminders.epochDayToday()
        val day = c.currentDay(t)
        title.text = c.name
        subtitle.text = buildString {
            append("${c.mode.label} · ${c.progression.label} · ${c.doneCount} av ${c.days} klara")
            if (c.isPaused) append(" · PAUSAD")
        }
        progress.progress = (c.doneCount * 1000) / c.days.coerceAtLeast(1)
        grid.setChallenge(c, day)

        today.text = when {
            c.isPaused -> "Utmaningen är pausad. Schemat flyttas fram när du återupptar den."
            day == null && t < c.dateOf(1) -> "Utmaningen börjar ${dateOf(c.dateOf(1))}."
            day == null -> "Utmaningen är slut – ${c.doneCount} av ${c.days} dagar klara. Snyggt jobbat!"
            else -> {
                val target = c.target(day)
                buildString {
                    append("Dag $day av ${c.days} · ${dateOf(c.dateOf(day))}\n")
                    append("${target.minutes} min · intensitet ${target.intensity} %")
                    append(if (target.finalMl > 0) " · final ${target.finalMl} ml" else " · ingen final")
                    if (c.isNonstopDay(day)) append("\nSista dagen: nonstop-mål (inga vilopauser). STOPP fungerar alltid.")
                    append("\nStatus: ${c.status(day).label}")
                    c.records[day]?.let { if (it.status == DayStatus.PARTIAL) append(" (${it.progressPct} %)") }
                }
            }
        }
        val canStart = day != null && !c.isPaused && st?.session?.running != true && c.status(day) != DayStatus.DONE
        Ui.setEnabled(btnStart, canStart)
        Ui.setEnabled(btnDone, day != null && !c.isPaused)
        Ui.setEnabled(btnMissed, day != null && !c.isPaused)
        btnPause.text = if (c.isPaused) "Återuppta" else "Pausa utmaningen"
        activeReminder.isChecked = c.reminderEnabled
        activeReminderTime.setIfIdle(fmtTime(c.reminderMinuteOfDay))
        rendering = false
    }
}
