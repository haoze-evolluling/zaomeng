package top.wkbin.zaomeng.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import top.wkbin.zaomeng.MainActivity
import top.wkbin.zaomeng.R
import top.wkbin.zaomeng.data.api.RunManifestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DistillationForegroundService : Service(), KoinComponent {
    private val backend: EmbeddedBackendController by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            finishMonitoring()
            return START_NOT_STICKY
        }

        if (!startAsForeground(buildStartingNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch { monitorRunningDistillations() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        finishMonitoring()
    }

    override fun onDestroy() {
        monitorJob = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun monitorRunningDistillations() {
        while (serviceScope.isActive) {
            try {
                when (backend.state.value) {
                    is BackendState.Failed -> backend.retry()
                    else -> backend.start()
                }
                val running = backend.requireApi()
                    .listRuns()
                    .items
                    .filter { it.status == RUNNING_STATUS }
                if (running.isEmpty()) {
                    finishMonitoring()
                    return
                }
                updateNotification(buildProgressNotification(running))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateNotification(buildReconnectNotification())
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun buildStartingNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.distillation_notification_starting_title))
            .setContentText(getString(R.string.distillation_notification_starting_text))
            .setProgress(0, 0, true)
            .build()

    private fun buildReconnectNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.distillation_notification_running_title))
            .setContentText(getString(R.string.distillation_notification_reconnecting))
            .setProgress(0, 0, true)
            .build()

    private fun buildProgressNotification(runs: List<RunManifestDto>): Notification {
        val primary = runs.first()
        val total = runs.sumOf { maxOf(it.progress.totalCharacters, it.lockedCharacters.size) }
        val completed = runs.sumOf { maxOf(it.progress.completedCount, it.availableCharacters.size) }
            .coerceAtMost(total.coerceAtLeast(0))
        val title = if (runs.size == 1) {
            getString(R.string.distillation_notification_single_title, primary.title)
        } else {
            getString(R.string.distillation_notification_multiple_title, runs.size)
        }
        val text = primary.progress.message.ifBlank {
            primary.progress.currentCharacter
                .takeIf(String::isNotBlank)
                ?.let { getString(R.string.distillation_notification_character, it) }
                ?: getString(R.string.distillation_notification_running_text)
        }
        return notificationBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(total, completed, total <= 0)
            .build()
    }

    private fun notificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_distillation_notification)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.distillation_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.distillation_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: SecurityException) {
        false
    }

    private fun updateNotification(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android still exposes foreground-service state when notification permission is denied.
        }
    }

    private fun finishMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val ACTION_START_MONITORING =
            "top.wkbin.zaomeng.action.START_DISTILLATION_MONITORING"
        internal const val ACTION_STOP_MONITORING =
            "top.wkbin.zaomeng.action.STOP_DISTILLATION_MONITORING"
        private const val NOTIFICATION_CHANNEL_ID = "distillation_progress"
        private const val NOTIFICATION_ID = 4101
        private const val RUNNING_STATUS = "running"
        private const val POLL_INTERVAL_MS = 2_000L
    }
}

object DistillationForegroundController {
    const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun start(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, DistillationForegroundService::class.java)
            .setAction(DistillationForegroundService.ACTION_START_MONITORING)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, DistillationForegroundService::class.java))
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
}
