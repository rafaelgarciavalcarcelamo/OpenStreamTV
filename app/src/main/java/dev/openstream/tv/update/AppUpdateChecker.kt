package dev.openstream.tv.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import dev.openstream.tv.data.Preferences
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

object AppUpdateManifest {
    fun parse(raw: String): AppUpdateInfo {
        val json = JSONObject(raw)
        return AppUpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName").trim(),
            apkUrl = json.getString("apkUrl").trim(),
            notes = json.optString("notes").trim(),
        ).also {
            require(it.versionCode > 0) { "versionCode inválido" }
            require(it.versionName.isNotBlank()) { "versionName vacío" }
            require(URI(it.apkUrl).scheme == "https") { "La descarga debe usar HTTPS" }
        }
    }
}

class AppUpdateChecker(
    private val activity: Activity,
    private val preferences: Preferences,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun check(force: Boolean = false) {
        if (!force && !preferences.automaticUpdateChecks) return
        val now = System.currentTimeMillis()
        if (!force && now - preferences.lastSuccessfulUpdateCheckMs < CHECK_INTERVAL_MS) return

        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "OpenStreamTV/$currentVersionName")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (force) showToast("No se pudo comprobar la actualización")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (force) showToast("GitHub respondió con el código ${it.code}")
                        return
                    }
                    val info = runCatching { AppUpdateManifest.parse(it.body?.string().orEmpty()) }
                        .getOrElse {
                            if (force) showToast("La información de actualización no es válida")
                            return
                        }
                    preferences.lastSuccessfulUpdateCheckMs = System.currentTimeMillis()
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (info.versionCode > currentVersionCode) showUpdateDialog(info)
                        else if (force) Toast.makeText(activity, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        val details = buildString {
            append("Está disponible OpenStream TV ${info.versionName}.")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
        }
        AlertDialog.Builder(activity)
            .setTitle("Nueva versión disponible")
            .setMessage(details)
            .setPositiveButton("Actualizar") { _, _ -> openDownload(info.apkUrl) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun openDownload(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No hay una aplicación disponible para abrir la descarga", Toast.LENGTH_LONG).show()
        }
    }

    private fun showToast(message: String) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private val currentVersionName: String
        get() = packageInfo().versionName.orEmpty()

    @Suppress("DEPRECATION")
    private val currentVersionCode: Int
        get() = if (Build.VERSION.SDK_INT >= 28) packageInfo().longVersionCode.toInt() else packageInfo().versionCode

    private fun packageInfo() = activity.packageManager.getPackageInfo(activity.packageName, 0)

    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/rafaelgarciavalcarcelamo/OpenStreamTV-Updates/main/update.json"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
