package dev.openstream.tv.mobile

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.GuideData
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.PlaylistSource
import dev.openstream.tv.data.PlaylistSourceType
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.Program
import dev.openstream.tv.data.StartupMode
import dev.openstream.tv.ui.ChannelArtworkLoader
import dev.openstream.tv.ui.DiagnosticsActivity
import dev.openstream.tv.ui.PlayerActivity
import dev.openstream.tv.ui.SettingsActivity
import dev.openstream.tv.ui.action
import dev.openstream.tv.ui.applyTouchInsets
import dev.openstream.tv.ui.dp
import dev.openstream.tv.ui.label
import java.util.concurrent.Executors

class MobileActivity : ComponentActivity() {
    private enum class Tab(val title: String) { CHANNELS("Canales"), FAVORITES("Favoritos"), LISTS("Mis listas") }

    private lateinit var repository: AppRepository
    private lateinit var preferences: Preferences
    private val reader = Executors.newSingleThreadExecutor()
    private lateinit var navigation: Map<Tab, Button>
    private lateinit var catalog: LinearLayout
    private lateinit var sourceScroll: ScrollView
    private lateinit var sourcesBody: LinearLayout
    private lateinit var categoryButton: Button
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var channelCards: ChannelCards
    private var playlist: Playlist? = null
    private var programs = emptyMap<String, Program>()
    private var tab = Tab.CHANNELS
    private var group: String? = null
    private var query = ""
    private var busy = false
    private var loaded = false
    private var guideRequest = 0
    private var needsStartupRefresh = false
    private var needsStartupPlayback = false

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) changeSource<Playlist>("Lista importada") { repository.importPlaylist(uri, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(applicationContext)
        preferences = Preferences(this)
        preferences.applyStableDefaults()
        tab = savedInstanceState?.getString("tab")?.let(Tab::valueOf)
            ?: if (preferences.startupMode == StartupMode.FAVORITOS) Tab.FAVORITES else Tab.CHANNELS
        group = savedInstanceState?.getString("group")
        query = savedInstanceState?.getString("query").orEmpty()
        needsStartupRefresh = savedInstanceState == null && preferences.refreshSourcesOnLaunch
        needsStartupPlayback = savedInstanceState == null && preferences.startupMode == StartupMode.ULTIMO_CANAL
        buildUi()
        loadPlaylist {
            if (needsStartupPlayback) {
                needsStartupPlayback = false
                visibleChannels().firstOrNull { it.id == preferences.lastChannelId }?.let(::play)
            }
            val refresh = needsStartupRefresh
            needsStartupRefresh = false
            if (refresh) {
                repository.refreshActivePlaylist { result -> onScreen {
                    result.onSuccess {
                        if (it.sourceId == playlist?.sourceId) {
                            playlist = it
                            render()
                        }
                    }
                } }
            }
            loadGuide(refresh)
        }
    }

    override fun onResume() {
        super.onResume()
        if (loaded) {
            render()
            loadGuide(refresh = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab.name)
        outState.putString("group", group)
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        reader.shutdownNow()
        repository.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = column().apply {
            setBackgroundColor(getColor(R.color.ink))
            applyTouchInsets()
        }
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
        }
        heading.addView(label("OpenStream", 24f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        heading.addView(action("Ajustes").apply {
            setOnClickListener { startActivity(Intent(this@MobileActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(heading, row(56))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, row(3))

        catalog = column().apply { setPadding(dp(12), dp(6), dp(12), 0) }
        val search = field("Buscar canal", query).apply {
            maxLines = 1
            setPadding(dp(14), 0, dp(14), 0)
            background = getDrawable(R.drawable.panel_surface)
            doAfterTextChanged {
                query = it?.toString().orEmpty()
                renderCatalog()
            }
        }
        catalog.addView(search, row(52))
        categoryButton = action("Todas las categorías").apply { setOnClickListener { chooseCategory() } }
        catalog.addView(categoryButton, row(48).apply { topMargin = dp(8); bottomMargin = dp(6) })

        val cards = RecyclerView(this).apply {
            val grid = GridLayoutManager(this@MobileActivity, 1)
            layoutManager = grid
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val columns = ((right - left) / dp(296)).coerceIn(1, 4)
                if (grid.spanCount != columns) grid.spanCount = columns
            }
            channelCards = ChannelCards(::play, ::showChannelOptions)
            adapter = channelCards
        }
        empty = label("", 16f, secondary = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
        }
        val results = FrameLayout(this).apply {
            addView(cards, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }
        catalog.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        sourcesBody = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }
        sourceScroll = ScrollView(this).apply { addView(sourcesBody) }
        val content = FrameLayout(this).apply {
            addView(catalog, FrameLayout.LayoutParams(-1, -1))
            addView(sourceScroll, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(getColor(R.color.panel))
        }
        navigation = Tab.entries.associateWith { destination ->
            action(destination.title).apply {
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    val keyboard = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    keyboard?.hideSoftInputFromWindow(search.windowToken, 0)
                    search.clearFocus()
                    tab = destination
                    render()
                }
                bottom.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
        }
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        root.isFocusableInTouchMode = true
        setContentView(root)
        root.requestFocus()
        render()
    }

    private fun loadPlaylist(after: () -> Unit = {}) {
        reader.execute {
            val cached = repository.loadSavedPlaylist()
            onScreen {
                if (cached?.sourceId != playlist?.sourceId && loaded) group = null
                playlist = cached
                loaded = true
                render()
                after()
            }
        }
    }

    private fun loadGuide(refresh: Boolean) {
        val request = ++guideRequest
        if (!preferences.epgEnabled) {
            programs = emptyMap()
            renderCatalog()
            return
        }
        repository.loadSavedGuideAsync { guide -> applyGuide(request, guide) }
        if (refresh) {
            val url = preferences.epgUrl.ifBlank { repository.inferredEpgUrl().orEmpty() }
            if (url.isNotBlank()) repository.importEpg(url) { result -> result.onSuccess { applyGuide(request, it) } }
        }
    }

    private fun applyGuide(request: Int, guide: GuideData) {
        val now = System.currentTimeMillis()
        val current = guide.programs.asSequence().filter { now in it.startMillis until it.endMillis }.associateBy(Program::channelId)
        onScreen {
            if (request == guideRequest && preferences.epgEnabled) {
                programs = current
                renderCatalog()
            }
        }
    }

    private fun visibleChannels(): List<Channel> = preferences.visibleChannels(playlist)

    private fun render() {
        if (!::navigation.isInitialized) return
        val browsing = tab != Tab.LISTS
        catalog.visibility = if (browsing) View.VISIBLE else View.GONE
        sourceScroll.visibility = if (browsing) View.GONE else View.VISIBLE
        progress.visibility = if (!loaded || busy) View.VISIBLE else View.INVISIBLE
        for ((destination, button) in navigation) {
            button.isSelected = tab == destination
            button.backgroundTintList = ColorStateList.valueOf(getColor(if (button.isSelected) R.color.accent_dark else R.color.panel))
        }
        if (browsing) renderCatalog() else renderSources()
    }

    private fun renderCatalog() {
        if (!::channelCards.isInitialized || !::empty.isInitialized) return
        val all = visibleChannels()
        val favorites = preferences.favorites
        val selected = group
        val channels = all.filter { channel ->
            (tab != Tab.FAVORITES || channel.id in favorites) &&
                (selected == null || channel.group == selected) &&
                (query.isBlank() || channel.name.contains(query.trim(), ignoreCase = true))
        }
        categoryButton.text = selected ?: "Todas las categorías"
        categoryButton.visibility = if (playlist == null) View.GONE else View.VISIBLE
        channelCards.submit(channels, favorites, programs)
        empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            !loaded -> "Cargando canales…"
            playlist == null -> "Abre Mis listas y añade tu primera fuente."
            all.isEmpty() -> "No hay canales visibles. Revisa las categorías ocultas."
            tab == Tab.FAVORITES && all.none { it.id in favorites } -> "Toca ⋮ junto a un canal para guardarlo en Favoritos."
            else -> "No hay canales que coincidan."
        }
    }

    private fun chooseCategory() {
        val groups = visibleChannels().map(Channel::group).distinct().sortedBy(String::lowercase)
        val options = listOf("Todas las categorías") + groups
        AlertDialog.Builder(this).setTitle("Categorías")
            .setItems(options.toTypedArray()) { _, index ->
                group = groups.getOrNull(index - 1)
                renderCatalog()
            }
            .setNeutralButton("Gestionar") { _, _ -> manageCategories() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun manageCategories() {
        val active = playlist ?: return
        val id = active.sourceId ?: return
        val hidden = preferences.hiddenGroups(id).toMutableSet()
        val groups = (active.channels.map(Channel::group) + hidden).distinct().sortedBy(String::lowercase)
        AlertDialog.Builder(this).setTitle("Categorías visibles")
            .setMultiChoiceItems(groups.toTypedArray(), BooleanArray(groups.size) { groups[it] !in hidden }) { _, index, visible ->
                if (visible) hidden.remove(groups[index]) else hidden.add(groups[index])
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                preferences.setHiddenGroups(id, hidden)
                if (group in hidden) group = null
                renderCatalog()
            }.show()
    }

    private fun showChannelOptions(channel: Channel) {
        val favorites = preferences.favorites
        val favorite = channel.id in favorites
        AlertDialog.Builder(this).setTitle(channel.name)
            .setItems(arrayOf(if (favorite) "Quitar de favoritos" else "Añadir a favoritos", "Ocultar canal")) { _, index ->
                if (index == 0) preferences.favorites = if (favorite) favorites - channel.id else favorites + channel.id
                else preferences.hiddenChannelIds = preferences.hiddenChannelIds + channel.id
                renderCatalog()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun play(channel: Channel) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id))
    }

    private fun renderSources() {
        sourcesBody.removeAllViews()
        val sources = repository.listPlaylistSources()
        val activeId = repository.activePlaylistSource()?.id
        val hidden = repository.hiddenPlaylistSourceIds()
        sourcesBody.addView(label("Mis listas", 24f), row(48))
        sources.filterNot { it.id in hidden }.forEach { source ->
            addSourceAction("${source.name}${if (source.id == activeId) " · Activa" else ""}") {
                showSourceOptions(source, source.id == activeId, false)
            }
        }
        if (hidden.isNotEmpty()) addSourceAction("Listas desactivadas") {
            val disabled = sources.filter { it.id in hidden }
            AlertDialog.Builder(this).setTitle("Listas desactivadas")
                .setItems(disabled.map(PlaylistSource::name).toTypedArray()) { _, index -> showSourceOptions(disabled[index], false, true) }
                .setNegativeButton("Cerrar", null).show()
        }
        addSourceAction("Añadir lista") { showAddOptions() }
        val hiddenChannels = preferences.hiddenChannelIds
        val restorable = playlist?.channels.orEmpty().filter { it.id in hiddenChannels }
        if (restorable.isNotEmpty()) addSourceAction("Canales ocultos") {
            AlertDialog.Builder(this).setTitle("Toca un canal para recuperarlo")
                .setItems(restorable.map(Channel::name).toTypedArray()) { _, index ->
                    preferences.hiddenChannelIds = preferences.hiddenChannelIds - restorable[index].id
                    render()
                }.setNegativeButton("Cerrar", null).show()
        }
        addSourceAction("Configurar guía XMLTV") { showGuideDialog() }
        addSourceAction("Diagnóstico") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
    }

    private fun showSourceOptions(source: PlaylistSource, active: Boolean, disabled: Boolean) {
        val options = buildList<Pair<String, () -> Unit>> {
            if (disabled) add("Reactivar lista" to {
                changeSource<Unit>("Lista reactivada") { repository.setPlaylistSourceEnabled(source.id, true, it) }
            }) else {
                if (!active) add("Usar esta lista" to {
                    changeSource<Playlist>("Lista activa") { repository.activatePlaylistSource(source.id, it) }
                })
                if (source.type != PlaylistSourceType.FILE) {
                    add("Actualizar lista" to {
                        changeSource<Playlist>("Lista actualizada") { repository.refreshPlaylistSource(source.id, it) }
                    })
                    add("Editar lista" to { showSourceForm(source.type, source) })
                }
                add("Desactivar lista" to {
                    changeSource<Unit>("Lista desactivada") { repository.setPlaylistSourceEnabled(source.id, false, it) }
                })
            }
            add("Eliminar lista" to {
                AlertDialog.Builder(this@MobileActivity).setTitle("¿Eliminar ${source.name}?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Eliminar") { _, _ ->
                        changeSource<Playlist?>("Lista eliminada") { repository.deletePlaylistSource(source.id, it) }
                    }.show()
            })
        }
        AlertDialog.Builder(this).setTitle(source.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, index -> options[index].second() }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun showAddOptions() {
        AlertDialog.Builder(this).setTitle("Añadir lista")
            .setItems(arrayOf("Enlace M3U", "Cuenta Xtream", "Archivo M3U")) { _, index ->
                when (index) {
                    0 -> showSourceForm(PlaylistSourceType.M3U)
                    1 -> showSourceForm(PlaylistSourceType.XTREAM)
                    2 -> openFile.launch(arrayOf("*/*"))
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showSourceForm(type: PlaylistSourceType, existing: PlaylistSource? = null) {
        val xtream = type == PlaylistSourceType.XTREAM
        val name = field("Nombre", existing?.name.orEmpty())
        val endpoint = field(if (xtream) "Servidor (https://…)" else "Enlace M3U", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("Usuario")
        val password = field("Contraseña", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val form = column().apply {
            setPadding(dp(22), 0, dp(22), dp(8))
            addView(name, row(54))
            addView(endpoint, row(54))
            if (xtream) {
                addView(user, row(54))
                addView(password, row(54))
            }
            if (existing != null) addView(label("Deja en blanco los datos de acceso que quieras conservar.", 14f, true), row(-2))
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Añadir lista" else "Editar lista")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = name.text.toString().trim().ifEmpty { if (xtream) "Xtream" else "Mi lista" }
            val url = endpoint.text.toString().trim()
            val username = user.text.toString().trim()
            val pass = password.text.toString()
            if ((url.isNotBlank() || existing == null) && !isWebUrl(url)) {
                endpoint.error = "Introduce un enlace HTTP o HTTPS"
                return@setOnClickListener
            }
            if (xtream && existing == null && (username.isBlank() || pass.isBlank())) {
                user.error = "Completa el usuario y la contraseña"
                return@setOnClickListener
            }
            dialog.dismiss()
            changeSource<Playlist>("Lista guardada") { done ->
                when {
                    existing != null -> repository.updatePlaylistSource(existing.id, title, url, username, pass, done)
                    xtream -> repository.importXtream(title, url, username, pass, done)
                    else -> repository.importPlaylist(title, url, done)
                }
            }
        }
    }

    private fun showGuideDialog() {
        val input = field("Enlace XMLTV", preferences.epgUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val dialog = AlertDialog.Builder(this).setTitle("Guía XMLTV").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isNotBlank() && !isWebUrl(url)) {
                input.error = "Introduce un enlace HTTP o HTTPS"
                return@setOnClickListener
            }
            preferences.epgUrl = url
            dialog.dismiss()
            loadGuide(refresh = url.isNotBlank())
        }
    }

    private fun <T> changeSource(message: String, operation: ((Result<T>) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        render()
        operation { result -> onScreen {
            loadPlaylist {
                busy = false
                render()
                toast(if (result.isSuccess) message else "No se pudo completar la operación. Revisa la fuente.")
            }
        } }
    }

    private fun addSourceAction(text: String, click: () -> Unit) {
        sourcesBody.addView(action(text).apply {
            isEnabled = !busy
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { click() }
        }, row(54).apply { bottomMargin = dp(8) })
    }

    private fun isWebUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun field(hintText: String, value: String = "", input: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            hint = hintText
            inputType = input
            setSingleLine(true)
            setText(value)
        }

    private fun onScreen(task: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) task() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(height: Int) = LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height))
}

private class ChannelCards(
    private val play: (Channel) -> Unit,
    private val options: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelCards.Card>() {
    private var channels = emptyList<Channel>()
    private var favorites = emptySet<String>()
    private var programs = emptyMap<String, Program>()

    fun submit(items: List<Channel>, favoriteIds: Set<String>, guide: Map<String, Program>) {
        channels = items
        favorites = favoriteIds
        programs = guide
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
        val context = parent.context
        val card = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.focus_card)
            isFocusable = true
            setPadding(context.dp(10), 0, context.dp(2), 0)
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(76)).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            }
        }
        val artwork = FrameLayout(context).apply { background = context.getDrawable(R.drawable.number_tile) }
        val fallback = context.label("TV", 13f, true).apply { gravity = Gravity.CENTER }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = context.dp(4)
            setPadding(inset, inset, inset, inset)
        }
        artwork.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        artwork.addView(logo, FrameLayout.LayoutParams(-1, -1))
        card.addView(artwork, LinearLayout.LayoutParams(context.dp(54), context.dp(48)))
        val title = context.label("", 16f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = context.label("", 13f, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), 0, 0, 0)
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        }
        card.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        val more = context.action("⋮").apply { textSize = 23f; setPadding(0, 0, 0, 0) }
        card.addView(more, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        return Card(card, logo, fallback, title, subtitle, more)
    }

    override fun onBindViewHolder(holder: Card, position: Int) {
        val channel = channels[position]
        holder.title.text = if (channel.id in favorites) "★  ${channel.name}" else channel.name
        holder.subtitle.text = programs[channel.tvgId ?: channel.id]?.title ?: channel.group
        holder.root.setOnClickListener { play(channel) }
        holder.root.setOnLongClickListener { options(channel); true }
        holder.more.contentDescription = "Opciones de ${channel.name}"
        holder.more.setOnClickListener { options(channel) }
        ChannelArtworkLoader.bind(channel.logoUrl, holder.logo, holder.fallback)
    }

    override fun onViewRecycled(holder: Card) {
        ChannelArtworkLoader.bind(null, holder.logo, holder.fallback)
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = channels.size

    class Card(
        val root: View,
        val logo: ImageView,
        val fallback: TextView,
        val title: TextView,
        val subtitle: TextView,
        val more: Button,
    ) : RecyclerView.ViewHolder(root)
}
