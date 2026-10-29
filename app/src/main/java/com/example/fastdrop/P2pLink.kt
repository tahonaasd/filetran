package com.example.fastdrop

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * وضع Wi-Fi Direct السريع:
 *  - المرسل ينشئ مجموعة (Group Owner) على نطاق 5GHz باسم وكلمة سر مشتقين من PIN من 6 أرقام.
 *  - المستقبل يدخل نفس الشبكة بنفس الـ PIN (بدون نافذة موافقة من النظام) ويرجع IP المرسل.
 * بعد كده الـ FastDropEngine بيشتغل زي ما هو فوق الشبكة الجديدة.
 *
 * يحتاج أندرويد 10+ وصلاحية NEARBY_WIFI_DEVICES (13+) أو الموقع (12 وأقل).
 * السلوك بيختلف شوية من شركة لشركة، فالوضع العادي (نفس الشبكة) متاح دايماً كبديل.
 */
@SuppressLint("MissingPermission")
class P2pLink(context: Context) {

    private val ctx: Context = context.applicationContext
    private val manager: WifiP2pManager
    private val channel: WifiP2pManager.Channel

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("وضع Wi-Fi Direct يحتاج أندرويد 10 أو أحدث")
        }
        manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IOException("الجهاز لا يدعم Wi-Fi Direct")
        channel = manager.initialize(ctx, Looper.getMainLooper(), null)
            ?: throw IOException("تعذر تهيئة Wi-Fi Direct")
    }

    // ───────────── المرسل ─────────────

    suspend fun host(pin: String) {
        val (name, pass) = credentials(pin)
        removeGroupQuietly() // نظّف أي مجموعة قديمة عالقة

        try {
            createGroup(buildConfig(name, pass, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ))
        } catch (e: IOException) {
            // الجهاز لا يدعم 5GHz للمجموعة -> نسيب النظام يختار النطاق
            removeGroupQuietly()
            createGroup(buildConfig(name, pass, WifiP2pConfig.GROUP_OWNER_BAND_AUTO))
        }
    }

    private fun buildConfig(name: String, pass: String, band: Int): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(pass)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()

    private suspend fun createGroup(cfg: WifiP2pConfig) = suspendCancellableCoroutine<Unit> { c ->
        manager.createGroup(channel, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (c.isActive) c.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                if (c.isActive) {
                    c.resumeWithException(IOException("فشل إنشاء شبكة Wi-Fi Direct (${reasonText(reason)})"))
                }
            }
        })
    }

    // ───────────── المستقبل ─────────────

    /** يدخل شبكة المرسل ويرجع عنوان IP الخاص بالمرسل (Group Owner). */
    suspend fun join(pin: String): String {
        val (name, pass) = credentials(pin)
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(pass)
            .build()

        var receiver: BroadcastReceiver? = null
        try {
            val ip = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { c ->
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                                val addr = info?.groupOwnerAddress?.hostAddress
                                if (info != null && info.groupFormed && !info.isGroupOwner &&
                                    addr != null && c.isActive
                                ) {
                                    c.resume(addr)
                                }
                            }
                        }
                    }
                    receiver = r
                    ContextCompat.registerReceiver(
                        ctx, r,
                        IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    manager.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) {
                            if (c.isActive) {
                                c.resumeWithException(
                                    IOException("فشل الاتصال بـ Wi-Fi Direct (${reasonText(reason)})")
                                )
                            }
                        }
                    })
                }
            } ?: throw IOException("انتهت مهلة الاتصال، تأكد من الرقم السري وأن المرسل شغّال")
            return ip
        } finally {
            receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        }
    }

    // ───────────── أدوات ─────────────

    /** يفصل الاتصال ويمسح المجموعة. آمن للاستدعاء حتى لو العملية اتلغت. */
    suspend fun close() = withContext(NonCancellable) {
        runCatching { manager.cancelConnect(channel, null) }
        runCatching { manager.removeGroup(channel, null) }
        delay(300) // نسيب النظام يكمّل الفصل قبل قفل القناة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { channel.close() }
    }

    private suspend fun removeGroupQuietly() {
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Unit> { c ->
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (c.isActive) c.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (c.isActive) c.resume(Unit)
                    }
                })
            }
        }
    }

    /** اسم الشبكة وكلمة السر مشتقين من الـ PIN (الاسم لازم يبدأ بـ DIRECT-xx). */
    private fun credentials(pin: String): Pair<String, String> {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest("fastdrop:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "DIRECT-FD-${hex.substring(0, 6)}" to hex.substring(8, 32)
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "غير مدعوم"
        WifiP2pManager.BUSY -> "الجهاز مشغول، أعد المحاولة"
        WifiP2pManager.ERROR -> "خطأ داخلي"
        else -> "كود $r"
    }

    companion object {
        private const val JOIN_TIMEOUT_MS = 45_000L
    }
}
