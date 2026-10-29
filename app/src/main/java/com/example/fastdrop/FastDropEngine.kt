package com.example.fastdrop

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLConnection
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * محرك النقل (بدون أي واجهة) - يشتغل داخل TransferService.
 *
 * الإرسال : FileChannel.transferTo  -> أقل نسخ ممكنة بين الملف والشبكة.
 * الاستقبال: SocketChannel.read في Direct ByteBuffer (1MB) -> FileChannel.write.
 *
 * البروتوكول:
 *   [MAGIC:Int][عدد الملفات:Int][الحجم الكلي:Long]
 *   لكل ملف: [طول الاسم:Short][الاسم UTF-8][الحجم:Long][البايتات]
 *   المستقبل في الآخر يرد ببايت (1) كتأكيد.
 */
class FastDropEngine(private val ctx: Context, private val reporter: Reporter) {

    interface Reporter {
        fun status(text: String)
        fun progress(label: String, percent: Int, mbps: Double)
    }

    class FileInfo(val uri: Uri, val name: String, val size: Long)

    private class Target(
        val out: FileOutputStream,
        val finish: () -> Unit,
        val abort: () -> Unit
    )

    private val open = ConcurrentLinkedQueue<Closeable>()

    @Volatile private var lastActivity = System.nanoTime()
    @Volatile private var stalled = false

    /** متوسط السرعة لآخر عملية نقل (MB/s) */
    @Volatile var avgMbps = 0.0
        private set

    /** يقفل كل الاتصالات فوراً (للإلغاء) - بيخلي أي عملية قراءة/كتابة معلّقة تفشل. */
    fun abort() {
        while (true) {
            val c = open.poll() ?: break
            runCatching { c.close() }
        }
    }

    private fun <T : Closeable> track(c: T): T {
        open.add(c)
        return c
    }

    // ───────────────────────── الإرسال ─────────────────────────

    suspend fun send(files: List<FileInfo>) {
        reporter.status("الحالة: بانتظار المستقبل... اضغط «استقبال» على الجهاز الآخر")
        val ch = track(acceptReceiver())
        ch.use { sendAll(it, files) }
    }

    private suspend fun sendAll(ch: SocketChannel, files: List<FileInfo>) = withStallGuard {
        val total = files.sumOf { it.size }
        reporter.status("الحالة: تم الاتصال، جاري الإرسال...")

        val head = ByteArrayOutputStream()
        DataOutputStream(head).apply {
            writeInt(MAGIC)
            writeInt(files.size)
            writeLong(total)
        }
        writeFully(ch, head.toByteArray())

        val meter = Meter("جاري الإرسال", total)

        for (f in files) {
            val nameBytes = f.name.toByteArray(Charsets.UTF_8)
            val fileHead = ByteArrayOutputStream()
            DataOutputStream(fileHead).apply {
                writeShort(nameBytes.size)
                write(nameBytes)
                writeLong(f.size)
            }
            writeFully(ch, fileHead.toByteArray())

            val pfd = ctx.contentResolver.openFileDescriptor(f.uri, "r")
                ?: throw IOException("تعذر فتح الملف ${f.name}")

            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
                val fc = fis.channel
                var pos = 0L
                while (pos < f.size) {
                    currentCoroutineContext().ensureActive()
                    val n = fc.transferTo(pos, minOf(SEND_CHUNK, f.size - pos), ch)
                    if (n <= 0) throw IOException("حجم الملف ${f.name} تغيّر أثناء الإرسال")
                    pos += n
                    meter.add(n)
                }
            }
        }
        meter.finish()

