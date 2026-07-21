package com.v2raytester

import android.Manifest
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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Progress bus between [TesterViewModel] and [RunService].
 *
 * The ViewModel owns the actual run; this just mirrors the status line + progress so the
 * service can render them in its notification, and carries a stop callback back the other
 * way (notification "Stop" button).
 */
object RunProgress {
    data class State(
        val running: Boolean = false,
        val text: String = "",
        val progress: Float = 0f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the ViewModel so the notification's Stop action can halt the run. */
    @Volatile
    var stopHandler: (() -> Unit)? = null

    fun publish(running: Boolean, text: String, progress: Float) {
        _state.value = State(running, text, progress)
    }
}

/**
 * Foreground service held for the duration of a test run / subscription fetch.
 *
 * Why it exists: on modern Android a backgrounded app is frozen (cached state) within
 * seconds, which suspends our coroutines AND the spawned xray child processes — the run
 * appeared to "stop" as soon as the user switched apps. A foreground service exempts the
 * process from that freeze; its mandatory notification is reused as the progress display.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Screen-off must not pause the run either.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2raytester:run")
            .apply {
                // non-counted: re-acquiring only re-arms the timeout, and a single
                // release() in onDestroy always clears it (counted locks would leak).
                setReferenceCounted(false)
                runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
            }

        scope.launch {
            RunProgress.state.collect { s ->
                if (!s.running) { finish(); return@collect }
                // throttle: a big run emits thousands of updates; the notification only
                // needs a couple per second.
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= MIN_UPDATE_MS) {
                    lastUpdate = now
                    notify(build(s))
                    // Re-arm the wake lock while the run is making progress, so a run
                    // longer than the timeout doesn't silently lose the CPU.
                    wakeLock?.let { runCatching { it.acquire(WAKE_LOCK_TIMEOUT_MS) } }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RunProgress.stopHandler?.invoke()
            finish()
            return START_NOT_STICKY
        }
        // Must post the notification promptly or the system kills us (ANR-style crash).
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(RunProgress.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }

    /** App swiped away from recents: the ViewModel dies with the process, so stop too. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        finish()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build(s: RunProgress.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = (s.progress.coerceIn(0f, 1f) * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("V2Ray Tester")
            .setContentText(s.text.ifEmpty { "working…" })
            .setProgress(100, pct, s.progress <= 0f)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return   // no permission: the service still runs, just without a visible update
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID, "Test progress", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while configs are being tested"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    companion object {
        const val ACTION_STOP = "com.v2raytester.STOP_RUN"
        private const val CHANNEL_ID = "run"
        private const val NOTIF_ID = 1
        private const val MIN_UPDATE_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
        }
    }
}
