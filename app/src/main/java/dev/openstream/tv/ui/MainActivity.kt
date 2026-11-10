package dev.openstream.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Build
import android.provider.Settings
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
import android.widget.TextClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.HomeChannelRanker
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.PlaylistSourceType
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.Program
import dev.openstream.tv.data.StartupMode
import dev.openstream.tv.local.LocalControlRuntime
import dev.openstream.tv.local.LocalControlServer
import dev.openstream.tv.update.AppUpdateChecker
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

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
    private lateinit var vpnWarning: Button
    private lateinit var homeNav: Button
    private lateinit var channelsNav: Button
    private lateinit var favoritesNav: Button
    private lateinit var channelContext: TextView
    private lateinit var emptyState: TextView
    private lateinit var emptyAction: Button
    private lateinit var emptyPanel: LinearLayout
    private lateinit var channelList: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var homeScroll: ScrollView
    private lateinit var catalogContent: LinearLayout
    private lateinit var homeEmptyMessage: TextView
    private lateinit var homeLiveSection: LinearLayout
    private lateinit var homeCategoriesSection: LinearLayout
    private lateinit var homeLiveAdapter: HomeChannelAdapter
    private lateinit var homeCategoriesAdapter: HomeCategoryAdapter

    private var playlist: Playlist? = null
    private var programs = emptyList<Program>()
    private var selectedGroup = "Todos"
    private var query = ""
    private var guideReleasedForMemory = false
    private var showingCatalog = false
    private var networkCallbackRegistered = false
    private var playlistChangeReceiverRegistered = false
    private val playlistChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalControlServer.ACTION_PLAYLISTS_CHANGED) return
            playlist = repository.loadSavedPlaylist()
            selectedGroup = "Todos"
            refresh()
            showHome()
            Toast.makeText(this@MainActivity, "Listas actualizadas desde el móvil", Toast.LENGTH_SHORT).show()
        }
    }
    private val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateVpnWarningSoon()
        override fun onLost(network: Network) = updateVpnWarningSoon()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateVpnWarningSoon()
    }

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
        if (playlist != null && preferences.startupMode == StartupMode.FAVORITOS) showCatalog("Favoritos") else refresh()
        if (playlist != null && savedInstanceState == null && preferences.startupMode == StartupMode.ULTIMO_CANAL) {
            playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }?.let(::playChannel)
        }
        if (preferences.refreshSourcesOnLaunch) refreshSourcesSilently()
        AppUpdateChecker(this, preferences).check()
    }

    override fun onStart() {
        super.onStart()
        registerPlaylistChangeReceiver()
        repository.loadSavedPlaylist()?.let { playlist = it }
        registerVpnMonitor()
        refreshVpnWarning()
        refreshWatchButton()
        if (::homeLiveAdapter.isInitialized) refreshHome()
        if (::channelAdapter.isInitialized) refreshChannels()
        if (guideReleasedForMemory) {
            guideReleasedForMemory = false
            repository.loadSavedEpgAsync { restored -> runOnUiThread { programs = restored; refresh() } }
        }
    }

    override fun onStop() {
        unregisterPlaylistChangeReceiver()
        unregisterVpnMonitor()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::watchButton.isInitialized && !showingCatalog && currentFocus == null) {
            watchButton.post { if (hasWindowFocus() && currentFocus == null) watchButton.requestFocus() }
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
        val brandName = label("OPENSTREAM TV", 21f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .09f
            setTextColor(getColor(R.color.accent))
        }
        brand.addView(brandName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 24 else 28)))
        brand.addView(label("TELEVISIÓN EN DIRECTO", 11f, secondary = true).apply { letterSpacing = .08f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 16 else 20)))
        toolbar.addView(brand, LinearLayout.LayoutParams(0, dp(if (compact) 42 else 52), 1f))
        vpnWarning = action("⚠  VPN no detectada").apply {
            textSize = if (compact) 12f else 13f
            background = getDrawable(R.drawable.vpn_warning)
            setTextColor(getColorStateList(R.color.vpn_warning_text))
            visibility = View.GONE
            contentDescription = "VPN no detectada. Pulsa OK para abrir los ajustes de VPN"
            setOnClickListener { openVpnSettings() }
        }
        toolbar.addView(vpnWarning, LinearLayout.LayoutParams(dp(if (compact) 210 else 238), dp(if (compact) 36 else 42)).apply {
            rightMargin = dp(14)
        })
        toolbar.addView(TextClock(this).apply {
            format24Hour = "EEE d MMM  ·  HH:mm"
            format12Hour = "EEE d MMM  ·  h:mm a"
            textSize = 16f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(250), dp(if (compact) 40 else 46)))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 44 else 60)))

        val homeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, dp(8), 0, dp(20))
        }
        homeScroll = ScrollView(this).apply {
            isFillViewport = true
            clipChildren = true
            clipToPadding = true
            addView(homeBody)
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.hero_card)
            setPadding(dp(28), dp(if (compact) 15 else 22), dp(28), dp(if (compact) 15 else 22))
            clipChildren = false
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heroCopy.addView(label("DESTACADO PARA TI  ·  EN DIRECTO", 12f).apply {
            setTextColor(getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 26 else 30)))
        heroTitle = label("Tu televisión", if (compact) 31f else 39f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        heroCopy.addView(heroTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 78 else 100)))
        watchSubtitle = label("Elige un canal y disfruta", if (compact) 15f else 18f, secondary = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        heroCopy.addView(watchSubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 36 else 48)))
        watchButton = action("▶  Reproducir").apply {
            textSize = if (compact) 17f else 19f
            id = View.generateViewId()
            setOnClickListener {
                val channel = playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }
                    ?: playlist?.channels?.firstOrNull()
                if (channel != null) playChannel(channel) else showSourcesDialog()
            }
        }
        val heroActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        heroActions.addView(label("●  EN DIRECTO", 13f).apply {
            setTextColor(getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        heroActions.addView(watchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 52 else 62)))
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(20) })
        hero.addView(heroActions, LinearLayout.LayoutParams(dp(if (compact) 214 else 250), ViewGroup.LayoutParams.MATCH_PARENT))
        homeBody.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 172 else 228)).apply { bottomMargin = dp(12) })

        homeEmptyMessage = label("Añade tu lista de canales en «Mis listas» para empezar.", 19f, secondary = true).apply {
            gravity = Gravity.CENTER
        }
        homeBody.addView(homeEmptyMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)))
        homeLiveAdapter = HomeChannelAdapter(::playChannel, ::toggleFavorite)
        homeCategoriesAdapter = HomeCategoryAdapter(::showCatalog)
        homeLiveSection = homeSection("Tus canales · favoritos y más vistos", homeLiveAdapter, "Personalizar") {
            startActivity(Intent(this, PersonalizationActivity::class.java))
        }
        homeCategoriesSection = homeSection("Explorar por categorías", homeCategoriesAdapter, "Ver todas") { showCatalog("Todos") }
        homeBody.addView(homeLiveSection)
        homeBody.addView(homeCategoriesSection)

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.panel_surface)
            setPadding(dp(8), dp(if (compact) 4 else 5), dp(8), dp(if (compact) 4 else 5))
            clipChildren = false
        }
        homeNav = action("Inicio").apply {
            id = View.generateViewId()
            setOnClickListener { showHome() }
        }
        channelsNav = action("Canales").apply { setOnClickListener { showCatalog("Todos") } }
        favoritesNav = action("Favoritos").apply { setOnClickListener { showCatalog("Favoritos") } }
        searchButton = action("Buscar").apply { setOnClickListener { showSearchDialog() } }
        val sources = action("Mis listas").apply { setOnClickListener { showSourcesDialog() } }
        val mobile = action("Móvil").apply { setOnClickListener { showMobileControlDialog() } }
        val apps = action("Ver aplicaciones").apply { setOnClickListener { openDeviceLauncher() } }
        val settings = action("Ajustes").apply { setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }
        for (button in listOf(homeNav, channelsNav, favoritesNav, searchButton, sources, mobile, apps, settings)) {
            dock.addView(button, LinearLayout.LayoutParams(0, dp(if (compact) 40 else 46), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        homeNav.nextFocusDownId = watchButton.id
        watchButton.nextFocusUpId = homeNav.id
        root.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 48 else 58)).apply { bottomMargin = dp(if (compact) 6 else 7) })

        status = label("", 14f, secondary = true).apply { letterSpacing = .05f; visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = ProgressBar.GONE }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 18 else 24)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 2 else 3)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(if (compact) 6 else 10), 0, 0)
            clipChildren = false
            visibility = View.GONE
        }
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
        catalogContent = content
        val screens = FrameLayout(this).apply { clipChildren = true }
        screens.addView(homeScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screens.addView(catalogContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(screens, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(label("FLECHAS  Moverse     OK  Ver canal     MANTÉN OK  Favorito", 13f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 20 else 28)).apply { topMargin = dp(if (compact) 4 else 7) })
        setContentView(root)
        updateNavSelection()
        watchButton.post { if (!showingCatalog && !isFinishing) watchButton.requestFocus() }
    }

    private fun homeSection(
        title: String,
        adapter: RecyclerView.Adapter<*>,
        moreLabel: String,
        onMore: () -> Unit,
    ): LinearLayout {
        val compact = resources.configuration.screenHeightDp <= 600
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            clipChildren = false
            setPadding(0, dp(5), 0, dp(8))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, if (compact) 20f else 23f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        heading.addView(action(moreLabel).apply {
            textSize = 13f
            setOnClickListener { onMore() }
        }, LinearLayout.LayoutParams(dp(140), dp(36)))
        section.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        section.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), 0, dp(16), 0)
            setHasFixedSize(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            dp(if (adapter is HomeCategoryAdapter) 104 else if (compact) 160 else 180)))
        return section
    }

    private fun showHome() {
        showingCatalog = false
        homeScroll.visibility = View.VISIBLE
        catalogContent.visibility = View.GONE
        status.visibility = View.GONE
        updateNavSelection()
        homeScroll.smoothScrollTo(0, 0)
        watchButton.requestFocus()
    }

    private fun showCatalog(group: String) {
        selectedGroup = group
        query = ""
        searchButton.text = "Buscar"
        showingCatalog = true
        homeScroll.visibility = View.GONE
        catalogContent.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        updateNavSelection()
        refresh()
        channelList.scrollToPosition(0)
        channelList.post {
            if (channelAdapter.itemCount > 0) channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            else if (emptyAction.visibility == View.VISIBLE) emptyAction.requestFocus()
            else favoritesNav.requestFocus()
        }
    }

    private fun updateNavSelection() {
        homeNav.isSelected = !showingCatalog
        channelsNav.isSelected = showingCatalog && selectedGroup != "Favoritos"
        favoritesNav.isSelected = showingCatalog && selectedGroup == "Favoritos"
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
        refreshHome()
        refreshChannels()
    }

    private fun refreshHome() {
        val all = playlist?.channels.orEmpty()
        val favorites = preferences.favorites
        val homeChannels = HomeChannelRanker.rank(
            channels = all,
            pinnedIds = preferences.pinnedChannelOrder,
            favoriteIds = favorites,
            watchCounts = preferences.watchCounts(),
            limit = preferences.homeChannelCount,
            smart = preferences.smartHomeOrder,
        )
        homeLiveAdapter.submit(homeChannels, favorites)
        val groups = all.groupingBy(Channel::group).eachCount().toList()
            .sortedBy { it.first.lowercase() }.take(24)
        homeCategoriesAdapter.submit(groups)
        homeEmptyMessage.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        homeLiveSection.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        homeCategoriesSection.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshWatchButton() {
        if (!::watchButton.isInitialized) return
        val last = playlist?.channels?.firstOrNull { it.id == preferences.lastChannelId }
        val featured = last ?: playlist?.channels?.firstOrNull()
        heroTitle.text = featured?.name ?: "Tu tele empieza aquí"
        watchButton.text = if (featured != null) "▶  Reproducir" else "Añadir una lista"
        watchSubtitle.text = when {
            last != null -> "Sigue viendo  ·  ${last.group}"
            featured != null -> "En directo  ·  ${featured.group}"
            else -> "Añade tu lista y empieza a ver canales"
        }
    }

    private fun refreshVpnWarning() {
        if (!::vpnWarning.isInitialized) return
        vpnWarning.visibility = if (preferences.showVpnWarning && !isVpnActive()) View.VISIBLE else View.GONE
    }

    private fun isVpnActive(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun registerVpnMonitor() {
        if (networkCallbackRegistered) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                vpnNetworkCallback,
            )
            networkCallbackRegistered = true
        }
    }

    private fun unregisterVpnMonitor() {
        if (!networkCallbackRegistered) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { connectivity.unregisterNetworkCallback(vpnNetworkCallback) }
        networkCallbackRegistered = false
    }

    private fun updateVpnWarningSoon() = runOnUiThread { refreshVpnWarning() }

    private fun openVpnSettings() {
        val actions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(Settings.ACTION_VPN_SETTINGS)
            add(Settings.ACTION_WIRELESS_SETTINGS)
            add(Settings.ACTION_SETTINGS)
        }
        val intent = actions
            .asSequence()
            .map(::Intent)
            .firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, "Abre la aplicación de tu VPN para conectarla", Toast.LENGTH_LONG).show()
    }

    private fun refreshChannels() {
        val all = playlist?.channels.orEmpty()
        val orderedAll = HomeChannelRanker.rank(
            channels = all,
            pinnedIds = preferences.pinnedChannelOrder,
            favoriteIds = emptySet(),
            watchCounts = emptyMap(),
            limit = all.size.coerceAtLeast(1),
            smart = false,
        )
        val byGroup = when (selectedGroup) {
            "Todos" -> orderedAll
            "Favoritos" -> orderedAll.filter { it.id in preferences.favorites }
            else -> orderedAll.filter { it.group == selectedGroup }
        }
        val filtered = if (query.isBlank()) byGroup else byGroup.filter {
            it.name.contains(query, true) || it.group.contains(query, true)
        }
        val numbers = orderedAll.mapIndexed { position, channel -> channel.id to position + 1 }.toMap()
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
                showCatalog("Todos")
            }
            .setPositiveButton("Buscar") { _, _ ->
                showCatalog("Todos")
                query = input.text.toString().trim()
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
                if (showingCatalog) {
                    showHome()
                    return
                }
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
        val sources = repository.listPlaylistSources()
        val activeId = repository.activePlaylistSource()?.id
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        lateinit var dialog: AlertDialog
        sources.forEach { source ->
            val type = when (source.type) {
                PlaylistSourceType.M3U -> "M3U"
                PlaylistSourceType.XTREAM -> "Xtream"
                PlaylistSourceType.FILE -> "Archivo"
            }
            val selected = source.id == activeId
            container.addView(action("${if (selected) "✓" else "○"}  ${source.name}  ·  $type${if (selected) "  ·  ACTIVA" else ""}").apply {
                setOnClickListener {
                    dialog.dismiss()
                    activatePlaylistSource(source.id)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(8) })
        }
        if (sources.isEmpty()) {
            container.addView(label("Todavía no hay listas. Añade la principal o una alternativa.", 17f, secondary = true))
        }
        container.addView(action("＋ Añadir lista M3U").apply { setOnClickListener { dialog.dismiss(); showAddM3uDialog() } }, sourceRowParams())
        container.addView(action("＋ Añadir con Xtream Login").apply { setOnClickListener { dialog.dismiss(); showXtreamDialog() } }, sourceRowParams())
        container.addView(action("＋ Importar archivo M3U").apply { setOnClickListener { dialog.dismiss(); openPlaylistFile() } }, sourceRowParams())
        if (activeId != null) {
            container.addView(action("↻ Actualizar solo la lista seleccionada").apply {
                setOnClickListener { dialog.dismiss(); refreshSelectedPlaylist() }
            }, sourceRowParams())
            container.addView(action("Eliminar la lista seleccionada").apply {
                setOnClickListener { dialog.dismiss(); confirmDeleteActiveSource() }
            }, sourceRowParams())
        }
        container.addView(action("Configurar guía XMLTV").apply { setOnClickListener { dialog.dismiss(); showEpgDialog() } }, sourceRowParams())
        val scroll = ScrollView(this).apply { addView(container) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Mis listas")
            .setMessage("Solo se cargan los canales de la lista marcada como activa.")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
    }

    private fun showMobileControlDialog() {
        LocalControlRuntime.start(this)
        val url = LocalControlRuntime.mobileUrl(this)
        if (url == null || !LocalControlRuntime.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle("Control desde el móvil")
                .setMessage("Conecta la televisión a la red Wi‑Fi o por cable y vuelve a intentarlo.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(10), dp(28), 0)
        }
        container.addView(label("1. Conecta el móvil a la misma Wi‑Fi.\n2. Abre la cámara y escanea el código.\n3. El móvil recordará esta televisión.", 17f, secondary = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        if (isVpnActive()) {
            val vpnHelp = if (packageManager.getInstalledApplications(0).any { it.packageName == "ch.protonvpn.android" }) {
                "Proton VPN detectada: activa Ajustes → LAN con Proton Plus, o desconéctala solo mientras cambias las listas."
            } else {
                "VPN detectada: activa «Permitir conexiones LAN» en sus ajustes y vuelve a conectarla."
            }
            container.addView(label(vpnHelp, 14f).apply {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.accent))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        container.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap(url, dp(270)))
            contentDescription = "Código QR para abrir el control móvil"
        }, LinearLayout.LayoutParams(dp(286), dp(286)))
        container.addView(label("Código: ${LocalControlRuntime.accessCode(this)}", 25f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .14f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        container.addView(label(url.substringBefore("?k="), 14f, secondary = true).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        AlertDialog.Builder(this)
            .setTitle("Control desde el móvil")
            .setView(container)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xff05090d.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun registerPlaylistChangeReceiver() {
        if (playlistChangeReceiverRegistered) return
        val filter = IntentFilter(LocalControlServer.ACTION_PLAYLISTS_CHANGED)
        ContextCompat.registerReceiver(this, playlistChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        playlistChangeReceiverRegistered = true
    }

    private fun unregisterPlaylistChangeReceiver() {
        if (!playlistChangeReceiverRegistered) return
        runCatching { unregisterReceiver(playlistChangeReceiver) }
        playlistChangeReceiverRegistered = false
    }

    private fun showAddM3uDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val name = EditText(this).apply { hint = "Nombre (Principal, Alternativa…)"; setSingleLine(true) }
        val url = EditText(this).apply { hint = "URL de lista M3U"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        container.addView(name, sourceRowParams(58)); container.addView(url, sourceRowParams(58))
        AlertDialog.Builder(this).setTitle("Añadir lista M3U").setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Añadir y seleccionar") { _, _ ->
                val source = url.text.toString().trim()
                if (source.isBlank()) Toast.makeText(this, "Introduce la URL de la lista", Toast.LENGTH_LONG).show()
                else importPlaylist(name.text.toString().trim().ifBlank { "Mi lista" }, source)
            }.show()
    }

    private fun showXtreamDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val name = EditText(this).apply { hint = "Nombre (Principal, Alternativa…)"; setSingleLine(true) }
        val server = EditText(this).apply { hint = "Servidor (https://proveedor.example:puerto)"; setSingleLine(true) }
        val username = EditText(this).apply { hint = "Usuario"; setSingleLine(true) }
        val password = EditText(this).apply {
            hint = "Contraseña"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(server, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(username, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        container.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        AlertDialog.Builder(this)
            .setTitle("Xtream Codes")
            .setMessage("OpenStream TV importará únicamente los canales en directo de tu cuenta.")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                importXtream(name.text.toString().trim().ifBlank { "Xtream" }, server.text.toString().trim(), username.text.toString().trim(), password.text.toString())
            }
            .show()
    }

    private fun sourceRowParams(height: Int = 54) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(6) }

    private fun showEpgDialog() {
        val input = EditText(this).apply { hint = "URL XMLTV"; setText(preferences.epgUrl); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Guía de programación").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Actualizar") { _, _ ->
                val source = input.text.toString().trim()
                preferences.epgUrl = source
                if (source.isNotBlank()) importEpg(source)
            }.show()
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

    private fun importPlaylist(name: String, source: String) {
        setLoading(true, "Actualizando lista…")
        repository.importPlaylist(name, source) { result -> runOnUiThread { handlePlaylistResult(result) } }
    }

    private fun importPlaylist(uri: Uri) {
        setLoading(true, "Importando archivo…")
        repository.importPlaylist(uri) { result -> runOnUiThread { handlePlaylistResult(result) } }
    }

    private fun handlePlaylistResult(result: Result<Playlist>) {
        setLoading(false)
        result.onSuccess {
            playlist = it
            refresh()
            showHome()
            Toast.makeText(this, "Lista actualizada", Toast.LENGTH_SHORT).show()
        }
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

    private fun importXtream(name: String, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Completa servidor, usuario y contraseña", Toast.LENGTH_LONG).show()
            return
        }
        setLoading(true, "Conectando con Xtream…")
        repository.importXtream(name, server, username, password) { result -> runOnUiThread {
            setLoading(false)
            result.onSuccess {
                playlist = it
                selectedGroup = "Todos"
                refresh()
                showHome()
                Toast.makeText(this, "Xtream: ${it.channels.size} canales importados", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this, "No se pudo conectar con Xtream: ${it.message}", Toast.LENGTH_LONG).show()
            }
        } }
    }

    private fun activatePlaylistSource(id: String) {
        setLoading(true, "Cambiando de lista…")
        repository.activatePlaylistSource(id) { result -> runOnUiThread {
            setLoading(false)
            result.onSuccess {
                playlist = it
                selectedGroup = "Todos"
                refresh(); showHome()
                Toast.makeText(this, "Lista activa: ${it.name}", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "No se pudo seleccionar: ${it.message}", Toast.LENGTH_LONG).show() }
        } }
    }

    private fun refreshSelectedPlaylist() {
        setLoading(true, "Actualizando lista seleccionada…")
        if (!repository.refreshActivePlaylist { result -> runOnUiThread { handlePlaylistResult(result) } }) {
            setLoading(false)
            Toast.makeText(this, "Esta lista no tiene una fuente actualizable", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteActiveSource() {
        val active = repository.activePlaylistSource() ?: return
        AlertDialog.Builder(this).setTitle("Eliminar ${active.name}")
            .setMessage("Se eliminará esta lista y su copia local. Las demás listas no se modificarán.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                repository.deletePlaylistSource(active.id) { result -> runOnUiThread {
                    result.onSuccess {
                        playlist = it
                        selectedGroup = "Todos"
                        refresh(); showHome()
                        Toast.makeText(this, "Lista eliminada", Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(this, "No se pudo eliminar: ${it.message}", Toast.LENGTH_LONG).show() }
                } }
            }.show()
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        progress.visibility = if (loading) ProgressBar.VISIBLE else ProgressBar.GONE
        if (loading) status.text = message else refresh()
    }

    private fun openDeviceLauncher() {
        val launcherFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val launcherHome = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage("com.hisight.mslauncher")
            .addFlags(launcherFlags)
        val launcherMain = Intent().setComponent(
            ComponentName("com.hisight.mslauncher", "com.hisight.mslauncher.MsMainActivity"),
        ).addFlags(launcherFlags)

        runCatching { startActivity(launcherMain) }
            .recoverCatching { startActivity(launcherHome) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
    }

    override fun onDestroy() { repository.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_STARTED_FROM_BOOT = "started_from_boot"
        private const val REQUEST_PLAYLIST = 40
    }
}
