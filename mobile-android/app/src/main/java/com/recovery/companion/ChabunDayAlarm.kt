package com.recovery.companion

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 퇴근 알림(선택): 다음 켜진 요일의 퇴근 15분 전에 한 번. 정확한 알람 권한 없이 setAndAllowWhileIdle만 쓴다
 * (SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 선언 안 함). 알림은 제안일 뿐이고 누르면 앱 홈이 열린다.
 */
object ChabunDayAlarm {
    const val ACTION = "com.recovery.companion.CHABUN_DAY_REMINDER"
    private const val CHANNEL = "chabun_day"
    private const val NOTIFICATION_ID = 4107

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 7,
        Intent(ctx, ChabunDayReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 설정이 바뀔 때마다 부른다: 알림이 꺼져 있으면 예약을 지우기만 한다. */
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        am.cancel(pi)
        val prefs = ChabunDayPrefs(ctx)
        if (!prefs.eveningReminder || !canNotify(ctx)) return
        val next = DayRhythmPolicy.nextReminder(LocalDateTime.now(), prefs.commute) ?: return
        // 절전(Doze) 중에도 울리게 하되 정확한 알람은 쓰지 않는다(몇 분 늦을 수 있음).
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

    fun show(ctx: Context) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "하루 쉼 알림", NotificationManager.IMPORTANCE_LOW).apply {
            description = "퇴근 무렵 한 번, 쉬어 갈지 물어봐요."
        })
        val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIFICATION_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.pitin_icon_mono)
            .setContentTitle("퇴근길이에요")
            .setContentText("운전하기 전에 3분 쉬고 출발할까요?")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class ChabunDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChabunDayAlarm.ACTION -> {
                val prefs = ChabunDayPrefs(context)
                val now = LocalDateTime.now()
                // 퇴근 창 안에서만 알린다(요일은 창이 속한 날로 판단: 00:10 퇴근의 23:55 알림, Doze로 늦게 온 알림).
                val inWindow = runCatching { DayRhythmPolicy.momentAt(now, prefs.commute, null, null) == DayMoment.EVENING_COMMUTE }.getOrDefault(false)
                val anchor = DayRhythmPolicy.anchorOf(now, prefs.commute, null, DayMoment.EVENING_COMMUTE)
                if (prefs.eveningReminder && inWindow && anchor != null && !prefs.dismissed(DayMoment.EVENING_COMMUTE, anchor)) ChabunDayAlarm.show(context)
                ChabunDayAlarm.reschedule(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> ChabunDayAlarm.reschedule(context)
        }
    }
}
