package com.example.fastdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** الواجهة فقط. النقل الفعلي بيتم في TransferService عشان يكمل والشاشة مقفولة. */
class MainActivity : AppCompatActivity() {

    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var btnStop: Button
    private lateinit var cbDirect: CheckBox
    private lateinit var etPin: EditText
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private var afterPermission: (() -> Unit)? = null

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            // نحتفظ بصلاحية القراءة عشان الخدمة تقدر تفتح الملفات في الخلفية
            uris.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            withDirectPermission { startTransfer(TransferService.ACTION_SEND, uris, null) }
        }

    // صلاحية التخزين مطلوبة فقط على أندرويد 9 وأقل
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startReceive()
            else showError("يجب السماح بصلاحية التخزين")
        }

    // صلاحية Wi-Fi Direct: الأجهزة القريبة (13+) أو الموقع (12 وأقل)
    private val directPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = afterPermission
            afterPermission = null
            if (granted) next?.invoke()
            else showError("وضع Wi-Fi Direct يحتاج صلاحية الأجهزة القريبة / الموقع")
        }

    // إذن الإشعارات (أندرويد 13+)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        btnStop = findViewById(R.id.btnStop)
        cbDirect = findViewById(R.id.cbDirect)
        etPin = findViewById(R.id.etPin)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        cbDirect.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnSend.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        btnReceive.setOnClickListener { onReceiveClicked() }
        btnStop.setOnClickListener {
            startService(
                Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP)
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferState.state.collect { render(it) }
            }
        }
    }

    private fun render(s: UiState) {
        tvStatus.text = s.text
        progressBar.isIndeterminate = s.indeterminate
        progressBar.progress = s.percent
        btnSend.isEnabled = !s.running
        btnReceive.isEnabled = !s.running
        cbDirect.isEnabled = !s.running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        etPin.isEnabled = !s.running
        btnStop.isEnabled = s.running
    }

    private fun showError(msg: String) {
        TransferState.state.value = UiState(text = "الحالة: $msg")
    }

    // ───────────────────────── الاستقبال ─────────────────────────

    private fun onReceiveClicked() {
        val pin = etPin.text.toString().trim()
        if (cbDirect.isChecked && !Regex("\\d{6}").matches(pin)) {
            showError("اكتب الرقم السري (6 أرقام) الظاهر على جهاز المرسل")
            return
        }

        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

        if (needsStorage) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else startReceive()
    }

    private fun startReceive() {
        val pin = etPin.text.toString().trim()
        withDirectPermission { startTransfer(TransferService.ACTION_RECEIVE, emptyList(), pin) }
    }

    // ───────────────────────── مساعدات ─────────────────────────

    private fun directPermissionName() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION

    private fun withDirectPermission(block: () -> Unit) {
        val name = directPermissionName()
        val granted = ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
        if (!cbDirect.isChecked || granted) {
            block()
        } else {
            afterPermission = block
            directPermission.launch(name)
        }
    }

    private fun startTransfer(action: String, uris: List<Uri>, pin: String?) {
        // نقفل الأزرار فوراً لتفادي الضغط مرتين
        TransferState.state.value =
            UiState(running = true, text = "الحالة: جاري التجهيز...", indeterminate = true)

        val intent = Intent(this, TransferService::class.java)
            .setAction(action)
            .putExtra(TransferService.EXTRA_DIRECT, cbDirect.isChecked)
        if (pin != null) intent.putExtra(TransferService.EXTRA_PIN, pin)
        if (uris.isNotEmpty()) {
            intent.putStringArrayListExtra(
                TransferService.EXTRA_URIS, ArrayList(uris.map { it.toString() })
            )
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
