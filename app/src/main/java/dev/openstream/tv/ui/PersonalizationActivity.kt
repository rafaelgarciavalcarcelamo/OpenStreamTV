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
import dev.openstream.tv.data.Preferences

class PersonalizationActivity : ComponentActivity() {
    private lateinit var preferences: Preferences
    private lateinit var selectedAdapter: PersonalizationChannelAdapter
    private lateinit var availableAdapter: PersonalizationChannelAdapter
    private lateinit var selectedCount: TextView
    private var allChannels = emptyList<Channel>()
    private val selectedIds = mutableListOf<String>()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        preferences = Preferences(this)
        allChannels = AppRepository(this).loadSavedPlaylist()?.channels.orEmpty()
        val validIds = allChannels.mapTo(HashSet(), Channel::id)
        selectedIds += preferences.pinnedChannelOrder.filter(validIds::contains)
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
        root.addView(label("Personalizar tus canales", if (compact) 28f else 34f).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 48 else 60)))
        root.addView(label("Elige los canales que aparecerán primero en Inicio y ordénalos como quieras.", 16f, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 34 else 42)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = action("Buscar canal").apply { setOnClickListener { showSearch() } }
        val suggested = action("Añadir LaLiga, DAZN, Champions y F1").apply { setOnClickListener { addSuggestions() } }
        val clear = action("Vaciar selección").apply {
            setOnClickListener {
                selectedIds.clear()
                refreshLists()
                Toast.makeText(this@PersonalizationActivity, "Selección vaciada", Toast.LENGTH_SHORT).show()
            }
        }
        actions.addView(search, LinearLayout.LayoutParams(dp(190), dp(48)).apply { rightMargin = dp(10) })
        actions.addView(suggested, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        actions.addView(clear, LinearLayout.LayoutParams(dp(190), dp(48)))
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
        footer.addView(action("Guardar y volver").apply {
            setOnClickListener { saveAndFinish() }
        }, LinearLayout.LayoutParams(dp(230), dp(48)))
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(6) })
        setContentView(root)
        search.requestFocus()
    }

    private fun refreshLists() {
        val byId = allChannels.associateBy(Channel::id)
        val selected = selectedIds.mapNotNull(byId::get)
        val available = allChannels.asSequence()
            .filterNot { it.id in selectedIds }
            .filter { query.isBlank() || it.name.contains(query, true) || it.group.contains(query, true) }
            .toList()
        selectedAdapter.submit(selected, ordered = true)
        availableAdapter.submit(available, ordered = false)
        selectedCount.text = "${selected.size} elegidos"
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
        HomeChannelRanker.sportHighlights(allChannels, perTopic = 5).forEach { channel ->
            if (channel.id !in selectedIds) { selectedIds += channel.id; added++ }
        }
        refreshLists()
        Toast.makeText(this, if (added > 0) "$added canales destacados añadidos" else "Ya estaban añadidos", Toast.LENGTH_SHORT).show()
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
