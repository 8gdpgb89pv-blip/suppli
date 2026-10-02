package com.example.suppli

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object SupplementStore {
    private const val PREFS = "suppli_store"
    private const val KEY_ITEMS = "supplements"
    private const val KEY_DATE = "tracking_date"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Supplement> {
        val p = prefs(context)
        val raw = p.getString(KEY_ITEMS, null) ?: return emptyList()
        val savedDate = p.getString(KEY_DATE, null)
        val today = getCurrentDateString()
        val reset = savedDate != today
        val array = JSONArray(raw)
        val result = buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    Supplement(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name"),
                        emoji = o.optString("emoji", "💊"),
                        doseValue = o.optString("doseValue"),
                        doseUnit = o.optString("doseUnit", "mg"),
                        targetDoses = o.optInt("targetDoses", 1).coerceAtLeast(1),
                        takenDoses = if (reset) 0 else o.optInt("takenDoses", 0).coerceAtLeast(0),
                        timeOfDay = runCatching { DayPartition.valueOf(o.optString("timeOfDay", "MORNING")) }.getOrDefault(DayPartition.MORNING),
                        reminderHour = o.optInt("reminderHour", if (o.optString("timeOfDay") == "EVENING") 20 else 8),
                        reminderMinute = o.optInt("reminderMinute", 0),
                        reminderEnabled = o.optBoolean("reminderEnabled", true)
                    )
                )
            }
        }
        if (reset) save(context, result)
        return result
    }

    fun save(context: Context, supplements: List<Supplement>) {
        val array = JSONArray()
        supplements.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("emoji", s.emoji)
                put("doseValue", s.doseValue)
                put("doseUnit", s.doseUnit)
                put("targetDoses", s.targetDoses)
                put("takenDoses", s.takenDoses)
                put("timeOfDay", s.timeOfDay.name)
                put("reminderHour", s.reminderHour)
                put("reminderMinute", s.reminderMinute)
                put("reminderEnabled", s.reminderEnabled)
            })
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).putString(KEY_DATE, getCurrentDateString()).apply()
    }

    fun update(context: Context, transform: (List<Supplement>) -> List<Supplement>): List<Supplement> {
        val updated = transform(load(context))
        save(context, updated)
        return updated
    }

    fun markOneTaken(context: Context, id: String): Supplement? {
        var changed: Supplement? = null
        update(context) { list ->
            list.map { s ->
                if (s.id == id && !s.isComplete) s.copy(takenDoses = s.takenDoses + 1).also { changed = it } else s
            }
        }
        return changed
    }
}

object NotificationHelper {
    const val CHANNEL_ID = "supplement_reminders"
    const val ACTION_MARK_TAKEN = "com.example.suppli.MARK_TAKEN"
    const val ACTION_SNOOZE = "com.example.suppli.SNOOZE"
    const val EXTRA_ID = "supplement_id"
    private const val NOTIFICATION_BASE = 31000
    private const val SNOOZE_BASE = 51000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Supplement reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily reminders for supplements in your stack"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun scheduleAll(context: Context) {
        createChannel(context)
        SupplementStore.load(context).forEach { schedule(context, it) }
    }

    fun cancel(context: Context, supplement: Supplement) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntent(context, supplement.id, 0))
        NotificationManagerCompat.from(context).cancel(notificationId(supplement.id))
    }

    fun schedule(context: Context, supplement: Supplement) {
        cancelAlarmOnly(context, supplement.id)
        if (!supplement.reminderEnabled) return
        val triggerAt = nextDailyTrigger(supplement.reminderHour, supplement.reminderMinute)
        setAlarm(context, triggerAt, pendingIntent(context, supplement.id, 0))
    }

    fun snooze(context: Context, supplement: Supplement, minutes: Int = 30) {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        setAlarm(context, triggerAt, pendingIntent(context, supplement.id, SNOOZE_BASE))
    }

    private fun cancelAlarmOnly(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id, 0))
    }

    private fun setAlarm(context: Context, triggerAt: Long, intent: PendingIntent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    private fun pendingIntent(context: Context, id: String, offset: Int): PendingIntent {
        val action = if (offset == SNOOZE_BASE) ACTION_SNOOZE else "daily"
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(id, offset),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(id: String, offset: Int): Int = NOTIFICATION_BASE + kotlin.math.abs(id.hashCode() % 20_000) + offset
    private fun notificationId(id: String): Int = kotlin.math.abs(id.hashCode() % 20_000) + 1

    private fun nextDailyTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = now.clone() as Calendar
        next.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        next.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        next.set(Calendar.SECOND, 0)
        next.set(Calendar.MILLISECOND, 0)
        if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    fun showReminder(context: Context, supplement: Supplement) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        if (supplement.isComplete) return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, notificationId(supplement.id), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val markIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_MARK_TAKEN
            putExtra(EXTRA_ID, supplement.id)
        }
        val markPending = PendingIntent.getBroadcast(
            context, requestCode(supplement.id, 40000), markIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_ID, supplement.id)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context, requestCode(supplement.id, SNOOZE_BASE), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progress = "${supplement.takenDoses}/${supplement.targetDoses} taken"
        val body = if (supplement.doseUnit == "pills") "$progress • ${supplement.doseValue} ${supplement.doseUnit}" else "${supplement.doseValue} ${supplement.doseUnit} • $progress"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.suppli.R.drawable.ic_launcher_foreground)
            .setContentTitle("Time for ${supplement.emoji} ${supplement.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Mark taken", markPending)
            .addAction(0, "Snooze 30 min", snoozePending)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(supplement.id), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(NotificationHelper.EXTRA_ID)
        if (id == null) {
            NotificationHelper.scheduleAll(context)
            return
        }
        val supplement = SupplementStore.load(context).firstOrNull { it.id == id } ?: return
        when (intent.action) {
            NotificationHelper.ACTION_MARK_TAKEN -> {
                SupplementStore.markOneTaken(context, id)
                NotificationManagerCompat.from(context).cancel(kotlin.math.abs(id.hashCode() % 20_000) + 1)
                NotificationHelper.schedule(context, supplement.copy(takenDoses = supplement.takenDoses + 1))
            }
            NotificationHelper.ACTION_SNOOZE -> NotificationHelper.snooze(context, supplement)
            else -> {
                NotificationHelper.showReminder(context, supplement)
                NotificationHelper.schedule(context, supplement)
            }
        }
    }
}

fun getCurrentDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
