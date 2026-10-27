package dev.openstream.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.TextClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.CredentialVault
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.Program
import dev.openstream.tv.data.StartupMode

class MainActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var preferences: Preferences
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var watchButton: Button
    private lateinit var heroTitle: TextView
    private lateinit var watchSubtitle: TextView
    private lateinit var searchButton: Button
    private lateinit var channelContext: TextView
    private lateinit var emptyState: TextView
    private lateinit var emptyAction: Button
    private lateinit var emptyPanel: LinearLayout
    private lateinit var channelList: RecyclerView
    private lateinit var progress: ProgressBar

    private var playlist: Playlist? = null
    private var programs = emptyList<Program>()
    private var selectedGroup = "Todos"
    private var query = ""
    private var guideReleasedForMemory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        repository = AppRepository(this)
        preferences = Preferences(this)
        preferences.applyStableDefaults()
        preferences.applyTvExperienceDefaults()
        buildUi()
        configureBackHandling()
        playlist = repository.loadSavedPlaylist()
        repository.loadSavedEpgAsync { loaded -> runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                programs = loaded
                refresh()
            }
        } }
        if (preferences.startupMode == StartupMode.FAVORITOS) selectedGroup = "Favoritos"
        refresh()
        if (playlist == null) {
            showSourcesDialog()
        } else if (savedInstanceState == null && preferences.startupMode == StartupMode.ULTIMO_CANAL) {
            playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }?.let(::playChannel)
        }
        if (preferences.refreshSourcesOnLaunch) refreshSourcesSilently()
    }

    override fun onStart() {
        super.onStart()
        refreshWatchButton()
        if (::channelAdapter.isInitialized) refreshChannels()
        if (guideReleasedForMemory) {
            guideReleasedForMemory = false
            repository.loadSavedEpgAsync { restored -> runOnUiThread { programs = restored; refresh() } }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && programs.isNotEmpty()) {
            programs = emptyList()
            refreshChannels()
            guideReleasedForMemory = true
        }
    }

    private fun buildUi() {
        val compact = resources.configuration.screenHeightDp <= 600
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(28), dp(if (compact) 10 else 14), dp(28), dp(if (compact) 10 else 16))
            clipChildren = false
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val brandName = label("●  OPENSTREAM TV", 19f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .06f
            setTextColor(getColor(R.color.accent))
        }
        brand.addView(brandName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 24 else 28)))
        brand.addView(label("Tu tele, a tu manera", 13f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 16 else 20)))
        toolbar.addView(brand, LinearLayout.LayoutParams(0, dp(if (compact) 42 else 52), 1f))
        toolbar.addView(TextClock(this).apply {
            format24Hour = "EEE d MMM  ·  HH:mm"
            format12Hour = "EEE d MMM  ·  h:mm a"
            textSize = 16f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(250), dp(if (compact) 40 else 46)))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 44 else 60)))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.hero_card)
            setPadding(dp(30), dp(if (compact) 10 else 16), dp(28), dp(if (compact) 10 else 16))
            clipChildren = false
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heroCopy.addView(label("EN DIRECTO  ·  SIN COMPLICACIONES", 12f).apply {
            setTextColor(getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 18 else 24)))
        heroTitle = label("Tu televisión", if (compact) 28f else 34f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
        heroCopy.addView(heroTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 38 else 48)))
        watchSubtitle = label("Elige un canal y disfruta", if (compact) 15f else 17f, secondary = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        heroCopy.addView(watchSubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 20 else 28)))
        watchButton = action("▶  Ver la tele").apply {
            textSize = if (compact) 17f else 19f
            setOnClickListener {
                val channel = playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }
                    ?: playlist?.channels?.firstOrNull()
                if (channel != null) playChannel(channel) else showSourcesDialog()
            }
        }
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(20) })
        hero.addView(watchButton, LinearLayout.LayoutParams(dp(if (compact) 212 else 246), dp(if (compact) 52 else 62)))
        root.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 96 else 132)).apply { bottomMargin = dp(if (compact) 8 else 10) })

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(8), dp(if (compact) 4 else 5), dp(8), dp(if (compact) 4 else 5))
            clipChildren = false
        }
        val favoritesButton = action("☆  Favoritos").apply {
            id = View.generateViewId()
            setOnClickListener {
                selectedGroup = "Favoritos"
                query = ""
                searchButton.text = "Buscar"
                refresh()
                channelList.scrollToPosition(0)
                channelList.post {
                    if (channelAdapter.itemCount > 0) channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    else this.requestFocus()
                }
            }
        }
        searchButton = action("Buscar").apply { setOnClickListener { showSearchDialog() } }
        val sources = action("Mis listas").apply { setOnClickListener { showSourcesDialog() } }
        val settings = action("Ajustes").apply { setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }
        for (button in listOf(favoritesButton, searchButton, sources, settings)) {
            dock.addView(button, LinearLayout.LayoutParams(0, dp(if (compact) 40 else 46), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        watchButton.nextFocusDownId = favoritesButton.id
        root.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 48 else 58)).apply { bottomMargin = dp(if (compact) 6 else 7) })

        status = label("", 14f, secondary = true).apply { letterSpacing = .05f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = ProgressBar.GONE }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 18 else 24)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 2 else 3)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(if (compact) 6 else 10), 0, 0); clipChildren = false }
        val groupsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(12), dp(if (compact) 6 else 8), dp(12), dp(if (compact) 6 else 10))
            clipChildren = false
        }
        groupsPane.addView(label("Explorar", 20f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(7), 0, 0, 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 32 else 40)))
        val channelsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(12), dp(if (compact) 6 else 8), dp(12), dp(if (compact) 6 else 10))
            clipChildren = false
        }
        val channelHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        channelHeading.addView(label("Canales", 20f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(7), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(if (compact) 32 else 40), 1f))
        channelContext = label("", 14f, secondary = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        channelHeading.addView(channelContext, LinearLayout.LayoutParams(dp(220), dp(if (compact) 32 else 40)))
        channelsPane.addView(channelHeading)
        groupAdapter = GroupAdapter {
            selectedGroup = it
            refreshChannels()
            channelList.scrollToPosition(0)
            channelList.post {
                if (channelAdapter.itemCount > 0) channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                else if (emptyAction.visibility == View.VISIBLE) emptyAction.requestFocus()
                else searchButton.requestFocus()
            }
        }
        val groupsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = groupAdapter
            clipToPadding = false
            clipChildren = false
        }
        groupsPane.addView(groupsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        channelAdapter = ChannelAdapter(::playChannel, ::toggleFavorite)
        channelList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            clipToPadding = false
            clipChildren = false
        }
        channelsPane.addView(channelList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        emptyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(20), 0, dp(20), 0)
        }
        emptyState = label("No hay canales en esta categoría", 18f, secondary = true).apply {
            gravity = Gravity.CENTER
        }
        emptyPanel.addView(emptyState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 42 else 58)))
        emptyAction = action("Añadir una lista").apply {
            visibility = View.GONE
            setOnClickListener { showSourcesDialog() }
        }
        emptyPanel.addView(emptyAction, LinearLayout.LayoutParams(dp(220), dp(if (compact) 42 else 50)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        channelsPane.addView(emptyPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(groupsPane, LinearLayout.LayoutParams(dp(238), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(14) })
        content.addView(channelsPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(label("FLECHAS  Moverse     OK  Ver canal     MANTÉN OK  Favorito", 13f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 20 else 28)).apply { topMargin = dp(if (compact) 4 else 7) })
        setContentView(root)
        watchButton.requestFocus()
    }

    private fun refresh() {
        val channels = playlist?.channels.orEmpty()
        val groups = buildList {
            add("Todos" to channels.size)
            add("Favoritos" to channels.count { it.id in preferences.favorites })
            addAll(channels.groupingBy(Channel::group).eachCount().toList().sortedBy { it.first.lowercase() })
        }
        if (groups.none { it.first == selectedGroup }) selectedGroup = "Todos"
        groupAdapter.submit(groups, selectedGroup)
        status.text = if (channels.isEmpty()) "COMIENZA AÑADIENDO UNA LISTA" else "${channels.size} CANALES DISPONIBLES"
        refreshWatchButton()
        refreshChannels()
    }

    private fun refreshWatchButton() {
        if (!::watchButton.isInitialized) return
        val last = playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }
        heroTitle.text = if (last != null) "Sigue viendo" else "Tu televisión"
        watchButton.text = if (last != null) "▶  Seguir viendo" else "▶  Ver la tele"
        watchSubtitle.text = when {
            last != null -> "Último canal: ${last.name}"
            playlist?.channels?.isNotEmpty() == true -> "Elige un canal o empieza aquí"
            else -> "Añade tu lista y empieza a ver canales"
        }
    }

    private fun refreshChannels() {
        val all = playlist?.channels.orEmpty()
        val byGroup = when (selectedGroup) {
            "Todos" -> all
            "Favoritos" -> all.filter { it.id in preferences.favorites }
            else -> all.filter { it.group == selectedGroup }
        }
        val filtered = if (query.isBlank()) byGroup else byGroup.filter {
            it.name.contains(query, true) || it.group.contains(query, true)
        }
        val numbers = all.mapIndexed { position, channel -> channel.id to position + 1 }.toMap()
        channelAdapter.submit(filtered, currentPrograms(), preferences.favorites, numbers, preferences.lastChannelId)
        channelContext.text = if (query.isNotBlank()) "${filtered.size} resultados" else "${filtered.size} · $selectedGroup"
        emptyState.text = when {
            all.isEmpty() -> "Todavía no hay canales. Añade tu lista para empezar."
            selectedGroup == "Favoritos" -> "Aún no hay favoritos. Mantén OK sobre un canal para guardarlo."
            query.isNotBlank() -> "No encontramos ese canal. Prueba otra búsqueda."
            else -> "No hay canales aquí. Prueba otra categoría."
        }
        channelList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyPanel.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyAction.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun currentPrograms(): Map<String, Program> {
        val now = System.currentTimeMillis()
        return programs.asSequence().filter { now in it.startMillis until it.endMillis }.associateBy(Program::channelId)
    }

    private fun playChannel(channel: Channel) {
        if (preferences.rememberLastChannel) preferences.lastChannelId = channel.id
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id))
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del canal"
            setText(query)
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Buscar un canal")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Mostrar todos") { _, _ ->
                query = ""
                searchButton.text = "Buscar"
                refreshChannels()
            }
            .setPositiveButton("Buscar") { _, _ ->
                query = input.text.toString().trim()
                selectedGroup = "Todos"
                searchButton.text = if (query.isBlank()) "Buscar" else "Buscar: $query"
                refresh()
                channelList.post { channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
            .show()
    }

    private fun refreshSourcesSilently() {
        repository.refreshActivePlaylist { result -> runOnUiThread {
            result.onSuccess { playlist = it; refresh() }
        } }
        if (preferences.epgUrl.isNotBlank()) {
            repository.importEpg(preferences.epgUrl) { result -> runOnUiThread {
                result.onSuccess { programs = it; refresh() }
            } }
        }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!preferences.confirmExit) {
                    finishAffinity()
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Salir de OpenStream TV")
                    .setMessage("¿Quieres cerrar la aplicación?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Salir") { _, _ -> finishAffinity() }
                    .show()
            }
        })
    }

    private fun toggleFavorite(channel: Channel) {
        val favorites = preferences.favorites.toMutableSet()
        val added = if (channel.id in favorites) { favorites.remove(channel.id); false } else { favorites.add(channel.id); true }
        preferences.favorites = favorites
        Toast.makeText(this, if (added) "Añadido a favoritos" else "Quitado de favoritos", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun showSourcesDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val playlistInput = EditText(this).apply { hint = "URL de lista M3U"; setText(preferences.playlistUrl); setSingleLine(true) }
        val epgInput = EditText(this).apply { hint = "URL XMLTV (opcional)"; setText(preferences.epgUrl); setSingleLine(true) }
        val xtream = action("Añadir con Xtream Login").apply {
            setOnClickListener { showXtreamDialog() }
        }
        container.addView(playlistInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(epgInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(xtream, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        AlertDialog.Builder(this)
            .setTitle("Tus listas de canales")
            .setMessage("Añade la lista de tu proveedor de televisión.")
            .setView(container)
            .setNeutralButton("Abrir archivo") { _, _ -> openPlaylistFile() }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Actualizar") { _, _ ->
                val listUrl = playlistInput.text.toString().trim()
                val guideUrl = epgInput.text.toString().trim()
                preferences.playlistUrl = listUrl
                preferences.epgUrl = guideUrl
                if (listUrl.isNotBlank()) importPlaylist(listUrl)
                if (guideUrl.isNotBlank()) importEpg(guideUrl)
            }
            .show()
    }

    private fun showXtreamDialog() {
        val vault = CredentialVault(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val server = EditText(this).apply { hint = "Servidor (https://proveedor.example:puerto)"; setText(preferences.xtreamServer); setSingleLine(true) }
        val username = EditText(this).apply { hint = "Usuario"; setText(preferences.xtreamUsername); setSingleLine(true) }
        val password = EditText(this).apply {
            hint = "Contraseña"
            setText(vault.loadXtreamPassword())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(server, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(username, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        AlertDialog.Builder(this)
            .setTitle("Xtream Codes")
            .setMessage("OpenStream TV importará únicamente los canales en directo de tu cuenta.")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                importXtream(server.text.toString().trim(), username.text.toString().trim(), password.text.toString())
            }
            .show()
    }

    private fun openPlaylistFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQUEST_PLAYLIST)
    }

    @Deprecated("Legacy result API retained for Android 6 TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PLAYLIST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { importPlaylist(it) }
        }
    }

    private fun importPlaylist(source: String) {
        setLoading(true, "Actualizando lista…")
        repository.importPlaylist(source) { result -> runOnUiThread { handlePlaylistResult(result) } }
    }

    private fun importPlaylist(uri: Uri) {
        setLoading(true, "Importando archivo…")
        repository.importPlaylist(uri) { result -> runOnUiThread { handlePlaylistResult(result) } }
    }

    private fun handlePlaylistResult(result: Result<Playlist>) {
        setLoading(false)
        result.onSuccess { playlist = it; refresh(); Toast.makeText(this, "Lista actualizada", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "No se pudo cargar: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importEpg(source: String) {
        setLoading(true, "Actualizando guía…")
        repository.importEpg(source) { result -> runOnUiThread {
            setLoading(false)
            result.onSuccess { programs = it; refresh(); Toast.makeText(this, "Guía actualizada", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "No se pudo cargar la guía: ${it.message}", Toast.LENGTH_LONG).show() }
        } }
    }

    private fun importXtream(server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Completa servidor, usuario y contraseña", Toast.LENGTH_LONG).show()
            return
        }
        setLoading(true, "Conectando con Xtream…")
        repository.importXtream(server, username, password) { result -> runOnUiThread {
            setLoading(false)
            result.onSuccess {
                preferences.xtreamServer = server
                preferences.xtreamUsername = username
                CredentialVault(this).saveXtreamPassword(password)
                playlist = it
                selectedGroup = "Todos"
                refresh()
                Toast.makeText(this, "Xtream: ${it.channels.size} canales importados", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this, "No se pudo conectar con Xtream: ${it.message}", Toast.LENGTH_LONG).show()
            }
        } }
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        progress.visibility = if (loading) ProgressBar.VISIBLE else ProgressBar.GONE
        if (loading) status.text = message else refresh()
    }

    override fun onDestroy() { repository.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_STARTED_FROM_BOOT = "started_from_boot"
        private const val REQUEST_PLAYLIST = 40
    }
}
