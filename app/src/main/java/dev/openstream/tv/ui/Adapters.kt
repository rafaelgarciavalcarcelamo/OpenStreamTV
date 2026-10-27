package dev.openstream.tv.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GroupAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<GroupAdapter.Holder>() {
    private var items = emptyList<Pair<String, Int>>()
    private var selected = "Todos"

    init { setHasStableIds(true) }

    fun submit(newItems: List<Pair<String, Int>>, selectedGroup: String) {
        items = newItems
        selected = selectedGroup
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val compact = context.resources.configuration.screenHeightDp <= 600
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = context.getDrawable(R.drawable.focus_card)
            setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 48 else 56)).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            }
            tvFocusMotion()
        }
        val name = context.label("", if (compact) 16f else 17f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val count = context.label("", 14f, secondary = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        view.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        view.addView(count, LinearLayout.LayoutParams(context.dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        return Holder(view, name, count)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (name, count) = items[position]
        holder.name.text = name
        holder.count.text = count.toString()
        holder.root.isSelected = name == selected
        holder.name.typeface = if (name == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.name.setTextColor(holder.root.context.getColor(if (name == selected) R.color.accent else R.color.text_primary))
        holder.root.contentDescription = "$name, $count canales${if (name == selected) ", seleccionada" else ""}"
        holder.root.setOnClickListener { selected = name; notifyDataSetChanged(); onClick(name) }
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].first.hashCode().toLong()
    class Holder(val root: LinearLayout, val name: TextView, val count: TextView) : RecyclerView.ViewHolder(root)
}

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
    private var items = emptyList<Channel>()
    private var programs = emptyMap<String, Program>()
    private var favorites = emptySet<String>()
    private var numbers = emptyMap<String, Int>()
    private var lastChannelId: String? = null

    init { setHasStableIds(true) }

    fun submit(channels: List<Channel>, now: Map<String, Program>, favoriteIds: Set<String>, channelNumbers: Map<String, Int> = emptyMap(), lastWatchedId: String? = null) {
        items = channels
        programs = now
        favorites = favoriteIds
        numbers = channelNumbers
        lastChannelId = lastWatchedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val compact = context.resources.configuration.screenHeightDp <= 600
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = context.getDrawable(R.drawable.focus_card)
            setPadding(context.dp(14), context.dp(if (compact) 5 else 8), context.dp(14), context.dp(if (compact) 5 else 8))
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 72 else 82)).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            }
            tvFocusMotion()
        }
        val number = context.label("", if (compact) 16f else 17f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.accent))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.number_tile)
        }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = context.label("", if (compact) 17f else 19f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = context.label("", if (compact) 13f else 14f, secondary = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val star = context.label("", if (compact) 19f else 21f).apply { gravity = Gravity.CENTER }
        details.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        details.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(number, LinearLayout.LayoutParams(context.dp(if (compact) 44 else 50), context.dp(if (compact) 44 else 50)).apply { rightMargin = context.dp(16) })
        root.addView(details, LinearLayout.LayoutParams(0, context.dp(if (compact) 50 else 56), 1f))
        root.addView(star, LinearLayout.LayoutParams(context.dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        return Holder(root, number, title, subtitle, star)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val isFavorite = channel.id in favorites
        val currentProgram = programs[channel.tvgId ?: channel.id]?.title
        holder.number.text = numbers[channel.id]?.toString() ?: (position + 1).toString()
        holder.title.text = channel.name
        holder.subtitle.text = currentProgram ?: channel.group
        holder.root.isSelected = channel.id == lastChannelId
        holder.star.text = if (isFavorite) "★" else "☆"
        holder.star.setTextColor(holder.root.context.getColor(if (isFavorite) R.color.accent else R.color.text_secondary))
        holder.root.contentDescription = "Canal ${holder.number.text}, ${channel.name}. ${currentProgram ?: channel.group}. Mantén OK para ${if (isFavorite) "quitar de" else "añadir a"} favoritos"
        holder.root.setOnClickListener { onClick(channel) }
        holder.root.setOnLongClickListener { onLongClick(channel); true }
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    class Holder(val root: View, val number: TextView, val title: TextView, val subtitle: TextView, val star: TextView) : RecyclerView.ViewHolder(root)
}

class ProgramAdapter : RecyclerView.Adapter<ProgramAdapter.Holder>() {
    private var items = emptyList<Program>()
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(programs: List<Program>) { items = programs; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val view = context.label("", 14f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(42))
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.text = "${time.format(Date(item.startMillis))}  ${item.title}"
    }

    override fun getItemCount() = items.size
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
