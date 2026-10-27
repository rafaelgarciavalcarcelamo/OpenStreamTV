package dev.openstream.tv.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.openstream.tv.R
import dev.openstream.tv.data.BufferPreset
import dev.openstream.tv.data.ChannelStabilityStore
import dev.openstream.tv.data.DecoderPreference
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.ResizeMode
import dev.openstream.tv.data.StartupMode

class SettingsActivity : ComponentActivity() {
    private lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        preferences = Preferences(this)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(getColor(R.color.ink)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(24), dp(36), dp(36))
        }
        root.addView(label("Ajustes de la tele", 30f), rowParams(64))
        root.addView(label("Todo está preparado para una reproducción estable. Puedes dejar estos ajustes como están.", 16f, true), rowParams(54))

        root.addView(sectionTitle("Lo básico"))
        val launchOnBoot = Switch(this).apply {
            text = "Abrir la tele al encender el aparato"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.launchOnBoot; isFocusable = true
        }
        root.addView(launchOnBoot, rowParams(58))
        root.addView(label("En algunos aparatos Android puede pedir que permitas el inicio automático.", 14f, true), rowParams(42))
        val rememberLastChannel = Switch(this).apply {
            text = "Recordar el último canal reproducido"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.rememberLastChannel; isFocusable = true
        }
        root.addView(rememberLastChannel, rowParams(58))
        val startupModes = StartupMode.entries
        val startupMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, startupModes.map(StartupMode::label))
            setSelection(startupModes.indexOf(preferences.startupMode)); isFocusable = true
        }
        root.addView(settingRow("Al abrir la aplicación", startupMode))
        val confirmExit = Switch(this).apply {
            text = "Preguntar antes de salir"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.confirmExit; isFocusable = true
        }
        root.addView(confirmExit, rowParams(58))
        val refreshOnLaunch = Switch(this).apply {
            text = "Actualizar lista y guía al abrir (conservar copia si falla)"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.refreshSourcesOnLaunch; isFocusable = true
        }
        root.addView(refreshOnLaunch, rowParams(58))

        val firstAdvancedIndex = root.childCount

        root.addView(sectionTitle("Búfer"))
        val presets = BufferPreset.entries
        val spinner = Spinner(this).apply {
            isFocusable = true
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, presets.map(BufferPreset::label))
            setSelection(presets.indexOf(preferences.bufferPreset))
        }
        root.addView(settingRow("Perfil", spinner))

        val minControl = seekSetting("Búfer mínimo", 5, 90, preferences.customMinMs / 1000, "s")
        val maxControl = seekSetting("Búfer máximo", 10, 180, preferences.customMaxMs / 1000, "s")
        val playControl = seekSetting("Inicio de reproducción", 1, 10, (preferences.customPlayMs / 1000).coerceAtLeast(1), "s")
        val rebufferControl = seekSetting("Reanudar tras corte", 1, 15, (preferences.customRebufferMs / 1000).coerceAtLeast(1), "s")
        val liveControl = seekSetting("Retraso de directo", 0, 30, preferences.liveOffsetMs / 1000, "s")
        root.addView(minControl.container)
        root.addView(maxControl.container)
        root.addView(playControl.container)
        root.addView(rebufferControl.container)
        root.addView(liveControl.container)

        root.addView(sectionTitle("Vídeo, audio y subtítulos"))
        val qualityValues = listOf(0, 720, 1080, 2160)
        val quality = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Automática", "Hasta 720p", "Hasta 1080p", "Hasta 2160p"))
            setSelection(qualityValues.indexOf(preferences.maxVideoHeight).coerceAtLeast(0))
            isFocusable = true
        }
        root.addView(settingRow("Resolución máxima", quality))
        val resizeModes = ResizeMode.entries
        val resize = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, resizeModes.map(ResizeMode::label))
            setSelection(resizeModes.indexOf(preferences.resizeMode))
            isFocusable = true
        }
        root.addView(settingRow("Modo de imagen", resize))
        val decoderModes = DecoderPreference.entries
        val videoDecoder = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, decoderModes.map(DecoderPreference::label))
            setSelection(decoderModes.indexOf(preferences.videoDecoder))
            isFocusable = true
        }
        val audioDecoder = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, decoderModes.map(DecoderPreference::label))
            setSelection(decoderModes.indexOf(preferences.audioDecoder))
            isFocusable = true
        }
        root.addView(settingRow("Decodificación de vídeo", videoDecoder))
        root.addView(settingRow("Decodificación de audio", audioDecoder))
        val audioLanguage = EditText(this).apply { hint = "es, en, ca…"; setText(preferences.preferredAudioLanguage); setSingleLine(true) }
        val subtitleLanguage = EditText(this).apply { hint = "es, en, ca…"; setText(preferences.preferredSubtitleLanguage); setSingleLine(true) }
        root.addView(settingRow("Idioma de audio", audioLanguage))
        root.addView(settingRow("Idioma de subtítulos", subtitleLanguage))
        val decoderFallback = Switch(this).apply {
            text = "Probar otro decodificador si falla el preferido"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.decoderFallback; isFocusable = true
        }
        root.addView(decoderFallback, rowParams(58))
        val asynchronousQueueing = Switch(this).apply {
            text = "Cola asíncrona del códec en dispositivos antiguos"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.asynchronousCodecQueueing; isFocusable = true
        }
        root.addView(asynchronousQueueing, rowParams(58))
        val resetAdaptiveProfiles = action("Restablecer perfiles aprendidos por canal").apply {
            setOnClickListener {
                ChannelStabilityStore(this@SettingsActivity).clearAll()
                Toast.makeText(this@SettingsActivity, "Perfiles adaptativos restablecidos", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(resetAdaptiveProfiles, rowParams(54))

        root.addView(sectionTitle("Red y comportamiento"))
        val userAgent = EditText(this).apply {
            setText(preferences.userAgent)
            setSingleLine(true)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        root.addView(settingRow("User-Agent", userAgent))
        val connectTimeout = seekSetting("Tiempo de conexión", 5, 60, preferences.connectTimeoutMs / 1000, "s")
        val readTimeout = seekSetting("Tiempo de lectura", 5, 90, preferences.readTimeoutMs / 1000, "s")
        root.addView(connectTimeout.container)
        root.addView(readTimeout.container)
        val autoplay = Switch(this).apply {
            text = "Reproducir automáticamente"
            setTextColor(getColor(R.color.text_primary))
            isChecked = preferences.autoplay
            isFocusable = true
        }
        root.addView(autoplay, rowParams(58))
        val reconnect = Switch(this).apply {
            text = "Reconectar automáticamente tras un error"
            setTextColor(getColor(R.color.text_primary)); isChecked = preferences.autoReconnect; isFocusable = true
        }
        root.addView(reconnect, rowParams(58))
        val retryCount = seekSetting("Reintentos rápidos antes de esperar 60 s", 1, 10, preferences.retryCount, "")
        val retryDelay = seekSetting("Espera entre reintentos", 1, 10, preferences.retryDelayMs / 1000, "s")
        val controllerTimeout = seekSetting("Ocultar información del canal tras", 3, 12, preferences.controllerTimeoutMs / 1000, "s")
        root.addView(retryCount.container)
        root.addView(retryDelay.container)
        root.addView(controllerTimeout.container)

        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        while (root.childCount > firstAdvancedIndex) {
            val child = root.getChildAt(firstAdvancedIndex)
            root.removeViewAt(firstAdvancedIndex)
            advanced.addView(child)
        }
        val advancedToggle = action("Mostrar opciones avanzadas").apply {
            setOnClickListener {
                val show = advanced.visibility != android.view.View.VISIBLE
                advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                text = if (show) "Ocultar opciones avanzadas" else "Mostrar opciones avanzadas"
            }
        }
        root.addView(advancedToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(16) })
        root.addView(advanced)

        val save = action("Guardar cambios").apply {
            setOnClickListener {
                val minSeconds = minControl.value()
                val maxSeconds = maxControl.value()
                if (maxSeconds < minSeconds) {
                    Toast.makeText(this@SettingsActivity, "El máximo debe ser mayor o igual que el mínimo", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                preferences.bufferPreset = presets[spinner.selectedItemPosition]
                preferences.launchOnBoot = launchOnBoot.isChecked
                preferences.rememberLastChannel = rememberLastChannel.isChecked
                preferences.startupMode = startupModes[startupMode.selectedItemPosition]
                preferences.confirmExit = confirmExit.isChecked
                preferences.refreshSourcesOnLaunch = refreshOnLaunch.isChecked
                preferences.customMinMs = minSeconds * 1000
                preferences.customMaxMs = maxSeconds * 1000
                preferences.customPlayMs = playControl.value() * 1000
                preferences.customRebufferMs = rebufferControl.value() * 1000
                preferences.liveOffsetMs = liveControl.value() * 1000
                preferences.userAgent = userAgent.text.toString().trim().ifBlank { "OpenStreamTV/0.8" }
                preferences.autoplay = autoplay.isChecked
                preferences.connectTimeoutMs = connectTimeout.value() * 1000
                preferences.readTimeoutMs = readTimeout.value() * 1000
                preferences.maxVideoHeight = qualityValues[quality.selectedItemPosition]
                preferences.resizeMode = resizeModes[resize.selectedItemPosition]
                preferences.preferredAudioLanguage = audioLanguage.text.toString().trim()
                preferences.preferredSubtitleLanguage = subtitleLanguage.text.toString().trim()
                preferences.decoderFallback = decoderFallback.isChecked
                preferences.videoDecoder = decoderModes[videoDecoder.selectedItemPosition]
                preferences.audioDecoder = decoderModes[audioDecoder.selectedItemPosition]
                preferences.asynchronousCodecQueueing = asynchronousQueueing.isChecked
                preferences.autoReconnect = reconnect.isChecked
                preferences.retryCount = retryCount.value()
                preferences.retryDelayMs = retryDelay.value() * 1000
                preferences.controllerTimeoutMs = controllerTimeout.value() * 1000
                Toast.makeText(this@SettingsActivity, "Ajustes guardados", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(save, LinearLayout.LayoutParams(dp(250), dp(54)).apply { gravity = Gravity.END; topMargin = dp(20) })
        val restoreStableDefaults = action("Aplicar ajustes recomendados de máxima estabilidad").apply {
            setOnClickListener {
                preferences.applyStableDefaults(force = true)
                Toast.makeText(this@SettingsActivity, "Perfil estable aplicado: 15 s, 1080p y reconexión continua", Toast.LENGTH_LONG).show()
                recreate()
            }
        }
        root.addView(restoreStableDefaults, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }

    private data class SeekSetting(val container: LinearLayout, val seekBar: SeekBar, val min: Int) {
        fun value() = min + seekBar.progress
    }

    private fun seekSetting(name: String, min: Int, max: Int, initial: Int, suffix: String): SeekSetting {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val value = label("", 14f, true)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = initial.coerceIn(min, max) - min
            isFocusable = true
        }
        fun update() { value.text = "$name: ${min + seek.progress} $suffix" }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        update()
        row.addView(value, rowParams(28))
        row.addView(seek, rowParams(46))
        return SeekSetting(row, seek, min)
    }

    private fun settingRow(title: String, control: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(label(title, 16f), LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        layoutParams = rowParams(64)
    }

    private fun rowParams(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
}
