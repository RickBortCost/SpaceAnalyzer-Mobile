package it.spaceanalyzer.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import it.spaceanalyzer.mobile.MainActivity
import it.spaceanalyzer.mobile.SfaApp
import it.spaceanalyzer.mobile.link.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the link is active so reception and recording continue with the
 * screen off. It owns no connection: the session lives in [SfaApp]. Stops when the link ends.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Collegamento PC", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Collegamento in corso"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val session = (application as SfaApp).session
        scope.launch {
            combine(session.state, session.recording) { s, r -> s to r }.collect { (state, rec) ->
                if (state is LinkState.Idle || state is LinkState.Failed) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else {
                    val text = when (state) {
                        is LinkState.Streaming -> "Ricezione da ${state.serverName}" + if (rec.file != null) " • registrazione attiva" else ""
                        is LinkState.Reconnecting -> "Riconnessione… (${state.reason})"
                        else -> "Collegamento in corso"
                    }
                    manager.notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Space Fieldbus Analyzer")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    private companion object {
        const val CHANNEL = "link"
        const val NOTIFICATION_ID = 1
    }
}
