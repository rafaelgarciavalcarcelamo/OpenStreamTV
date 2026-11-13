package dev.openstream.tv.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.HomeChannelRanker
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.Preferences

class PersonalizationActivity : ComponentActivity() {
    private lateinit var preferences: Preferences
    private lateinit var selectedAdapter: PersonalizationChannelAdapter
    private lateinit var availableAdapter: PersonalizationChannelAdapter
    private lateinit var selectedCount: TextView
    private var playlist: Playlist? = null
    private val selectedIds = mutableListOf<String>()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        preferences = Preferences(this)
        playlist = AppRepository(this).loadSavedPlaylist()
        val validIds = playlist?.channels.orEmpty().mapTo(HashSet(), Channel::id)
        selectedIds += preferences.pinnedChannelOrder
            .filter(validIds::contains)
        buildUi()
        refreshLists()
    }

    private fun buildUi() {
        val compact = resources.configuration.screenHeightDp <= 600
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(30), dp(if (compact) 15 else 24), dp(30), dp(if (compact) 15 else 24))
        }
        root.addView(label("Personalizar Inicio", if (compact) 28f else 34f).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 48 else 60)))
        root.addView(label("Elige qué categorías aparecen y qué canales quieres ver primero.", 16f, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 34 else 42)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = action("Buscar canal").apply { setOnClickListener { showSearch() } }
        val groups = action("Categorías de Inicio").apply { setOnClickListener { showGroupPicker() } }
        val suggested = action("Añadir LaLiga, DAZN, Champions y F1").apply { setOnClickListener { addSuggestions() } }
        val hidden = action("Canales ocultos").apply { setOnClickListener { showHiddenChannels() } }
        val clear = action("Vaciar selección").apply {
            setOnClickListener {
                selectedIds.clear()
                refreshLists()
                Toast.makeText(this@PersonalizationActivity, "Selección vaciada", Toast.LENGTH_SHORT).show()
            }
        }
        actions.addView(search, LinearLayout.LayoutParams(dp(170), dp(48)).apply { rightMargin = dp(10) })
        actions.addView(groups, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        actions.addView(suggested, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        actions.addView(hidden, LinearLayout.LayoutParams(dp(170), dp(48)).apply { rightMargin = dp(10) })
        actions.addView(clear, LinearLayout.LayoutParams(dp(170), dp(48)))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val selectedPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val selectedHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectedHeading.addView(label("Primero en Inicio", 20f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        selectedCount = label("", 14f, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        selectedHeading.addView(selectedCount, LinearLayout.LayoutParams(dp(110), dp(42)))
        selectedPane.addView(selectedHeading)
        selectedAdapter = PersonalizationChannelAdapter { channel -> showOrderActions(channel) }
        selectedPane.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PersonalizationActivity)
            adapter = selectedAdapter
            clipChildren = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val availablePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        availablePane.addView(label("Añadir canales", 20f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        availableAdapter = PersonalizationChannelAdapter { channel ->
            if (channel.id !in selectedIds) selectedIds += channel.id
            refreshLists()
            Toast.makeText(this, "${channel.name} aparecerá en Inicio", Toast.LENGTH_SHORT).show()
        }
        availablePane.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PersonalizationActivity)
            adapter = availableAdapter
            clipChildren = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panes.addView(selectedPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(14) })
        panes.addView(availablePane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(panes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(label("OK: añadir o editar orden", 14f, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.addView(action("Ordenar categorías").apply {
            setOnClickListener { showGroupOrder() }
        }, LinearLayout.LayoutParams(dp(245), dp(48)).apply { rightMargin = dp(12) })
        footer.addView(action("Guardar y volver").apply {
            setOnClickListener { saveAndFinish() }
        }, LinearLayout.LayoutParams(dp(230), dp(48)))
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(6) })
        setContentView(root)
        search.requestFocus()
    }

    private fun refreshLists() {
        val visibleChannels = preferences.visibleChannels(playlist)
        val byId = visibleChannels.associateBy(Channel::id)
        val selected = selectedIds.mapNotNull(byId::get)
        val available = visibleChannels.asSequence()
            .filterNot { it.id in selectedIds }
            .filter { query.isBlank() || it.name.contains(query, true) || it.group.contains(query, true) }
            .toList()
        selectedAdapter.submit(selected, ordered = true)
        availableAdapter.submit(available, ordered = false)
        selectedCount.text = "${selected.size} elegidos"
    }

    private fun showHiddenChannels() {
        val hiddenIds = preferences.hiddenChannelIds
        val hidden = playlist?.channels.orEmpty().filter { it.id in hiddenIds }
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hay canales ocultos", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Canales ocultos")
            .setItems(hidden.map(Channel::name).toTypedArray()) { _, index ->
                val channel = hidden[index]
                preferences.hiddenChannelIds = preferences.hiddenChannelIds - channel.id
                refreshLists()
                Toast.makeText(this, "${channel.name} restaurado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showSearch() {
        val input = EditText(this).apply { hint = "Nombre o categoría"; setText(query); setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Buscar un canal")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Mostrar todos") { _, _ -> query = ""; refreshLists() }
            .setPositiveButton("Buscar") { _, _ -> query = input.text.toString().trim(); refreshLists() }
            .show()
    }

    private fun addSuggestions() {
        var added = 0
        HomeChannelRanker.sportHighlights(preferences.visibleChannels(playlist), perTopic = 5).forEach { channel ->
            if (channel.id !in selectedIds) { selectedIds += channel.id; added++ }
        }
        refreshLists()
        Toast.makeText(this, if (added > 0) "$added canales destacados añadidos" else "Ya estaban añadidos", Toast.LENGTH_SHORT).show()
    }

    private fun showGroupPicker() {
        val groups = preferences.visibleChannels(playlist)
            .map(Channel::group).distinct().sortedBy(String::lowercase)
        if (groups.isEmpty()) {
            Toast.makeText(this, "No hay categorías disponibles", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = preferences.homeVisibleGroups
        val selected = groups.filterTo(mutableSetOf()) { saved.isEmpty() || it in saved }
        AlertDialog.Builder(this)
            .setTitle("Categorías visibles en Inicio")
            .setMultiChoiceItems(groups.toTypedArray(), BooleanArray(groups.size) { saved.isEmpty() || groups[it] in saved }) { _, index, checked ->
                if (checked) selected += groups[index] else selected -= groups[index]
            }
            .setPositiveButton("Guardar") { _, _ ->
                preferences.homeVisibleGroups = selected
                Toast.makeText(this, "Categorías de Inicio actualizadas", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Solo deportes") { _, _ ->
                val sports = groups.filterTo(mutableSetOf(), ::isSportsGroup)
                preferences.homeVisibleGroups = sports
                preferences.homeGroupOrder = sports.sortedWith(sportsGroupComparator())
                Toast.makeText(this, "Inicio mostrará ${sports.size} categorías deportivas", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Mostrar todas") { _, _ ->
                preferences.homeVisibleGroups = emptySet()
                Toast.makeText(this, "Se mostrarán todas las categorías", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showGroupOrder() {
        val visible = preferences.homeVisibleGroups
        val available = preferences.visibleChannels(playlist)
            .map(Channel::group).distinct()
            .filter { visible.isEmpty() || it in visible }
        if (available.isEmpty()) {
            Toast.makeText(this, "Selecciona primero alguna categoría", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = preferences.homeGroupOrder
        val ordered = buildList {
            addAll(saved.filter(available::contains))
            addAll(available.filterNot(::contains).sortedWith(sportsGroupComparator()))
        }
        AlertDialog.Builder(this)
            .setTitle("Orden de las categorías")
            .setItems(ordered.toTypedArray()) { _, index -> showGroupOrderActions(ordered, index) }
            .setNeutralButton("Orden deportivo") { _, _ ->
                preferences.homeGroupOrder = available.sortedWith(sportsGroupComparator())
                Toast.makeText(this, "Orden deportivo aplicado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showGroupOrderActions(current: List<String>, index: Int) {
        val updated = current.toMutableList()
        AlertDialog.Builder(this)
            .setTitle(current[index])
            .setItems(arrayOf("Subir", "Bajar", "Poner la primera", "Poner la última")) { _, action ->
                when (action) {
                    0 -> if (index > 0) updated.add(index - 1, updated.removeAt(index))
                    1 -> if (index < updated.lastIndex) updated.add(index + 1, updated.removeAt(index))
                    2 -> updated.add(0, updated.removeAt(index))
                    3 -> updated.add(updated.removeAt(index))
                }
                preferences.homeGroupOrder = updated
                showGroupOrder()
            }
            .setNegativeButton("Cancelar") { _, _ -> showGroupOrder() }
            .show()
    }

    private fun isSportsGroup(group: String): Boolean {
        val normalized = group.lowercase()
        return SPORTS_GROUP_TERMS.any(normalized::contains)
    }

    private fun sportsGroupComparator(): Comparator<String> =
        compareBy<String>(::sportsGroupPriority).thenBy(String::lowercase)

    private fun sportsGroupPriority(group: String): Int {
        val normalized = group.lowercase()
        val preferredProvider = "movistar" in normalized || "orange" in normalized
        return when {
            preferredProvider && "laliga" in normalized && "dazn" !in normalized && "hypermotion" !in normalized -> 0
            "dazn" in normalized && "laliga" in normalized -> 1
            "liga de campeones" in normalized || "champions" in normalized || "uefa" in normalized -> 2
            "f1" in normalized || "formula 1" in normalized -> 3
            "orange" in normalized -> 4
            else -> 5
        }
    }

    private fun showOrderActions(channel: Channel) {
        val index = selectedIds.indexOf(channel.id)
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(arrayOf("Subir", "Bajar", "Quitar de Inicio")) { _, which ->
                when (which) {
                    0 -> if (index > 0) selectedIds.add(index - 1, selectedIds.removeAt(index))
                    1 -> if (index in 0 until selectedIds.lastIndex) selectedIds.add(index + 1, selectedIds.removeAt(index))
                    2 -> selectedIds.remove(channel.id)
                }
                refreshLists()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveAndFinish() {
        preferences.pinnedChannelOrder = selectedIds
        Toast.makeText(this, "Orden de Inicio guardado", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private val SPORTS_GROUP_TERMS = listOf(
            "deport", "fútbol", "futbol", "laliga", "liga de campeones", "champions",
            "uefa", "dazn", "f1", "formula 1", "motor", "motogp", "tenis", "tennis",
            "golf", "nba", "baloncesto", "basket", "nfl", "eurosport", "bein sport",
        )
    }
}

private class PersonalizationChannelAdapter(private val onClick: (Channel) -> Unit) :
    RecyclerView.Adapter<PersonalizationChannelAdapter.Holder>() {
    private var channels = emptyList<Channel>()
    private var ordered = false

    init { setHasStableIds(true) }

    fun submit(items: List<Channel>, ordered: Boolean) {
        channels = items
        this.ordered = ordered
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = context.getDrawable(R.drawable.focus_card)
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(64)).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            }
            tvFocusMotion()
        }
        val number = context.label("", 17f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
        }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = context.label("", 17f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val group = context.label("", 13f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        details.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        details.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(number, LinearLayout.LayoutParams(context.dp(46), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return Holder(root, number, title, group)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = channels[position]
        holder.number.text = if (ordered) "${position + 1}" else "+"
        holder.title.text = channel.name
        holder.group.text = channel.group
        holder.root.contentDescription = if (ordered) "${position + 1}, ${channel.name}. OK para cambiar su orden" else "${channel.name}. OK para añadir a Inicio"
        holder.root.setOnClickListener { onClick(channel) }
    }

    override fun getItemCount() = channels.size
    override fun getItemId(position: Int) = channels[position].id.hashCode().toLong()
    class Holder(val root: LinearLayout, val number: TextView, val title: TextView, val group: TextView) : RecyclerView.ViewHolder(root)
}
