package dev.openstream.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.ContentKind
import dev.openstream.tv.data.PlaylistSource
import dev.openstream.tv.data.VodSearch
import dev.openstream.tv.local.LocalControlRuntime

class VodActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var list: RecyclerView
    private lateinit var heading: TextView
    private lateinit var message: TextView
    private lateinit var back: View
    private lateinit var retry: View
    private lateinit var searchButton: View
    private var voiceDialog: AlertDialog? = null
    private val adapter = VodAdapter(::open)
    private val categoryAdapter = GroupAdapter(onOpen = ::openCategory, itemLabel = "títulos")
    private val seasonAdapter = GroupAdapter(onOpen = ::openSeason, itemLabel = "episodios")
    private var activeSource: PlaylistSource? = null
    private var kind = ContentKind.MOVIE
    private var series: Channel? = null
    private var categories = emptyMap<String, List<Channel>>()
    private var remoteCategories = emptyMap<String, String>()
    private var loadedCategory: Pair<String, List<Channel>>? = null
    private var seasons = emptyMap<String, List<Channel>>()
    private var category: String? = null
    private var season: String? = null
    private var seriesPosition = 0
    private var requestId = 0
    private var searchQuery: String? = null
    private var searchResults = emptyList<Channel>()
    private val sectionTitle get() = if (kind == ContentKind.SERIES) "Series" else "Películas"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        repository = AppRepository(this)
        activeSource = repository.activePlaylistSource()
        if (intent.getStringExtra(EXTRA_KIND) == ContentKind.SERIES.name) kind = ContentKind.SERIES
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(32), dp(18), dp(32), dp(18))
        }
        val header = LinearLayout(this)
        heading = label(sectionTitle, 26f)
        heading.maxLines = 1
        heading.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(heading, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchButton = action("Buscar").apply { setOnClickListener { showSearchDialog() } }
        header.addView(searchButton, LinearLayout.LayoutParams(dp(140), dp(54)))
        back = action("Volver").apply { setOnClickListener { goBack() } }
        header.addView(back, LinearLayout.LayoutParams(dp(130), dp(54)))
        root.addView(header)
        message = label("", 17f, secondary = true)
        root.addView(message)
        retry = action("Reintentar").apply {
            setOnClickListener {
                val query = searchQuery
                if (query != null && series == null) search(query) else load()
            }
        }
        root.addView(retry, LinearLayout.LayoutParams(dp(160), dp(52)))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@VodActivity)
            adapter = this@VodActivity.adapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        load()
    }

    private fun load() {
        val source = activeSource
        val request = ++requestId
        val selectedCategory = category
        val remoteId = remoteCategories[selectedCategory]
        searchButton.visibility = if (series == null) View.VISIBLE else View.GONE
        heading.text = series?.name ?: selectedCategory?.let { "$sectionTitle · $it" } ?: sectionTitle
        display(emptyList(), notice = if (source == null) "Añade una lista para ver el catálogo." else "Cargando catálogo…")
        if (source == null) return
        if (series == null && selectedCategory == null) {
            repository.loadVodCategories(source, kind) { groups ->
                runOnUiThread {
                    if (!isCurrent(request)) return@runOnUiThread
                    if (groups.isEmpty()) loadEntries(source, request, null, null)
                    else {
                        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("es"))
                        remoteCategories = groups.entries.sortedWith(compareBy(collator) { it.value })
                            .associate { (id, name) -> name to id }
                        showCategories()
                    }
                }
            }
        } else loadEntries(source, request, selectedCategory, remoteId)
    }

    private fun isCurrent(request: Int) = !isFinishing && !isDestroyed && request == requestId

    private fun showSearchDialog() {
        if (activeSource == null) return
        val input = EditText(this).apply {
            hint = "Título"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(VodSearch.MAX_QUERY_LENGTH))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(searchQuery.orEmpty())
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscar en $sectionTitle")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Voz desde el móvil") { _, _ -> showVoiceDialog() }
            .setPositiveButton("Buscar", null)
            .create()
        fun submit() {
            val query = input.text.toString().trim()
            if (query.isBlank()) input.error = "Escribe un título"
            else {
                dialog.dismiss()
                search(query)
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        input.requestFocus()
    }

    private fun showVoiceDialog() {
        LocalControlRuntime.start(this)
        val url = LocalControlRuntime.mobileUrl(this)
        if (url == null || !LocalControlRuntime.isRunning()) {
            AlertDialog.Builder(this).setMessage("Conecta el móvil y la TV a la misma red para dictar un título.")
                .setPositiveButton("Cerrar", null).show()
            return
        }
        val size = dp(230)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(label("Escanea el QR con el móvil conectado a la misma red. Dicta el título con el micrófono del teclado y pulsa «Buscar en la TV».", 16f).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ImageView(context).apply {
                setImageBitmap(qrBitmap("$url#buscar", size))
                contentDescription = "Código QR para buscar en $sectionTitle desde el móvil"
            }, LinearLayout.LayoutParams(size, size).apply { topMargin = dp(10) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Voz desde el móvil")
            .setView(content)
            .setNegativeButton("Cerrar", null)
            .create()
        val input: (String) -> Unit = { query ->
            runOnUiThread {
                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                    dialog.dismiss()
                    search(query)
                }
            }
        }
        voiceDialog = dialog
        dialog.setOnDismissListener {
            LocalControlRuntime.vodSearchInput.compareAndSet(input, null)
            voiceDialog = null
        }
        dialog.show()
        LocalControlRuntime.vodSearchInput.set(input)
    }

    private fun search(query: String) {
        val source = activeSource ?: return
        val request = ++requestId
        searchQuery = query
        searchResults = emptyList()
        heading.text = "$sectionTitle · $query"
        display(emptyList(), notice = "Buscando títulos…")
        repository.searchVod(source, kind, query, remoteCategories.isNotEmpty()) { result ->
            runOnUiThread {
                if (!isCurrent(request)) return@runOnUiThread
                result.onSuccess {
                    searchResults = it
                    showSearchResults()
                }.onFailure {
                    display(emptyList(), notice = "No se pudo completar la búsqueda.")
                    retry.visibility = View.VISIBLE
                    retry.requestFocus()
                }
            }
        }
    }

    private fun showSearchResults(position: Int = 0) {
        searchButton.visibility = View.VISIBLE
        heading.text = "$sectionTitle · ${searchQuery.orEmpty()}"
        display(searchResults, position, "No se encontraron títulos.")
    }

    private fun loadEntries(source: PlaylistSource, request: Int, selectedCategory: String?, remoteId: String?) {
        repository.loadVod(source, kind, series, remoteId) { result ->
            runOnUiThread {
                if (!isCurrent(request)) return@runOnUiThread
                result.onSuccess { entries ->
                    if (series != null) {
                        seasons = entries.groupBy(Channel::group)
                        showSeries()
                    } else if (selectedCategory != null) {
                        val titles = entries.map { it.copy(group = selectedCategory) }
                        loadedCategory = selectedCategory to titles
                        display(titles, notice = "No hay títulos en esta categoría.")
                    } else {
                        categories = entries.groupBy(Channel::group)
                            .toList()
                            .sortedWith(compareBy<Pair<String, List<Channel>>> { it.first == "Sin categoría" }
                                .thenBy(java.text.Collator.getInstance(java.util.Locale.forLanguageTag("es"))) { it.first })
                            .toMap()
                        showCategories()
                    }
                }.onFailure { error ->
                    if (dev.openstream.tv.BuildConfig.DEBUG) {
                        android.util.Log.d("OpenStreamVod", "${source.type} $kind: ${error.javaClass.simpleName}")
                    }
                    display(emptyList(), notice = "No se pudo cargar el catálogo.")
                    retry.visibility = View.VISIBLE
                    retry.requestFocus()
                }
            }
        }
    }

    private fun display(entries: List<Channel>, position: Int = 0, notice: String = "") {
        adapter.entries = entries
        adapter.notifyDataSetChanged()
        showList(adapter, position, notice)
    }

    private fun showList(rows: RecyclerView.Adapter<*>, position: Int, notice: String = "") {
        retry.visibility = View.GONE
        message.text = notice
        message.visibility = if (rows.itemCount == 0) View.VISIBLE else View.GONE
        if (list.adapter !== rows) list.adapter = rows
        back.requestFocus()
        if (rows.itemCount == 0) return
        list.scrollToPosition(position)
        val request = requestId
        list.doOnNextLayout {
            if (request == requestId && list.adapter === rows && currentFocus == back) {
                list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun showCategories(previous: String? = null) {
        heading.text = sectionTitle
        val rows = if (remoteCategories.isNotEmpty()) remoteCategories.keys.map { it to null }
            else categories.map { (name, entries) -> name to entries.size }
        categoryAdapter.submit(rows, null)
        showList(categoryAdapter, rows.indexOfFirst { it.first == previous }.coerceAtLeast(0), "No hay títulos en esta lista.")
    }

    private fun openCategory(name: String, position: Int = 0) {
        category = name
        heading.text = "$sectionTitle · $name"
        val entries = categories[name] ?: loadedCategory?.takeIf { it.first == name }?.second
        if (entries != null) display(entries, position, "No hay títulos en esta categoría.")
        else load()
    }

    private fun showSeries(previous: String? = null) {
        heading.text = series?.name.orEmpty()
        if (seasons.size > 1) {
            seasonAdapter.submit(seasons.map { (name, entries) -> name to entries.size }, null)
            showList(seasonAdapter, seasons.keys.indexOf(previous).coerceAtLeast(0))
        } else display(seasons.values.firstOrNull().orEmpty(), notice = "No hay episodios disponibles.")
    }

    private fun openSeason(name: String) {
        season = name
        heading.text = "${series?.name.orEmpty()} · $name"
        display(seasons[name].orEmpty())
    }

    private fun open(entry: Channel, position: Int) {
        if (entry.url.isBlank()) {
            seriesPosition = position
            series = entry
            load()
        } else {
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, entry.id)
                .putExtra(PlayerActivity.EXTRA_VOD_TITLE, entry.name)
                .putExtra(PlayerActivity.EXTRA_VOD_SUBTITLE_ID, series?.id ?: entry.id)
                .putExtra(PlayerActivity.EXTRA_SOURCE_ID, activeSource?.id)
                .putExtra(PlayerActivity.EXTRA_VOD_URL, entry.url))
        }
    }

    private fun goBack() {
        requestId++
        when {
            season != null -> {
                val previous = season
                season = null
                showSeries(previous)
            }
            series != null -> {
                series = null
                seasons = emptyMap()
                searchButton.visibility = View.VISIBLE
                if (searchQuery != null) showSearchResults(seriesPosition)
                else openCategory(requireNotNull(category), seriesPosition)
            }
            searchQuery != null -> {
                searchQuery = null
                searchResults = emptyList()
                val previous = category
                if (previous != null) openCategory(previous)
                else if (categories.isEmpty() && remoteCategories.isEmpty()) load()
                else showCategories()
            }
            category != null -> {
                val previous = category
                category = null
                adapter.entries = emptyList()
                adapter.notifyDataSetChanged()
                showCategories(previous)
            }
            else -> finish()
        }
    }

    override fun onStop() {
        voiceDialog?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        repository.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "vod_kind"
    }
}

private class VodAdapter(private val open: (Channel, Int) -> Unit) : RecyclerView.Adapter<VodAdapter.Holder>() {
    var entries = emptyList<Channel>()

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(124)).apply {
                bottomMargin = context.dp(6)
            }
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            background = context.getDrawable(R.drawable.focus_card)
            isFocusable = true
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val fallback = context.label("▶", 26f).apply { gravity = android.view.Gravity.CENTER }
        val poster = android.widget.FrameLayout(context)
        poster.addView(art, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        poster.addView(fallback, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(poster, LinearLayout.LayoutParams(context.dp(74), ViewGroup.LayoutParams.MATCH_PARENT))
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, 0, 0)
        }
        val title = context.label("", 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val metadata = context.label("", 14f, secondary = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val synopsis = context.label("", 15f, secondary = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        copy.addView(title)
        copy.addView(metadata)
        copy.addView(synopsis)
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return Holder(row, art, fallback, title, metadata, synopsis).also { holder ->
            row.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) open(entries[position], position)
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.name
        holder.metadata.text = entry.group
        holder.synopsis.text = entry.description.orEmpty()
        holder.synopsis.visibility = if (entry.description.isNullOrBlank()) View.GONE else View.VISIBLE
        ChannelArtworkLoader.bind(entry.logoUrl, holder.art, holder.fallback)
    }

    override fun onViewRecycled(holder: Holder) {
        ChannelArtworkLoader.bind(null, holder.art, holder.fallback)
    }

    class Holder(
        row: View, val art: ImageView, val fallback: TextView, val title: TextView,
        val metadata: TextView, val synopsis: TextView,
    ) : RecyclerView.ViewHolder(row)
}
