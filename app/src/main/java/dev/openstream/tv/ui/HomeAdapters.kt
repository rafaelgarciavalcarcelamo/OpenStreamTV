package dev.openstream.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.TextUtils
import android.util.LruCache
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.BoundedStreams
import dev.openstream.tv.data.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class HomeChannelMetadata(
    val schedule: String? = null,
    val quality: String = "Buena",
    val qualityLevel: Int = 2,
)

/** Poster-shaped channel cards used only on the home screen. The full catalog stays in ChannelAdapter. */
class HomeChannelAdapter(
    private val onPlay: (Channel) -> Unit,
    private val onFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<HomeChannelAdapter.Holder>() {
    private var channels = emptyList<Channel>()
    private var favorites = emptySet<String>()
    private var metadata = emptyMap<String, HomeChannelMetadata>()

    init { setHasStableIds(true) }

    fun submit(newChannels: List<Channel>, favoriteIds: Set<String>, cardMetadata: Map<String, HomeChannelMetadata> = emptyMap()) {
        channels = newChannels
        favorites = favoriteIds
        metadata = cardMetadata
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val compact = context.resources.configuration.screenHeightDp <= 600
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            background = context.getDrawable(R.drawable.poster_card)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            layoutParams = RecyclerView.LayoutParams(context.dp(if (compact) 224 else 254), context.dp(if (compact) 174 else 196)).apply {
                rightMargin = context.dp(12)
                topMargin = context.dp(4)
                bottomMargin = context.dp(4)
            }
            tvFocusMotion()
        }
        val artwork = FrameLayout(context)
        val monogram = context.label("", 39f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = .42f
        }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val live = context.label("●  DIRECTO", 11f).apply {
            setTextColor(context.getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
        }
        val star = context.label("", 21f).apply { gravity = Gravity.END or Gravity.TOP }
        artwork.addView(monogram, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artwork.addView(logo, FrameLayout.LayoutParams(context.dp(118), context.dp(if (compact) 56 else 66), Gravity.CENTER))
        artwork.addView(live, FrameLayout.LayoutParams(context.dp(108), context.dp(22), Gravity.START or Gravity.TOP))
        artwork.addView(star, FrameLayout.LayoutParams(context.dp(32), context.dp(28), Gravity.END or Gravity.TOP))
        val title = context.label("", if (compact) 16f else 18f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = context.label("", 12f, secondary = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val quality = context.label("", 11f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
        }
        card.addView(artwork, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 42 else 48)))
        card.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 34 else 38)))
        card.addView(quality, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(20)))
        return Holder(card, monogram, logo, title, subtitle, quality, star)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = channels[position]
        val favorite = channel.id in favorites
        val details = metadata[channel.id] ?: HomeChannelMetadata()
        holder.monogram.text = channel.name.substringAfter(':').trim().take(2).uppercase()
        holder.title.text = channel.name
        holder.subtitle.text = details.schedule ?: "Programación no disponible"
        holder.quality.text = "●  ${details.quality}"
        holder.quality.setTextColor(holder.root.context.getColor(when (details.qualityLevel) {
            0 -> R.color.quality_bad
            1 -> R.color.quality_medium
            else -> R.color.quality_good
        }))
        holder.star.text = if (favorite) "★" else "☆"
        holder.star.setTextColor(holder.root.context.getColor(if (favorite) R.color.accent else R.color.text_secondary))
        holder.root.contentDescription = "${channel.name}. ${details.schedule ?: "Programación no disponible"}. Calidad ${details.quality}. OK para ver. Mantén OK para ${if (favorite) "quitar de" else "añadir a"} favoritos"
        holder.root.setOnClickListener { onPlay(channel) }
        holder.root.setOnLongClickListener { onFavorite(channel); true }
        ChannelArtworkLoader.bind(channel.logoUrl, holder.logo, holder.monogram)
    }

    override fun getItemCount() = channels.size
    override fun getItemId(position: Int) = channels[position].id.hashCode().toLong()

    class Holder(
        val root: LinearLayout,
        val monogram: TextView,
        val logo: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val quality: TextView,
        val star: TextView,
    ) : RecyclerView.ViewHolder(root)
}