        // ننتظر تأكيد المستقبل أنه كتب كل شيء
        if (readBytes(ch, 1)[0] != 1.toByte()) throw IOException("المستقبل لم يؤكد الاستلام")
    }

    private suspend fun acceptReceiver(): SocketChannel = coroutineScope {
        val broadcaster = launch { broadcastPresence() }
        try {
            ServerSocketChannel.open().use { server ->
                server.socket().reuseAddress = true
                server.socket().receiveBufferSize = SOCKET_BUFFER
                server.bind(InetSocketAddress(PORT))
                server.configureBlocking(false) // polling قابل للإلغاء
                acceptLoop(server)
            }
        } finally {
            broadcaster.cancel()
        }
    }

    private suspend fun acceptLoop(server: ServerSocketChannel): SocketChannel {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val c = server.accept()
            if (c != null) {
                c.configureBlocking(true)
                c.socket().apply {
                    sendBufferSize = SOCKET_BUFFER
                    keepAlive = true
                    tcpNoDelay = false
                }
                return c
            }
            if (System.currentTimeMillis() > deadline) {
                throw IOException("انتهت مهلة الانتظار، لم يتصل أي مستقبل")
            }
            delay(50)
        }
    }

    /** بث UDP على كل واجهات الشبكة (Wi-Fi + Hotspot) */
    private suspend fun broadcastPresence() {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val payload = DISCOVERY_MESSAGE.toByteArray()
            while (currentCoroutineContext().isActive) {
                for (addr in getBroadcastAddresses()) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                    }
                }
                delay(1000)
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        } catch (_: Exception) {
        }
        result.add(InetAddress.getByName("255.255.255.255"))
        return result.toList()
    }

    // ───────────────────────── الاستقبال ─────────────────────────

    /** يرجع عدد الملفات المستلمة */
    suspend fun receive(senderIp: String? = null): Int {
        reporter.status(
            if (senderIp == null) "الحالة: جاري البحث عن مرسل في الشبكة..."
            else "الحالة: جاري الاتصال بالمرسل..."
        )
        val ip = senderIp ?: findSender()
        reporter.status("تم العثور على المرسل ($ip)، جاري الاتصال...")

        val ch = track(SocketChannel.open())
        return ch.use { receiveAll(it, ip) }
    }

    private suspend fun receiveAll(ch: SocketChannel, ip: String): Int {
        ch.socket().apply {
            receiveBufferSize = SOCKET_BUFFER // لازم قبل connect
            keepAlive = true
        }
        ch.socket().connect(InetSocketAddress(ip, PORT), 5000)

        return withStallGuard {
            val head = DataInputStream(ByteArrayInputStream(readBytes(ch, 16)))
            if (head.readInt() != MAGIC) throw IOException("بروتوكول غير متوافق")
            val count = head.readInt()
            val total = head.readLong()
            if (count !in 1..MAX_FILES || total < 0) throw IOException("بيانات غير صالحة من المرسل")

            val meter = Meter("جاري الاستقبال", total)
            val buf = ByteBuffer.allocateDirect(RECV_BUFFER)
            val b: Buffer = buf

            repeat(count) {
                val nameLen = ByteBuffer.wrap(readBytes(ch, 2)).short.toInt() and 0xFFFF
                if (nameLen > 2048) throw IOException("اسم ملف غير صالح")
                val name = sanitizeFileName(String(readBytes(ch, nameLen), Charsets.UTF_8))
                val size = ByteBuffer.wrap(readBytes(ch, 8)).long
                if (size < 0) throw IOException("حجم ملف غير صالح")

                val target = createTarget(name)
                var ok = false
                try {
                    target.out.use { fos ->
                        val fc = fos.channel
                        var remaining = size
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            b.clear()
                            if (remaining < buf.capacity()) b.limit(remaining.toInt())
                            val n = ch.read(buf)
                            if (n < 0) throw EOFException("انقطع الاتصال أثناء استقبال $name")
                            b.flip()
                            while (buf.hasRemaining()) fc.write(buf)
                            remaining -= n
                            meter.add(n.toLong())
                        }
                    }
                    ok = true
                } finally {
                    if (ok) target.finish() else target.abort() // لا نترك ملفات ناقصة
                }
            }
            meter.finish()

            writeFully(ch, byteArrayOf(1)) // تأكيد الاستلام
            count
        }
    }

    private suspend fun findSender(): String {
        // بعض الأجهزة تتجاهل حزم البث بدون MulticastLock
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("fastdrop").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                socket.soTimeout = 1000

                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val msg = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress
                    if (msg == DISCOVERY_MESSAGE && ip != null) return ip
                }
            }
            throw IOException("لم يتم العثور على مرسل، تأكد أن الجهازين على نفس الشبكة")
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    // ───────────────────────── الملفات ─────────────────────────

    fun resolveFile(uri: Uri): FileInfo {
        var name: String? = null
        var size = -1L
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }
        if (size < 0) throw IOException("تعذر معرفة حجم الملف")
        return FileInfo(uri, sanitizeFileName(name ?: uri.lastPathSegment ?: "file"), size)
    }

    private fun sanitizeFileName(raw: String): String {
        val clean = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(200)
        return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
    }

    private fun createTarget(name: String): Target {
        val resolver = ctx.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                )
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FastDrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("تعذر إنشاء الملف في التنزيلات")
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("تعذر فتح الملف للكتابة")
            return Target(
                out = ParcelFileDescriptor.AutoCloseOutputStream(pfd),
                finish = {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                },
                abort = { resolver.delete(uri, null, null) }
            )
        }

        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FastDrop"
        )
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("تعذر إنشاء مجلد FastDrop")
        val file = uniqueFile(dir, name)
        return Target(
            out = FileOutputStream(file),
            finish = { MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null) },
            abort = { file.delete() }
        )
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    // ───────────────────────── أدوات مساعدة ─────────────────────────

    private fun writeFully(ch: SocketChannel, data: ByteArray) {
        val b = ByteBuffer.wrap(data)
        while (b.hasRemaining()) ch.write(b)
    }

    private fun readBytes(ch: SocketChannel, n: Int): ByteArray {
        val arr = ByteArray(n)
        val b = ByteBuffer.wrap(arr)
        while (b.hasRemaining()) {
            if (ch.read(b) < 0) throw EOFException("انقطع الاتصال")
        }
        return arr
    }

    /**
     * حارس التوقف: لو مفيش ولا بايت اتنقل لمدة STALL_MS (واي فاي فصل مثلاً)
     * بيقفل الاتصال بدل ما النقل يفضل معلّق للأبد.
     */
    private suspend fun <T> withStallGuard(block: suspend () -> T): T = coroutineScope {
        lastActivity = System.nanoTime()
        stalled = false
        val guard = launch {
            while (true) {
                delay(5000)
                if (System.nanoTime() - lastActivity > STALL_NS) {
                    stalled = true
                    abort()
                    break
                }
            }
        }
        try {
            block()
        } catch (e: IOException) {
            if (stalled) throw IOException("توقف النقل لفترة طويلة، تحقق من اتصال الشبكة", e)
            throw e
        } finally {
            guard.cancel()
        }
    }

    private inner class Meter(private val label: String, private val total: Long) {
        private val start = System.nanoTime()
        private var done = 0L
        private var winStart = start
        private var winBytes = 0L

        fun add(n: Long) {
            done += n
            winBytes += n
            val now = System.nanoTime()
            lastActivity = now
            if (now - winStart >= 500_000_000L) {
                val mbps = winBytes / ((now - winStart) / 1e9) / MB
                winStart = now
                winBytes = 0
                reporter.progress(label, percent(), mbps)
            }
        }

        fun finish() {
            val secs = (System.nanoTime() - start) / 1e9
            avgMbps = if (secs > 0) done / secs / MB else 0.0
            reporter.progress(label, 100, avgMbps)
        }

        private fun percent() =
            if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 100
    }

    companion object {
        private const val PORT = 8888
        private const val DISCOVERY_PORT = 8889
        private const val DISCOVERY_MESSAGE = "FAST_DROP_SERVER_HERE"
        private const val MAGIC = 0x46445250 // "FDRP"
        private const val MAX_FILES = 10_000

        private const val SEND_CHUNK = 8L * 1024 * 1024  // كل استدعاء transferTo
        private const val RECV_BUFFER = 1024 * 1024      // Direct buffer للاستقبال
        private const val SOCKET_BUFFER = 2 * 1024 * 1024
        private const val WAIT_TIMEOUT_MS = 180_000L
        private const val STALL_NS = 30_000_000_000L
        private const val MB = 1_048_576.0
    }
}
