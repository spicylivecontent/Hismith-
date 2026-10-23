package com.hismith.session

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hismith.session.core.Challenge
import com.hismith.session.core.DayStatus
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Optional daily challenge reminder (inexact alarm – no exact-alarm permission needed). */
object Reminders {
    const val CHANNEL_ID = "hismith_challenge"
    private const val NOTIF_ID = 44

    fun epochDayToday(): Long {
        val now = System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toDays(now + TimeZone.getDefault().getOffset(now))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 10, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, challenge: Challenge?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        am.cancel(pi)
        if (challenge == null || !challenge.reminderEnabled || challenge.isPaused) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, challenge.reminderMinuteOfDay / 60)
            set(Calendar.MINUTE, challenge.reminderMinuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    fun notifyIfDue(context: Context) {
        val settings = AppSettings(context)
        val today = epochDayToday()
        val c = settings.challenge?.rollover(today) ?: return
        settings.challenge = c
        if (!c.reminderEnabled || c.isPaused) return
        val day = c.currentDay(today) ?: return
        if (c.status(day) == DayStatus.DONE) return
        val t = c.target(day)
        val open = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHALLENGE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID, "Utmaning", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("${c.name}: dag $day av ${c.days}")
            .setContentText("${t.minutes} min · intensitet ${t.intensity} %" + if (c.isNonstopDay(day)) " · nonstop-mål" else "")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: Exception) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.notifyIfDue(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.schedule(context, AppSettings(context).challenge)
        }
    }
}