class HomeCategoryAdapter(private val onOpen: (String) -> Unit) : RecyclerView.Adapter<HomeCategoryAdapter.Holder>() {
    private var groups = emptyList<Pair<String, Int>>()

    init { setHasStableIds(true) }

    fun submit(newGroups: List<Pair<String, Int>>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = context.getDrawable(R.drawable.poster_card)
            setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
            layoutParams = RecyclerView.LayoutParams(context.dp(222), context.dp(92)).apply {
                rightMargin = context.dp(12)
                topMargin = context.dp(4)
                bottomMargin = context.dp(4)
            }
            tvFocusMotion()
        }
        val title = context.label("", 17f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val count = context.label("", 13f, secondary = true)
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(24)))
        return Holder(root, title, count)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (name, count) = groups[position]
        holder.title.text = name
        holder.count.text = "$count canales"
        holder.root.contentDescription = "$name, $count canales. OK para abrir"
        holder.root.setOnClickListener { onOpen(name) }
    }

    override fun getItemCount() = groups.size
    override fun getItemId(position: Int) = groups[position].first.hashCode().toLong()

    class Holder(val root: LinearLayout, val title: TextView, val count: TextView) : RecyclerView.ViewHolder(root)
}

internal object ChannelArtworkLoader {
    private const val MAX_IMAGE_BYTES = 256 * 1024L
    private const val MAX_HERO_IMAGE_BYTES = 2 * 1024 * 1024L
    private const val MAX_DECODED_DIMENSION = 1920
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newFixedThreadPool(3)
    private val priorityExecutor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun bind(url: String?, image: ImageView, fallback: TextView) {
        bindWithExecutor(url, image, fallback, executor, MAX_IMAGE_BYTES, false)
    }

    fun bindPriority(url: String?, image: ImageView, fallback: TextView) {
        bindWithExecutor(url, image, fallback, priorityExecutor, MAX_HERO_IMAGE_BYTES, true)
    }

    private fun bindWithExecutor(
        url: String?,
        image: ImageView,
        fallback: TextView,
        worker: Executor,
        maxBytes: Long,
        previewFallback: Boolean,
    ) {
        image.tag = url
        image.setImageDrawable(null)
        image.visibility = View.GONE
        fallback.visibility = View.VISIBLE
        if (url.isNullOrBlank()) return
        cache.get(url)?.let { show(image, fallback, url, it); return }
        worker.execute {
            val candidates = mutableListOf(url)
            if (previewFallback && url.contains("thesportsdb.com") && !url.endsWith("/medium")) {
                candidates += "$url/medium"
            }
            var bitmap: Bitmap? = null
            for (candidate in candidates) {
                bitmap = runCatching { download(candidate, maxBytes) }
                    .onFailure { Log.w("OpenStreamArtwork", "Image failed: $candidate", it) }
                    .getOrNull()
                if (bitmap != null) break
            }
            bitmap ?: return@execute
            cache.put(url, bitmap)
            image.post { show(image, fallback, url, bitmap) }
        }
    }

    private fun download(url: String, maxBytes: Long): Bitmap? {
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("OpenStreamArtwork", "Image HTTP ${response.code}: $url")
                return@use null
            }
            val body = response.body ?: return@use null
            if (body.contentLength() > maxBytes) return@use null
            val output = ByteArrayOutputStream()
            body.byteStream().use { BoundedStreams.copy(it, output, maxBytes) }
            val bytes = output.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_DECODED_DIMENSION || bounds.outHeight / sample > MAX_DECODED_DIMENSION) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun show(image: ImageView, fallback: TextView, url: String, bitmap: Bitmap) {
        if (image.tag != url) return
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        fallback.visibility = View.GONE
    }
}
