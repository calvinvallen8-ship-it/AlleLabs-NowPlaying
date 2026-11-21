package com.allelabs.nowplaying

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val mono: Typeface = Typeface.MONOSPACE
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val grey = Color.parseColor("#AAAAAA")

    private lateinit var tvConn: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvPlay: TextView
    private lateinit var tvLog: TextView

    private class SetupRow(
        val name: String,
        val check: () -> Boolean,
        val statusView: TextView,
        val button: Button
    )

    private val rows = mutableListOf<SetupRow>()
    private var lastRebindAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    // ---------------- Pengecekan izin ----------------

    private fun bluetoothOk(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun notificationOk(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun requestBluetooth() {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(perms, 1)
    }

    // ---------------- Akses Notifikasi (Android 13+ / 16) ----------------

    /** Buka halaman Info Aplikasi, tempat menu titik tiga > "Izinkan setelan terbatas". */
    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Buka layar akses notifikasi; di Android 11+ langsung ke halaman detail aplikasi ini. */
    private fun openNotificationAccess() {
        val component = ComponentName(this, NowPlayingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component.flattenToString()
                    )
                )
                return
            }
        } catch (_: Exception) {
            // jatuh ke layar daftar di bawah
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Minta sistem menyambungkan ulang service setelah izin diberikan. */
    private fun rebindListener() {
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, NowPlayingService::class.java)
            )
        } catch (_: Exception) {}
    }

    private fun showNotificationHelp() {
        val steps = if (Build.VERSION.SDK_INT >= 33) {
            "Kalau tombol Akses Notifikasi abu-abu / muncul \"Setelan dibatasi\", itu karena " +
                "aplikasi ini di-install dari luar Play Store. Cara membukanya:\n\n" +
                "1. Tekan \"Info Aplikasi\"\n" +
                "2. Tekan titik tiga (⋮) di pojok kanan atas\n" +
                "3. Pilih \"Izinkan setelan terbatas\" (Allow restricted settings), lalu konfirmasi PIN/sidik jari\n" +
                "4. Kembali, tekan \"Akses Notifikasi\", nyalakan Alle Labs Now Playing\n\n" +
                "Kalau menu titik tiga tidak muncul, buka Akses Notifikasi dulu, tekan toggle yang " +
                "abu-abu sekali (muncul pesan dibatasi), lalu ulangi langkah 1-4."
        } else {
            "Nyalakan toggle Alle Labs Now Playing di layar Akses Notifikasi."
        }
        AlertDialog.Builder(this)
            .setTitle("Beri akses Notifikasi")
            .setMessage(steps)
            .setPositiveButton("Info Aplikasi") { _, _ -> openAppInfo() }
            .setNeutralButton("Akses Notifikasi") { _, _ -> openNotificationAccess() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ---------------- Helper tampilan ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) monoBold else mono
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#111111"))
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(4).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun whiteButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = monoBold
        textSize = 12f
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(4).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun addRow(parent: LinearLayout, name: String, buttonLabel: String,
                       check: () -> Boolean, action: () -> Unit) {
        val line = text("", 13f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        val btn = whiteButton(buttonLabel, action)
        parent.addView(line)
        parent.addView(btn)
        rows.add(SetupRow(name, check, line, btn))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }

        // Judul
        root.addView(text("ALLE LABS", 24f, bold = true))
        root.addView(View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(2)).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
        })
        root.addView(text("NOW PLAYING BRIDGE", 11f, grey).apply { setPadding(0, 0, 0, dp(18)) })

        // Kartu status keychain
        val connCard = card()
        connCard.addView(text("KEYCHAIN", 11f, grey))
        tvConn = text("", 18f, bold = true).apply { setPadding(0, dp(6), 0, dp(4)) }
        tvHint = text("", 11f, grey)
        connCard.addView(tvConn)
        connCard.addView(tvHint)
        connCard.addView(whiteButton("Sambung ulang") {
            AppState.reconnectRequested = true
            AppState.log("manual: sambung ulang")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
        root.addView(connCard)

        // Kartu lagu
        val songCard = card()
        songCard.addView(text("NOW PLAYING", 11f, grey))
        tvTitle = text("", 18f, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) }
        tvArtist = text("", 13f, grey)
        tvPlay = text("", 12f, bold = true).apply { setPadding(0, dp(8), 0, 0) }
        songCard.addView(tvTitle)
        songCard.addView(tvArtist)
        songCard.addView(tvPlay)
        root.addView(songCard)

        // Kartu pengaturan (tombol hilang kalau sudah OK)
        val setupCard = card()
        setupCard.addView(text("PENGATURAN (SEKALI SAJA)", 11f, grey))
        addRow(setupCard, "Izin Bluetooth", "Izinkan Bluetooth", ::bluetoothOk) { requestBluetooth() }
        addRow(setupCard, "Akses Notifikasi", "Beri akses Notifikasi", ::notificationOk) {
            // Android 13+: sideload kena "setelan dibatasi", jadi tampilkan panduan dulu
            if (Build.VERSION.SDK_INT >= 33) showNotificationHelp() else openNotificationAccess()
        }
        addRow(setupCard, "Hemat baterai dimatikan", "Matikan hemat baterai", ::batteryOk) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        root.addView(setupCard)

        // Kartu log (membantu kalau ada masalah sambungan)
        val logCard = card()
        logCard.addView(text("LOG", 11f, grey))
        tvLog = text("", 11f, grey).apply { setPadding(0, dp(6), 0, 0) }
        logCard.addView(tvLog)
        root.addView(logCard)

        root.addView(text(
            "Di keychain: Slot 6, tahan tombol 3 detik untuk menyalakan Bluetooth.",
            11f, grey
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------------- Pembaruan layar ----------------

    private fun refresh() {
        val s = AppState

        // Izin sudah diberikan tapi service belum tersambung: minta sistem bind ulang
        if (notificationOk() && !s.listenerRunning) {
            val now = System.currentTimeMillis()
            if (now - lastRebindAt > 5000) {
                lastRebindAt = now
                rebindListener()
            }
        }

        // Status koneksi
        when {
            !s.listenerRunning -> {
                tvConn.text = "○ BELUM AKTIF"
                tvHint.text = "Beri akses Notifikasi di pengaturan bawah (ada panduan di tombolnya)."
            }
            s.connected -> {
                tvConn.text = "● TERHUBUNG"
                tvHint.text = "Lagu dikirim otomatis ke layar keychain."
            }
            else -> {
                tvConn.text = "○ " + s.bleStatus.uppercase()
                tvHint.text = "Nyalakan Bluetooth keychain: Slot 6, tahan 3 detik."
            }
        }

        // Lagu
        if (s.title.isEmpty() && s.artist.isEmpty()) {
            tvTitle.text = "(belum ada lagu)"
            tvArtist.text = ""
            tvPlay.text = "—"
        } else {
            tvTitle.text = if (s.title.isEmpty()) "-" else s.title
            tvArtist.text = s.artist
            tvPlay.text = if (s.playing) "▶ PLAYING" else "❚❚ PAUSED"
        }

        tvLog.text = AppState.logText().ifEmpty { "(kosong)" }

        // Pengaturan: centang kalau OK, tombol hanya muncul kalau belum
        for (r in rows) {
            val ok = r.check()
            r.statusView.text = (if (ok) "✓ " else "✗ ") + r.name
            r.statusView.setTextColor(if (ok) Color.WHITE else grey)
            r.button.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }
}
