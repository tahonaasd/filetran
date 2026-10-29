package com.example.fastdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Locale

/** حالة الواجهة - الخدمة بتكتب فيها والـ Activity بتقرأ منها. */
data class UiState(
    val running: Boolean = false,
    val text: String = "الحالة: جاهز",
    val percent: Int = 0,
    val indeterminate: Boolean = false
)

object TransferState {
    val state = MutableStateFlow(UiState())
}

/**
 * Foreground Service: يكمل النقل حتى لو الشاشة اتقفلت أو التطبيق راح للخلفية.
 * بيمسك WakeLock + WifiLock ويتوقف لما النقل يخلص أو لما التطبيق يتشال من الأخيرة (Recents).
 */
class TransferService : Service() {

    companion object {
        const val ACTION_SEND = "com.example.fastdrop.SEND"
        const val ACTION_RECEIVE = "com.example.fastdrop.RECEIVE"
        const val ACTION_STOP = "com.example.fastdrop.STOP"
        const val EXTRA_URIS = "uris"
        const val EXTRA_DIRECT = "direct"
        const val EXTRA_PIN = "pin"

        private const val CHANNEL_ID = "fastdrop_transfer"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var engine: FastDropEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotify = 0L

    /** الرقم السري يظهر مع حالة الانتظار على شاشة المرسل فقط (مش في الإشعار). */
    @Volatile private var pinHint = ""

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "نقل الملفات", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> stopTransfer()
            ACTION_SEND, ACTION_RECEIVE -> {
                // لازم startForeground فوراً (خلال 5 ثواني من startForegroundService)
                startInForeground()
                if (job?.isActive != true) startJob(intent)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startJob(intent: Intent) {
        val send = intent.action == ACTION_SEND
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        val joinPin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().map { Uri.parse(it) }

        acquireLocks()
        pinHint = ""
        publish(UiState(true, "الحالة: جاري التجهيز...", 0, true))

        val eng = FastDropEngine(applicationContext, reporter)
        engine = eng

        job = scope.launch {
            var link: P2pLink? = null
            val message = try {
                if (send) {
                    val files = uris.map { eng.resolveFile(it) }
                    if (direct) {
                        val pin = randomPin()
                        pinHint = "\nالرقم السري: $pin"
                        reporter.status("الحالة: جاري إنشاء شبكة Wi-Fi Direct (5GHz)...")
                        val l = P2pLink(applicationContext)
                        link = l
                        l.host(pin)
                    }
                    eng.send(files)
                    "تم إرسال ${files.size} ملف بنجاح ✅ (${speed(eng)})"
                } else {
                    var senderIp: String? = null
                    if (direct) {
                        reporter.status("الحالة: جاري الاتصال بشبكة المرسل (Wi-Fi Direct)...")
                        val l = P2pLink(applicationContext)
                        link = l
                        senderIp = l.join(joinPin)
                    }
                    val n = eng.receive(senderIp)
                    "تم استقبال $n ملف وحفظها في التنزيلات/FastDrop ✅ (${speed(eng)})"
                }
            } catch (e: CancellationException) {
                "تم الإلغاء"
            } catch (e: Exception) {
                if (isActive) "خطأ: ${e.message ?: e.javaClass.simpleName}" else "تم الإلغاء"
            } finally {
                link?.close()
            }

            pinHint = ""
            publish(UiState(false, "الحالة: $message", 0, false))
            releaseLocks()
            uris.forEach {
                runCatching {
                    contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            showDoneNotification(message)
            stopSelf()
        }
    }

    private fun randomPin() = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))

    private fun speed(eng: FastDropEngine) =
        String.format(Locale.US, "متوسط %.1f MB/s", eng.avgMbps)

    private fun stopTransfer() {
        engine?.abort()
        val j = job
        if (j == null || !j.isActive) stopSelf() else j.cancel()
    }

    /** المستخدم شال التطبيق من الأخيرة = "قفل البرنامج" -> نوقف النقل. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopTransfer()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engine?.abort()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    // ───────────────────────── التقرير والإشعارات ─────────────────────────

    private val reporter = object : FastDropEngine.Reporter {
        override fun status(text: String) {
            publish(UiState(true, text + pinHint, 0, true))
            notifyProgress(text, 0, true, force = true)
        }

        override fun progress(label: String, percent: Int, mbps: Double) {
            pinHint = ""
            val text = String.format(Locale.US, "%s %d%% • %.1f MB/s", label, percent, mbps)
            publish(UiState(true, text, percent, false))
            notifyProgress(text, percent, false)
        }
    }

    private fun publish(s: UiState) {
        TransferState.state.value = s
    }

    private fun notifyProgress(text: String, percent: Int, indeterminate: Boolean, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return // أندرويد بيحد معدل تحديث الإشعارات
        lastNotify = now
        nm.notify(NOTIF_ID, buildNotification(text, percent, indeterminate))
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("جاري التجهيز...", 0, true), type
        )
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FastDrop")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", stop)
            .build()
    }

    private fun showDoneNotification(message: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("FastDrop")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        nm.notify(DONE_ID, n)
    }

    // ───────────────────────── Locks ─────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fastdrop:transfer").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // حد أمان 6 ساعات
        }

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fastdrop:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }
}
