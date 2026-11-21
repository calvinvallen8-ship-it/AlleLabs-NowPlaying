package com.allelabs.nowplaying

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val mono: Typeface = Typeface.MONOSPACE
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val grey = Color.parseColor("#AAAAAA")
    private val adbCommand = "adb install -r app-debug.apk"

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
        val button: Button,
        val hint: TextView? = null
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
        val msg = when {
            Build.VERSION.SDK_INT < 33 ->
                "Nyalakan toggle Alle Labs Now Playing di layar Akses Notifikasi."
            isSideloadRestricted() -> buildString {
                append("APK ini terdeteksi di-install dari luar Play Store (Telegram/Chrome/file ")
                    .append("manager/dll). Android 13 s.d. 16 otomatis memblokir akses Notifikasi ")
                    .append("untuk install seperti itu.\n\n")
                    .append("CARA MEMBUKANYA:\n")
                    .append("1. Tekan tombol \"Info Aplikasi\" di bawah\n")
                    .append("2. Tekan titik tiga (⋮) di pojok kanan atas\n")
                    .append("3. Pilih \"Izinkan setelan terbatas\" (Allow restricted settings), ")
                    .append("lalu konfirmasi PIN/sidik jari\n")
                    .append("4. Kembali, tekan \"Akses Notifikasi\", nyalakan Alle Labs Now Playing\n\n")
                    .append("Kalau menu titik tiga / \"Izinkan setelan terbatas\" TIDAK ADA atau toggle ")
                    .append("tetap abu-abu (sering terjadi di Android 15/16), install ulang APK lewat PC ")
                    .append("dengan ADB. Install lewat ADB tidak kena blokir, jadi toggle langsung bisa ")
                    .append("dinyalakan:\n")
                    .append(adbCommand)
                    .append("\n(HP disambung USB ke PC + \"USB debugging\" diaktifkan di menu Opsi Pengembang)")
            }
            else -> buildString {
                append("Buka layar Akses Notifikasi dan nyalakan Alle Labs Now Playing.\n\n")
                    .append("Kalau toggle abu-abu / muncul \"Setelan dibatasi\": tekan \"Info Aplikasi\", ")
                    .append("tekan titik tiga (⋮) di pojok kanan atas, pilih \"Izinkan setelan terbatas\", ")
                    .append("lalu ulangi.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Beri akses Notifikasi")
            .setMessage(msg)
            .setPositiveButton("Info Aplikasi") { _, _ -> openAppInfo() }
            .setNeutralButton("Akses Notifikasi") { _, _ -> openNotificationAccess() }
            .setNegativeButton("Salin perintah ADB") { _, _ -> copyAdbCommand() }
            .show()
    }

    /** True kalau aplikasi terpasang dari sumber yang kena "setelan terbatas" (sideload). */
    private fun isSideloadRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val info = packageManager.getInstallSourceInfo(packageName)
            if (Build.VERSION.SDK_INT >= 34) {
                when (info.packageSource) {
                    PackageInstaller.PackageSource.PACKAGE_SOURCE_STORE -> false
                    PackageInstaller.PackageSource.PACKAGE_SOURCE_UNSPECIFIED ->
                        installedByUntrusted(info.initiatingPackageName)
                    else -> true   // LOCAL_FILE / DOWNLOADED_FILE / OTHER
                }
            } else {
                installedByUntrusted(info.initiatingPackageName)
            }
        } catch (_: Exception) { false }
    }

    /** ADB (com.android.shell) dan Play Store dikecualikan dari blokir; sisanya tidak. */
    private fun installedByUntrusted(initiator: String?): Boolean =
        initiator != null && initiator != "com.android.shell" && initiator != "com.android.vending"

    private fun copyAdbCommand() {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
            Toast.makeText(this, "Perintah disalin: $adbCommand", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
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
                       check: () -> Boolean, action: () -> Unit, hint: String? = null) {
        val line = text("", 13f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        val btn = whiteButton(buttonLabel, action)
        parent.addView(line)
        parent.addView(btn)
        var hintView: TextView? = null
        if (hint != null) {
            hintView = text(hint, 11f, grey).apply {
                setPadding(0, dp(4), 0, 0)
                visibility = View.GONE
            }
            parent.addView(hintView)
        }
        rows.add(SetupRow(name, check, line, btn, hintView))
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
        addRow(setupCard, "Akses Notifikasi", "Beri akses Notifikasi", ::notificationOk, {
            // Android 13+: sideload kena "setelan dibatasi", jadi tampilkan panduan dulu
            if (Build.VERSION.SDK_INT >= 33) showNotificationHelp() else openNotificationAccess()
        }, "Toggle abu-abu / \"Setelan dibatasi\"? Tekan tombolnya — ada panduan lengkap + perintah ADB.")
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
            r.hint?.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }
}
