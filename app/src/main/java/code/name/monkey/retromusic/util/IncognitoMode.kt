package code.name.monkey.retromusic.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.R

/** The incognito button in the top bar (next to cast): when on, plays are not recorded anywhere. */
object IncognitoMode {

    /** Call after the toolbar tint helper ran, so the "on" icon can get the accent colour. */
    fun applyState(menu: Menu, context: Context) {
        val item = menu.findItem(R.id.action_incognito) ?: return
        val on = PreferenceUtil.incognito
        item.setIcon(if (on) R.drawable.ic_incognito_on else R.drawable.ic_incognito)
        item.icon?.mutate()?.let { if (on) it.setTint(ThemeStore.accentColor(context)) }
        item.title = context.getString(R.string.incognito_mode)
    }

    /** Returns true when [item] was the incognito button (and has been handled). */
    fun handle(item: MenuItem, activity: FragmentActivity): Boolean {
        if (item.itemId != R.id.action_incognito) return false
        PreferenceUtil.incognito = !PreferenceUtil.incognito
        Toast.makeText(
            activity,
            if (PreferenceUtil.incognito) R.string.incognito_on_msg else R.string.incognito_off_msg,
            Toast.LENGTH_SHORT
        ).show()
        refreshNotification(activity)
        activity.invalidateMenu()
        return true
    }

    private const val CHANNEL_ID = "incognito_mode"
    private const val NOTIFICATION_ID = 9071
    const val ACTION_OFF = "code.name.monkey.retromusic.INCOGNITO_OFF"

    /** Shows the "incognito is on" notification (with a Turn off button) or removes it. */
    fun refreshNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!PreferenceUtil.incognito) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.incognito_mode),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName), flags
        )
        val off = PendingIntent.getBroadcast(
            context, 1,
            Intent(ACTION_OFF).setPackage(context.packageName), flags
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_incognito_on)
            .setContentTitle(context.getString(R.string.incognito_mode))
            .setContentText(context.getString(R.string.incognito_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.incognito_turn_off), off)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** The "Turn off" button of the notification. */
    class OffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_OFF) return
            PreferenceUtil.incognito = false
            refreshNotification(context)
        }
    }
}
