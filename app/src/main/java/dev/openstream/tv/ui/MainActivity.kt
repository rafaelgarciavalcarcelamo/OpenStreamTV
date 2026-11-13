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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
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
import dev.openstream.tv.data.ChannelStabilityStore
import dev.openstream.tv.data.HomeChannelRanker
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.PlaylistSource
import dev.openstream.tv.data.PlaylistSourceType
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.Program
import dev.openstream.tv.data.StartupMode
import dev.openstream.tv.data.SportsEventArtwork
import dev.openstream.tv.data.SportsEventLookup
import dev.openstream.tv.data.SportsMetadataClient
import dev.openstream.tv.data.SportsMetadataStatus
import dev.openstream.tv.local.LocalControlRuntime
import dev.openstream.tv.local.LocalControlServer
import dev.openstream.tv.update.AppUpdateChecker
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var preferences: Preferences
    private lateinit var stabilityStore: ChannelStabilityStore
    private lateinit var sportsMetadataClient: SportsMetadataClient
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var manageGroupsButton: Button
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var watchButton: Button
    private lateinit var heroTitle: TextView
    private lateinit var heroEyebrow: TextView
    private lateinit var watchSubtitle: TextView
    private lateinit var heroImage: ImageView
    private lateinit var heroImageFallback: TextView
    private lateinit var heroMatchup: LinearLayout
    private lateinit var heroHomeLogo: ImageView
    private lateinit var heroHomeFallback: TextView
    private lateinit var heroHomeName: TextView
    private lateinit var heroAwayLogo: ImageView
    private lateinit var heroAwayFallback: TextView
    private lateinit var heroAwayName: TextView
    private lateinit var heroCompetition: TextView
    private lateinit var heroPosition: TextView
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
    private lateinit var homeGroupedSections: LinearLayout
    private lateinit var homeLiveAdapter: HomeChannelAdapter
    private lateinit var sideMenuOverlay: FrameLayout
    private lateinit var menuButton: Button
    private val homeGroupAdapters = linkedMapOf<String, HomeChannelAdapter>()
    private val homeChannelViewPool = RecyclerView.RecycledViewPool()
    private val heroHandler = Handler(Looper.getMainLooper())
    private var featuredEvents = emptyList<FeaturedEvent>()
    private var candidateFeaturedEvents = emptyList<FeaturedEvent>()
    private var sportsArtwork = emptyMap<String, SportsEventArtwork>()
    private var featuredEventIndex = 0
    private var showingDemoHero = false

    private var playlist: Playlist? = null
    private var programs = emptyList<Program>()
    private var indexedProgramsSource: List<Program>? = null
    private var indexedProgramsByChannel: Map<String, List<Program>> = emptyMap()
    private var guideChannelIdsByName = emptyMap<String, String>()
    private var guideLoaded = false
    private var guideLoadInFlight = false
    private var selectedGroup = "Todos"
    private var query = ""
    private var guideReleasedForMemory = false
    private var showingCatalog = false
    private var networkCallbackRegistered = false
    private var playlistChangeReceiverRegistered = false
    private val heroRotation = object : Runnable {
        override fun run() {
            if (!showingCatalog && featuredEvents.size > 1 && currentFocus != watchButton) {
                showFeaturedEvent((featuredEventIndex + 1) % featuredEvents.size)
            }
            heroHandler.postDelayed(this, HERO_ROTATION_MS)
        }
    }
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
        stabilityStore = ChannelStabilityStore(this)
        sportsMetadataClient = SportsMetadataClient(this)
        preferences.applyStableDefaults()
        preferences.applyTvExperienceDefaults()
        buildUi()
        configureBackHandling()
        playlist = repository.loadSavedPlaylist()
        loadGuideIfEnabled()
        if (playlist != null && preferences.startupMode == StartupMode.FAVORITOS) showCatalog("Favoritos") else refresh()
        if (playlist != null && savedInstanceState == null && preferences.startupMode == StartupMode.ULTIMO_CANAL) {
            visiblePlaylistChannels().firstOrNull { it.id == preferences.lastChannelId }?.let(::playChannel)
        }
        if (preferences.refreshSourcesOnLaunch) refreshSourcesSilently()
        AppUpdateChecker(this, preferences).check()
    }

    override fun onStart() {
        super.onStart()
        registerPlaylistChangeReceiver()
        playlist = repository.loadSavedPlaylist()
        syncGuideAvailability()
        registerVpnMonitor()
        refreshVpnWarning()
        refreshWatchButton()
        if (::homeLiveAdapter.isInitialized) refreshHome()
        if (::channelAdapter.isInitialized) refreshChannels()
        if (guideReleasedForMemory) {
            guideReleasedForMemory = false
            loadGuideIfEnabled()
        }
        heroHandler.removeCallbacks(heroRotation)
        heroHandler.postDelayed(heroRotation, HERO_ROTATION_MS)
    }

    override fun onStop() {
        heroHandler.removeCallbacks(heroRotation)
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
            indexedProgramsSource = null
            indexedProgramsByChannel = emptyMap()
            guideLoaded = false
            refreshChannels()
            guideReleasedForMemory = true
        }
    }

    private fun buildUi() {
        val compact = resources.configuration.screenHeightDp <= 600
        val screen = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(34), dp(if (compact) 8 else 12), dp(34), dp(if (compact) 8 else 12))
            clipChildren = false
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        menuButton = action("☰").apply {
            textSize = 21f
            contentDescription = "Abrir menú"
            background = getDrawable(R.drawable.top_nav_focus)
            setOnClickListener { openSideMenu() }
        }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(52), dp(if (compact) 38 else 44)).apply { rightMargin = dp(12) })
        val brandName = label("OPENSTREAM", if (compact) 17f else 20f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
            setTextColor(getColor(R.color.accent))
        }
        toolbar.addView(brandName, LinearLayout.LayoutParams(dp(if (compact) 142 else 172), dp(if (compact) 40 else 48)))

        val topNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        homeNav = topNavAction("Inicio") { showHome() }.apply { id = View.generateViewId() }
        channelsNav = topNavAction("Canales") { showCatalog("Todos") }
        favoritesNav = topNavAction("Favoritos") { showCatalog("Favoritos") }
        searchButton = topNavAction("Buscar") { showSearchDialog() }
        val sources = topNavAction("Mis listas") { showSourcesDialog() }
        for (button in listOf(homeNav, channelsNav, favoritesNav, searchButton, sources)) {
            topNav.addView(button, LinearLayout.LayoutParams(0, dp(if (compact) 38 else 44), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        toolbar.addView(topNav, LinearLayout.LayoutParams(0, dp(if (compact) 42 else 48), 1f).apply {
            leftMargin = dp(8)
            rightMargin = dp(14)
        })
        vpnWarning = action("⚠  VPN no detectada").apply {
            textSize = if (compact) 12f else 13f
            background = getDrawable(R.drawable.vpn_warning)
            setTextColor(getColorStateList(R.color.vpn_warning_text))
            visibility = View.GONE
            contentDescription = "VPN no detectada. Pulsa OK para abrir los ajustes de VPN"
            setOnClickListener { openVpnSettings() }
        }
        toolbar.addView(vpnWarning, LinearLayout.LayoutParams(dp(if (compact) 176 else 208), dp(if (compact) 36 else 40)).apply {
            rightMargin = dp(10)
        })
        toolbar.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm a"
            textSize = 15f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(if (compact) 76 else 94), dp(if (compact) 40 else 46)))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 46 else 56)))

        val homeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, dp(12), 0, dp(20))
        }
        homeScroll = ScrollView(this).apply {
            isFillViewport = true
            clipChildren = true
            clipToPadding = true
            addView(homeBody)
        }

        val hero = FrameLayout(this).apply {
            background = getDrawable(R.drawable.hero_card)
            clipChildren = false
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heroEyebrow = label("En directo", 13f).apply {
            setTextColor(getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
        }
        heroCopy.addView(heroEyebrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 26 else 30)))
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
            setOnClickListener { openCurrentHero() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || featuredEvents.size < 2) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        showFeaturedEvent(featuredEventIndex - 1); resetHeroRotation(); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        showFeaturedEvent(featuredEventIndex + 1); resetHeroRotation(); true
                    }
                    else -> false
                }
            }
        }
        val heroArtwork = FrameLayout(this).apply {
            background = getDrawable(R.drawable.matchup_backdrop)
            clipToOutline = true
        }
        heroImageFallback = label("TV", if (compact) 38f else 48f, secondary = true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            alpha = .55f
        }
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        heroArtwork.addView(heroImageFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        heroArtwork.addView(heroImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        heroMatchup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = getDrawable(R.drawable.matchup_backdrop)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun teamColumn(home: Boolean): LinearLayout {
            val fallback = label("TV", if (compact) 23f else 28f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = getDrawable(R.drawable.team_badge_fallback)
            }
            val logo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            val badge = FrameLayout(this).apply {
                addView(fallback, FrameLayout.LayoutParams(dp(if (compact) 58 else 72), dp(if (compact) 58 else 72), Gravity.CENTER))
                addView(logo, FrameLayout.LayoutParams(dp(if (compact) 58 else 72), dp(if (compact) 58 else 72), Gravity.CENTER))
            }
            val name = label("Equipo", if (compact) 10f else 12f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            if (home) {
                heroHomeLogo = logo; heroHomeFallback = fallback; heroHomeName = name
            } else {
                heroAwayLogo = logo; heroAwayFallback = fallback; heroAwayName = name
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 66 else 82)))
                addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 34 else 42)).apply { topMargin = dp(4) })
            }
        }
        val homeTeam = teamColumn(true)
        val competitionColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        competitionColumn.addView(label("⚽", if (compact) 29f else 36f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 48 else 60)))
        heroCompetition = label("PARTIDO", if (compact) 9f else 11f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        competitionColumn.addView(heroCompetition, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 50 else 62)))
        val awayTeam = teamColumn(false)
        heroMatchup.addView(homeTeam, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        heroMatchup.addView(competitionColumn, LinearLayout.LayoutParams(dp(if (compact) 78 else 98), ViewGroup.LayoutParams.MATCH_PARENT))
        heroMatchup.addView(awayTeam, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        heroArtwork.addView(heroMatchup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        heroArtwork.addView(View(this).apply { background = getDrawable(R.drawable.hero_scrim) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val heroActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        heroPosition = label("", 13f, secondary = true).apply { gravity = Gravity.CENTER }
        heroActions.addView(watchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 52 else 62)))
        heroActions.addView(heroPosition, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        hero.addView(heroArtwork, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(if (compact) 14 else 20), dp(28), dp(if (compact) 14 else 20))
        }
        heroContent.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(20) })
        heroContent.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (compact) .40f else .65f))
        heroContent.addView(heroActions, LinearLayout.LayoutParams(dp(if (compact) 214 else 250), ViewGroup.LayoutParams.MATCH_PARENT))
        hero.addView(heroContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        homeBody.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 196 else 250)).apply { bottomMargin = dp(12) })

        homeEmptyMessage = label("Añade tu lista de canales en «Mis listas» para empezar.", 19f, secondary = true).apply {
            gravity = Gravity.CENTER
        }
        homeBody.addView(homeEmptyMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)))
        homeLiveAdapter = HomeChannelAdapter(::playChannel, ::toggleFavorite)
        homeLiveSection = homeSection("Tus canales", homeLiveAdapter, "Ordenar") {
            startActivity(Intent(this, PersonalizationActivity::class.java))
        }
        homeGroupedSections = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        homeBody.addView(homeLiveSection)
        homeBody.addView(homeGroupedSections)
        homeNav.nextFocusDownId = watchButton.id
        watchButton.nextFocusUpId = homeNav.id

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
        manageGroupsButton = action("Gestionar categorías").apply {
            textSize = 14f
            setOnClickListener { showGroupVisibilityDialog() }
        }
        groupsPane.addView(manageGroupsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 40 else 46)).apply {
            setMargins(dp(3), dp(3), dp(3), dp(6))
        })
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
        groupAdapter = GroupAdapter(onOpen = { group ->
            selectedGroup = group
            updateNavSelection()
            refreshChannels()
            channelList.scrollToPosition(0)
            channelList.post {
                if (channelAdapter.itemCount > 0) channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                else if (emptyAction.visibility == View.VISIBLE) emptyAction.requestFocus()
                else searchButton.requestFocus()
            }
        }, onHide = ::confirmHideGroup)
        val groupsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = groupAdapter
            clipToPadding = false
            clipChildren = false
        }
        groupsPane.addView(groupsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        channelAdapter = ChannelAdapter(::playChannel, ::showChannelActions, opensChannelActions = true)
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
        root.addView(label("FLECHAS  Moverse     OK  Ver canal     MANTÉN OK  Favorito u ocultar", 13f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 20 else 28)).apply { topMargin = dp(if (compact) 4 else 7) })
        screen.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sideMenuOverlay = buildSideMenu()
        screen.addView(sideMenuOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(screen)
        updateNavSelection()
        watchButton.post { if (!showingCatalog && !isFinishing) watchButton.requestFocus() }
    }

    private fun topNavAction(text: String, onClick: () -> Unit): Button = action(text).apply {
        textSize = 15f
        background = getDrawable(R.drawable.top_nav_focus)
        setTextColor(getColorStateList(R.color.top_nav_text))
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun buildSideMenu(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            isFocusable = true
            background = getDrawable(R.drawable.side_menu_scrim)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.side_menu_panel)
            setPadding(dp(30), dp(30), dp(30), dp(26))
            clipChildren = false
        }
        panel.addView(label("OpenStream", 26f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accent))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        panel.addView(label("Más opciones", 15f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        val personalize = action("Personalizar inicio").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { closeSideMenu(); startActivity(Intent(this@MainActivity, PersonalizationActivity::class.java)) }
        }
        val apps = action("Ver aplicaciones").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { closeSideMenu(); openDeviceLauncher() }
        }
        val diagnostics = action("Diagnóstico automático").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { closeSideMenu(); startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) }
        }
        val settings = action("Ajustes").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { closeSideMenu(); startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val close = action("Cerrar menú").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { closeSideMenu() }
        }
        for (button in listOf(personalize, apps, diagnostics, settings, close)) {
            panel.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })
        }
        overlay.addView(panel, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        overlay.setOnClickListener { closeSideMenu() }
        panel.setOnClickListener { }
        overlay.tag = personalize
        return overlay
    }

    private fun openSideMenu() {
        sideMenuOverlay.visibility = View.VISIBLE
        (sideMenuOverlay.tag as? View)?.post { (sideMenuOverlay.tag as? View)?.requestFocus() }
    }

    private fun closeSideMenu() {
        sideMenuOverlay.visibility = View.GONE
        menuButton.requestFocus()
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
            if (adapter is HomeChannelAdapter) setRecycledViewPool(homeChannelViewPool)
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), 0, dp(16), 0)
            setHasFixedSize(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 186 else 208)))
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

    private fun visiblePlaylistChannels(): List<Channel> = preferences.visibleChannels(playlist)

    private fun showGroupVisibilityDialog() {
        val current = playlist ?: return
        val sourceId = current.sourceId ?: return
        val hidden = preferences.hiddenGroups(sourceId).toMutableSet()
        val groups = (current.channels.map(Channel::group) + hidden).distinct().sortedBy(String::lowercase)
        if (groups.isEmpty()) return
        val checked = BooleanArray(groups.size) { groups[it] !in hidden }
        AlertDialog.Builder(this)
            .setTitle("Categorías visibles")
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, index, visible ->
                if (visible) hidden.remove(groups[index]) else hidden.add(groups[index])
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ -> saveHiddenGroups(sourceId, hidden) }
            .show()
    }

    private fun confirmHideGroup(group: String) {
        val sourceId = playlist?.sourceId ?: return
        AlertDialog.Builder(this)
            .setTitle(group)
            .setMessage("Puedes recuperarla en Gestionar categorías.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ocultar categoría") { _, _ ->
                saveHiddenGroups(sourceId, preferences.hiddenGroups(sourceId) + group)
            }
            .show()
    }

    private fun saveHiddenGroups(sourceId: String, groups: Set<String>) {
        preferences.setHiddenGroups(sourceId, groups)
        refresh()
        if (showingCatalog && manageGroupsButton.visibility == View.VISIBLE) manageGroupsButton.requestFocus()
    }

    private fun refresh() {
        val channels = visiblePlaylistChannels()
        val favorites = preferences.favorites
        val groups = buildList {
            add("Todos" to channels.size)
            add("Favoritos" to channels.count { it.id in favorites })
            addAll(channels.groupingBy(Channel::group).eachCount().toList().sortedBy { it.first.lowercase() })
        }
        if (groups.none { it.first == selectedGroup }) selectedGroup = "Todos"
        updateNavSelection()
        groupAdapter.submit(groups, selectedGroup)
        manageGroupsButton.visibility = if (playlist?.sourceId != null) View.VISIBLE else View.GONE
        status.text = if (playlist == null) "COMIENZA AÑADIENDO UNA LISTA" else "${channels.size} CANALES DISPONIBLES"
        refreshWatchButton()
        refreshHome()
        refreshChannels()
    }

    private fun refreshHome() {
        val all = visiblePlaylistChannels()
        val favorites = preferences.favorites
        val newFeaturedEvents = buildFeaturedEvents(all)
        val currentKey = featuredEvents.getOrNull(featuredEventIndex)?.key
        candidateFeaturedEvents = newFeaturedEvents
        featuredEvents = newFeaturedEvents.filter { it.key in sportsArtwork }
        featuredEventIndex = featuredEvents.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: 0
        if (::heroPosition.isInitialized) showFeaturedEvent(featuredEventIndex)
        refreshSportsArtwork(newFeaturedEvents)
        val homeChannels = HomeChannelRanker.rank(
            channels = all,
            pinnedIds = preferences.pinnedChannelOrder,
            favoriteIds = favorites,
            watchCounts = preferences.watchCounts(),
            limit = preferences.homeChannelCount.coerceAtMost(HOME_CHANNEL_LIMIT),
            smart = preferences.smartHomeOrder,
        )
        val metadata = buildHomeMetadata(homeChannels)
        homeLiveAdapter.submit(homeChannels, favorites, metadata)
        refreshHomeGroupRows(all, favorites, metadata)
        homeEmptyMessage.text = if (playlist == null) "Añade tu lista de canales en «Mis listas» para empezar."
            else "No hay canales visibles. Puedes recuperarlos desde Canales o Personalizar inicio."
        homeEmptyMessage.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        homeLiveSection.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        homeGroupedSections.visibility = if (all.isEmpty() || preferences.homeVisibleGroups.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildFeaturedEvents(channels: List<Channel>): List<FeaturedEvent> {
        if (programs.isEmpty() || channels.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val limit = now + FEATURED_WINDOW_MS
        val programIds = programs.mapTo(HashSet(), Program::channelId)
        val activeDisplayNames = guideChannelIdsByName.entries
            .filter { (_, id) -> id in programIds }
            .associate { normalizeGuideName(it.key) to it.value }
        val channelsByGuideId = buildMap<String, MutableList<Channel>> {
            channels.forEach { channel ->
                val ids = linkedSetOf(channel.tvgId ?: channel.id)
                guideChannelIdsByName[channel.name]?.takeIf(programIds::contains)?.let(ids::add)
                activeDisplayNames[normalizeGuideName(channel.name)]?.let(ids::add)
                ids.filter(programIds::contains).forEach { id -> getOrPut(id) { mutableListOf() }.add(channel) }
            }
        }
        return programs.asSequence()
            .filter { it.endMillis > now && it.startMillis < limit && isFeaturedProgram(it) }
            .groupBy { normalizeEventTitle(it.title) + ":" + (it.startMillis / EVENT_DEDUPE_WINDOW_MS) }
            .mapNotNull { (key, matches) ->
                val program = matches.minByOrNull(Program::startMillis) ?: return@mapNotNull null
                val eventChannels = matches.flatMap { channelsByGuideId[it.channelId].orEmpty() }.distinctBy(Channel::id)
                if (eventChannels.isEmpty()) null else FeaturedEvent(key, program, eventChannels)
            }
            .sortedWith(compareBy<FeaturedEvent> { featuredPriority(it.program) }
                .thenBy { if (now in it.program.startMillis until it.program.endMillis) 0 else 1 }
                .thenBy { it.program.startMillis })
            .take(MAX_FEATURED_EVENTS)
            .toList()
    }

    private fun isFeaturedProgram(program: Program): Boolean {
        val title = program.title.lowercase()
        val category = program.category.orEmpty().lowercase()
        if (EXCLUDED_EVENT_TERMS.any(title::contains)) return false
        val looksLikeMatch = MATCH_MARKERS.any(title::contains)
        val sportsContext = SPORT_TERMS.any(title::contains) || SPORT_TERMS.any(category::contains)
        val recognizedTeams = TEAM_BADGE_IDS.keys.count(title::contains)
        return (looksLikeMatch && (sportsContext || recognizedTeams >= 2)) || FEATURED_SPORT_MARKERS.any(title::contains)
    }

    private fun featuredPriority(program: Program): Int {
        val title = program.title.lowercase()
        val context = "$title ${program.category.orEmpty()} ${program.description.orEmpty()}".lowercase()
        val namedFootballTeam = FEATURED_PRIORITIES.take(7).flatten().any(title::contains)
        val football = FOOTBALL_MARKERS.any(context::contains) ||
            (namedFootballTeam && OTHER_SPORT_MARKERS.none(context::contains))
        val teamPriority = if (football) FEATURED_PRIORITIES.indexOfFirst { terms -> terms.any(title::contains) } else -1
        return teamPriority.takeIf { it >= 0 } ?: (FEATURED_PRIORITIES.size + if (football) 0 else 10)
    }

    private fun normalizeEventTitle(value: String): String = value.lowercase()
        .replace(EVENT_QUALITY_REGEX, "")
        .replace(EVENT_SEPARATOR_REGEX, " ")
        .trim()

    private fun showFeaturedEvent(requestedIndex: Int) {
        if (featuredEvents.isEmpty()) {
            showFallbackHero()
            return
        }
        featuredEventIndex = ((requestedIndex % featuredEvents.size) + featuredEvents.size) % featuredEvents.size
        val event = featuredEvents[featuredEventIndex]
        showingDemoHero = false
        val artwork = sportsArtwork[event.key]
        val now = System.currentTimeMillis()
        val live = now in event.program.startMillis until event.program.endMillis
        heroEyebrow.text = if (live) "●  EN DIRECTO" else formatFeaturedDate(event.program.startMillis)
        heroTitle.text = artwork?.officialTitle ?: event.program.title
        watchSubtitle.text = buildString {
            (artwork?.league ?: event.program.category)?.takeIf(String::isNotBlank)?.let { append(it).append("  ·  ") }
            append(event.channels.size).append(if (event.channels.size == 1) " canal disponible" else " canales disponibles")
        }
        watchButton.text = "▶  Ver canal principal"
        heroPosition.text = "${featuredEventIndex + 1} / ${featuredEvents.size}"
        if (!artwork?.coverUrl.isNullOrBlank()) {
            heroMatchup.visibility = View.GONE
            heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
            heroImageFallback.text = "⚽"
            ChannelArtworkLoader.bindPriority(artwork?.coverUrl, heroImage, heroImageFallback)
        } else if (!bindMatchupArtwork(event.program, artwork)) {
            heroMatchup.visibility = View.GONE
            heroImageFallback.text = if (isSportText(event.program.title)) "⚽" else "TV"
            ChannelArtworkLoader.bind(event.program.iconUrl ?: event.channels.firstOrNull()?.logoUrl, heroImage, heroImageFallback)
        }
    }

    private fun refreshSportsArtwork(events: List<FeaturedEvent>) {
        if (events.isEmpty()) return
        sportsMetadataClient.enrich(events.map { event ->
            val match = MATCH_TITLE.find(event.program.title)
            val lookupTitle = if (match != null) {
                "${cleanTeamName(match.groupValues[1])} vs ${cleanTeamName(match.groupValues[2])}"
            } else event.program.title
            SportsEventLookup(event.key, lookupTitle, API_DATE_FORMAT.format(Date(event.program.startMillis)))
        }) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result.status) {
                    SportsMetadataStatus.VERIFIED -> result.artwork?.let {
                        sportsArtwork = sportsArtwork + (result.key to it)
                    }
                    SportsMetadataStatus.DATE_MISMATCH, SportsMetadataStatus.UNKNOWN ->
                        sportsArtwork = sportsArtwork - result.key
                }
                featuredEvents = candidateFeaturedEvents.filter { it.key in sportsArtwork }
                featuredEventIndex = featuredEventIndex.coerceIn(0, (featuredEvents.size - 1).coerceAtLeast(0))
                showFeaturedEvent(featuredEventIndex)
            }
        }
    }

    private fun bindMatchupArtwork(program: Program, artwork: SportsEventArtwork? = null): Boolean {
        val match = MATCH_TITLE.find(program.title) ?: return false
        val home = cleanTeamName(match.groupValues[1])
        val away = cleanTeamName(match.groupValues[2])
        if (home.length < 2 || away.length < 2) return false
        heroImage.visibility = View.GONE
        heroImageFallback.visibility = View.GONE
        heroMatchup.visibility = View.VISIBLE
        heroHomeName.text = home
        heroAwayName.text = away
        heroHomeFallback.text = teamInitials(home)
        heroAwayFallback.text = teamInitials(away)
        ChannelArtworkLoader.bindPriority(artwork?.homeBadgeUrl ?: teamBadgeUrl(home), heroHomeLogo, heroHomeFallback)
        ChannelArtworkLoader.bindPriority(artwork?.awayBadgeUrl ?: teamBadgeUrl(away), heroAwayLogo, heroAwayFallback)
        heroCompetition.text = artwork?.league?.uppercase()?.replace(" ", "\n")?.take(34) ?: competitionName(program)
        return true
    }

    private fun cleanTeamName(value: String): String = value.trim()
        .replace(Regex("^(ES|ESP|HD|FHD|UHD)\\s*[:|·-]\\s*", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun teamInitials(name: String): String = name.split(Regex("\\s+"))
        .filterNot { it.lowercase() in TEAM_STOP_WORDS }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifBlank { name.take(2).uppercase() }

    private fun teamBadgeUrl(name: String): String? {
        val normalized = normalizeGuideName(name)
        val id = TEAM_BADGE_IDS.entries.firstOrNull { (alias, _) ->
            val normalizedAlias = normalizeGuideName(alias)
            normalized == normalizedAlias || normalized.contains(normalizedAlias) || normalizedAlias.contains(normalized)
        }?.value ?: return null
        return "https://images.fotmob.com/image_resources/logo/teamlogo/$id.png"
    }

    private fun competitionName(program: Program): String {
        val title = program.title.lowercase()
        return when {
            "champions" in title || "liga de campeones" in title -> "UEFA\nCHAMPIONS\nLEAGUE"
            "europa league" in title -> "UEFA\nEUROPA LEAGUE"
            "conference" in title -> "UEFA\nCONFERENCE"
            "copa del rey" in title -> "COPA DEL REY"
            "laliga" in title || "liga ea sports" in title -> "LALIGA\nEA SPORTS"
            "premier" in title -> "PREMIER\nLEAGUE"
            "serie a" in title -> "SERIE A"
            "bundesliga" in title -> "BUNDESLIGA"
            else -> program.category?.takeIf(String::isNotBlank)?.uppercase()?.take(28) ?: "EN DIRECTO"
        }
    }

    private fun formatFeaturedDate(timeMillis: Long): String {
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val label = when {
            now.get(Calendar.YEAR) == date.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR) -> "HOY"
            now.get(Calendar.YEAR) == date.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) + 1 -> "MAÑANA"
            else -> FEATURED_DATE_FORMAT.format(Date(timeMillis)).uppercase()
        }
        return "$label  ·  ${EVENT_TIME_FORMAT.format(Date(timeMillis))}"
    }

    private fun openCurrentHero() {
        resetHeroRotation()
        if (showingDemoHero) {
            val candidates = defaultPrimaryChannels()
            candidates.firstOrNull()?.let { playChannelWithFallback(it, candidates) } ?: showCatalog("Todos")
            return
        }
        val event = featuredEvents.getOrNull(featuredEventIndex)
        if (event == null) {
            val visible = visiblePlaylistChannels()
            val channel = visible.firstOrNull { it.id == preferences.lastChannelId }
                ?: visible.firstOrNull()
            if (channel != null) playChannel(channel) else if (playlist != null) showCatalog("Todos") else showSourcesDialog()
            return
        }
        playChannelWithFallback(bestChannel(event.channels), event.channels)
    }

    private fun bestChannel(channels: List<Channel>): Channel = channels.minBy { channel ->
        val profile = stabilityStore.get(channel.id)
        val normalizedName = channel.name.lowercase()
        val secondaryPenalty = when {
            "repuesto" in normalizedName || "backup" in normalizedName -> 60
            Regex("(?:tv|liga)\\s*[2-9]").containsMatchIn(normalizedName) -> 25
            else -> 0
        }
        profile.consecutiveFailures * 100 + profile.rebufferCount * 20 +
            (if (profile.forceSoftwareVideo || profile.forceSoftwareAudio) 10 else 0) -
            (if (channel.name.contains("FHD", true)) 3 else if (channel.name.contains("HD", true)) 1 else 0) +
            secondaryPenalty
    }

    private fun defaultPrimaryChannels(): List<Channel> {
        val channels = visiblePlaylistChannels()
        val mainSports = channels.filter { channel ->
            val name = channel.name.lowercase()
            ("m+ laliga" in name || "movistar" in name && "laliga" in name) &&
                "repuesto" !in name && !Regex("(?:tv|liga)\\s*[2-9]").containsMatchIn(name)
        }
        val preferred = mainSports.takeIf(List<Channel>::isNotEmpty) ?: channels
        return preferred.sortedBy { channel ->
            val profile = stabilityStore.get(channel.id)
            profile.consecutiveFailures * 100 + profile.rebufferCount * 20 + if (profile.lastError.isNotBlank()) 40 else 0
        }
    }

    private fun showFeaturedChannels(event: FeaturedEvent) {
        heroHandler.removeCallbacks(heroRotation)
        val ordered = event.channels.sortedBy { channel ->
            val profile = stabilityStore.get(channel.id)
            profile.consecutiveFailures * 100 + profile.rebufferCount * 20
        }
        AlertDialog.Builder(this)
            .setTitle(event.program.title)
            .setMessage("Elige dónde verlo. La app coloca primero los canales más estables.")
            .setItems(ordered.map(Channel::name).toTypedArray()) { _, index -> playChannel(ordered[index]) }
            .setNegativeButton("Cerrar", null)
            .create()
            .apply {
                setOnDismissListener { resetHeroRotation() }
                show()
            }
    }

    private fun resetHeroRotation() {
        heroHandler.removeCallbacks(heroRotation)
        heroHandler.postDelayed(heroRotation, HERO_ROTATION_MS)
    }

    private fun showFallbackHero() {
        if (visiblePlaylistChannels().isNotEmpty()) {
            showDemoHero()
            return
        }
        showingDemoHero = false
        heroEyebrow.text = "En directo"
        heroTitle.text = if (playlist == null) "Tu tele empieza aquí" else "No hay canales visibles"
        watchButton.text = if (playlist == null) "Añadir una lista" else "Ir a Canales"
        watchSubtitle.text = if (playlist == null) "Añade tu lista y empieza a ver canales"
            else "Revisa las categorías o los canales ocultos."
        heroPosition.text = ""
        heroMatchup.visibility = View.GONE
        heroImage.scaleType = ImageView.ScaleType.FIT_CENTER
        heroImageFallback.text = "TV"
        ChannelArtworkLoader.bind(null, heroImage, heroImageFallback)
    }

    private fun showDemoHero() {
        showingDemoHero = true
        heroEyebrow.text = "HOY · ${CURRENT_DATE_FORMAT.format(Date()).uppercase()}"
        heroTitle.text = "Hoy en OpenStream"
        watchSubtitle.text = "No hay partidos destacados confirmados  ·  Televisión en directo"
        watchButton.text = "▶  Ver canal principal"
        heroPosition.text = "Programación verificada para hoy"
        heroMatchup.visibility = View.GONE
        heroImage.scaleType = ImageView.ScaleType.CENTER_CROP
        heroImageFallback.text = "⚽"
        ChannelArtworkLoader.bindPriority(DEMO_BANNER_URL, heroImage, heroImageFallback)
    }

    private fun refreshHomeGroupRows(
        channels: List<Channel>,
        favorites: Set<String>,
        metadata: Map<String, HomeChannelMetadata>,
    ) {
        val visibleGroups = preferences.homeVisibleGroups
        if (visibleGroups.isEmpty()) {
            homeGroupedSections.removeAllViews()
            homeGroupAdapters.clear()
            return
        }
        val savedOrder = preferences.homeGroupOrder.withIndex().associate { it.value to it.index }
        val grouped = channels.groupBy(Channel::group)
            .toList()
            .filter { (group, _) -> visibleGroups.isEmpty() || group in visibleGroups }
            .sortedWith(compareBy<Pair<String, List<Channel>>> { (group, _) -> savedOrder[group] ?: Int.MAX_VALUE }
                .thenBy { (group, _) -> group.lowercase() })
        val groupNames = grouped.map { it.first }
        if (homeGroupAdapters.keys.toList() != groupNames) {
            homeGroupedSections.removeAllViews()
            homeGroupAdapters.clear()
            grouped.forEach { (group, groupChannels) ->
                val adapter = HomeChannelAdapter(::playChannel, ::toggleFavorite)
                homeGroupAdapters[group] = adapter
                val section = homeSection(group.ifBlank { "Otros" }, adapter, "Ver todos") {
                    showCatalog(group)
                }.apply { visibility = View.VISIBLE }
                homeGroupedSections.addView(section)
                adapter.submit(groupChannels, favorites, metadata)
            }
        } else {
            grouped.forEach { (group, groupChannels) ->
                homeGroupAdapters[group]?.submit(groupChannels, favorites, metadata)
            }
        }
    }

    private fun buildHomeMetadata(channels: List<Channel>): Map<String, HomeChannelMetadata> {
        val now = System.currentTimeMillis()
        val byChannel = indexedPrograms()
        val normalizedGuideIds = byChannel.keys.associateBy(::normalizeGuideName)
        val normalizedDisplayNames = guideChannelIdsByName.entries
            .filter { (_, id) -> id in byChannel }
            .associate { normalizeGuideName(it.key) to it.value }
        return channels.associate { channel ->
            val exactGuide = byChannel[channel.tvgId ?: channel.id]
            val normalizedName = normalizeGuideName(channel.name)
            val displayNameId = guideChannelIdsByName[channel.name]?.takeIf(byChannel::containsKey)
                ?: normalizedDisplayNames[normalizedName]
            val fallbackId = displayNameId ?: normalizedGuideIds.entries.firstOrNull { (normalized, _) ->
                normalizedName.length >= 5 && normalized.length >= 5 &&
                    (normalizedName.contains(normalized) || normalized.contains(normalizedName))
            }?.value
            val schedule = (exactGuide ?: fallbackId?.let(byChannel::get)).orEmpty().sortedBy(Program::startMillis).let { guide ->
                val current = guide.firstOrNull { now in it.startMillis until it.endMillis }
                val next = guide.firstOrNull { it.startMillis >= (current?.endMillis ?: now) }
                when {
                    current != null && next != null -> "Ahora: ${current.title}\nDespués: ${next.title}"
                    current != null -> "Ahora: ${current.title}"
                    next != null -> "A las ${EVENT_TIME_FORMAT.format(Date(next.startMillis))}: ${next.title}"
                    else -> null
                }
            }
            val profile = stabilityStore.get(channel.id)
            val quality = when {
                profile.updatedAtMs == 0L -> HomeChannelMetadata(schedule, "Sin medir", 1)
                profile.consecutiveFailures >= 2 || profile.lastError.isNotBlank() -> HomeChannelMetadata(schedule, "Revisar", 0)
                profile.forceSoftwareVideo || profile.forceSoftwareAudio || profile.maxVideoHeight in 1..720 || profile.rebufferCount > 0 ->
                    HomeChannelMetadata(schedule, "Adaptada", 1)
                else -> HomeChannelMetadata(schedule, "Buena", 2)
            }
            channel.id to quality
        }
    }

    private fun normalizeGuideName(value: String): String = value.lowercase()
        .replace("repuesto", "")
        .replace("uhd", "")
        .replace("fhd", "")
        .replace("hd", "")
        .replace(Regex("[^a-z0-9áéíóúñ]+"), "")

    private fun todaySportEvents(channels: List<Channel>): List<Pair<Channel, Program>> {
        if (programs.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val end = calendar.timeInMillis
        val now = System.currentTimeMillis()
        val indexed = indexedPrograms()
        val programIds = indexed.keys
        val activeDisplayNames = guideChannelIdsByName.entries
            .filter { (_, id) -> id in programIds }
            .associate { normalizeGuideName(it.key) to it.value }
        val channelsByGuideId = buildMap {
            channels.forEach { channel ->
                put(channel.tvgId ?: channel.id, channel)
                val guideId = guideChannelIdsByName[channel.name]?.takeIf(programIds::contains)
                    ?: activeDisplayNames[normalizeGuideName(channel.name)]
                guideId?.let { put(it, channel) }
            }
        }
        return indexed.values.asSequence().flatten()
            .filter { it.startMillis < end && it.endMillis > maxOf(start, now) }
            .mapNotNull { program -> channelsByGuideId[program.channelId]?.let { it to program } }
            .filter { (channel, program) -> isSportText(channel.group) || isSportText(program.title) }
            .sortedBy { it.second.startMillis }
            .distinctBy { it.first.id }
            .take(30)
            .toList()
    }

    private fun indexedPrograms(): Map<String, List<Program>> {
        if (indexedProgramsSource !== programs) {
            indexedProgramsByChannel = programs.groupBy(Program::channelId)
                .mapValues { (_, values) -> values.sortedBy(Program::startMillis) }
            indexedProgramsSource = programs
        }
        return indexedProgramsByChannel
    }

    private fun isSportText(value: String): Boolean {
        val normalized = value.lowercase()
        return SPORT_TERMS.any(normalized::contains)
    }

    private fun refreshWatchButton() {
        if (!::watchButton.isInitialized) return
        if (featuredEvents.isNotEmpty()) showFeaturedEvent(featuredEventIndex) else showFallbackHero()
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
        val all = visiblePlaylistChannels()
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
            playlist == null -> "Todavía no hay canales. Añade tu lista para empezar."
            all.isEmpty() -> "No hay canales visibles. Revisa las categorías o los canales ocultos."
            selectedGroup == "Favoritos" -> "Aún no hay favoritos. Mantén OK sobre un canal para guardarlo."
            query.isNotBlank() -> "No encontramos ese canal. Prueba otra búsqueda."
            else -> "No hay canales aquí. Prueba otra categoría."
        }
        channelList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyPanel.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyAction.visibility = if (playlist == null) View.VISIBLE else View.GONE
    }

    private fun currentPrograms(): Map<String, Program> {
        val now = System.currentTimeMillis()
        val current = programs.asSequence().filter { now in it.startMillis until it.endMillis }.associateBy(Program::channelId)
        val activeDisplayNames = guideChannelIdsByName.entries
            .filter { (_, id) -> id in current }
            .associate { normalizeGuideName(it.key) to it.value }
        return buildMap {
            putAll(current)
            visiblePlaylistChannels().forEach { channel ->
                val guideId = guideChannelIdsByName[channel.name]?.takeIf(current::containsKey)
                    ?: activeDisplayNames[normalizeGuideName(channel.name)]
                    ?: return@forEach
                current[guideId]?.let { put(channel.tvgId ?: channel.id, it) }
            }
        }
    }

    private fun playChannel(channel: Channel) {
        if (channel.id in preferences.hiddenChannelIds || channel.group in preferences.hiddenGroups(playlist?.sourceId)) {
            Toast.makeText(this, "Este canal está oculto.", Toast.LENGTH_SHORT).show()
            return
        }
        if (preferences.rememberLastChannel) preferences.lastChannelId = channel.id
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id))
    }

    private fun playChannelWithFallback(channel: Channel, alternatives: List<Channel>) {
        val hidden = preferences.hiddenChannelIds
        val hiddenGroups = preferences.hiddenGroups(playlist?.sourceId)
        if (channel.id in hidden || channel.group in hiddenGroups) {
            Toast.makeText(this, "Este canal está oculto.", Toast.LENGTH_SHORT).show()
            return
        }
        if (preferences.rememberLastChannel) preferences.lastChannelId = channel.id
        val fallbackIds = (listOf(channel) + alternatives)
            .filterNot { it.id in hidden || it.group in hiddenGroups }
            .distinctBy(Channel::id)
            .take(6)
            .map(Channel::id)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putStringArrayListExtra(PlayerActivity.EXTRA_FALLBACK_CHANNEL_IDS, ArrayList(fallbackIds)),
        )
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
        val activeId = repository.activePlaylistSource()?.id
        repository.refreshActivePlaylist { result -> runOnUiThread {
            if (!isFinishing && !isDestroyed && repository.activePlaylistSource()?.id == activeId) {
                result.onSuccess { playlist = it; refresh() }
            }
        } }
        if (!preferences.epgEnabled) return
        val guideSource = preferences.epgUrl.ifBlank { repository.inferredEpgUrl().orEmpty() }
        if (guideSource.isNotBlank()) {
            importEpg(guideSource)
        }
    }

    private fun syncGuideAvailability() {
        if (!preferences.epgEnabled) {
            val hadGuide = programs.isNotEmpty() || guideChannelIdsByName.isNotEmpty()
            programs = emptyList()
            guideChannelIdsByName = emptyMap()
            indexedProgramsSource = null
            indexedProgramsByChannel = emptyMap()
            guideLoaded = false
            guideReleasedForMemory = false
            if (hadGuide && ::homeLiveAdapter.isInitialized) refresh()
        } else if (!guideLoaded) {
            loadGuideIfEnabled()
        }
    }

    private fun loadGuideIfEnabled() {
        if (!preferences.epgEnabled || guideLoadInFlight || guideLoaded) return
        guideLoadInFlight = true
        repository.loadSavedGuideAsync { loaded -> runOnUiThread {
            guideLoadInFlight = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (preferences.epgEnabled) {
                programs = loaded.programs
                guideChannelIdsByName = loaded.channelIdsByDisplayName
                guideLoaded = true
            } else {
                programs = emptyList()
                guideChannelIdsByName = emptyMap()
                indexedProgramsSource = null
                indexedProgramsByChannel = emptyMap()
                guideLoaded = false
            }
            refresh()
        } }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::sideMenuOverlay.isInitialized && sideMenuOverlay.visibility == View.VISIBLE) {
                    closeSideMenu()
                    return
                }
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

    private fun showChannelActions(channel: Channel) {
        val favorite = channel.id in preferences.favorites
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(arrayOf(if (favorite) "Quitar de favoritos" else "Añadir a favoritos", "Ocultar canal")) { _, which ->
                if (which == 0) toggleFavorite(channel) else hideChannel(channel)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hideChannel(channel: Channel) {
        preferences.hiddenChannelIds = preferences.hiddenChannelIds + channel.id
        preferences.favorites = preferences.favorites - channel.id
        if (preferences.lastChannelId == channel.id) preferences.lastChannelId = ""
        selectedGroup = "Todos"
        query = ""
        refresh()
        Toast.makeText(this, "${channel.name} oculto. Puedes restaurarlo desde Personalizar inicio.", Toast.LENGTH_LONG).show()
    }

    private fun showSourcesDialog() {
        val sources = repository.listPlaylistSources()
        val activeId = repository.activePlaylistSource()?.id
        val hiddenIds = repository.hiddenPlaylistSourceIds()
        val available = sources.filterNot { it.id in hiddenIds }
        val hidden = sources.filter { it.id in hiddenIds }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        lateinit var dialog: AlertDialog
        available.forEach { source ->
            val type = when (source.type) {
                PlaylistSourceType.M3U -> "M3U"
                PlaylistSourceType.XTREAM -> "Xtream"
                PlaylistSourceType.FILE -> "Archivo"
            }
            val selected = source.id == activeId
            val state = if (selected) "  ·  ACTIVA" else ""
            val row = action("${source.name}  ·  $type$state").apply {
                setOnClickListener {
                    dialog.dismiss()
                    showSourceActions(source, selected)
                }
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(8) })
        }
        if (available.isEmpty()) {
            container.addView(label("No hay listas disponibles. Añade una lista o recupera una desactivada.", 17f, secondary = true))
        }
        if (hidden.isNotEmpty()) {
            container.addView(action("Listas desactivadas (${hidden.size})").apply {
                setOnClickListener { dialog.dismiss(); showDisabledSources(hidden) }
            }, sourceRowParams())
        }
        container.addView(action("＋ Añadir lista M3U").apply { setOnClickListener { dialog.dismiss(); showAddM3uDialog() } }, sourceRowParams())
        container.addView(action("＋ Añadir con Xtream Login").apply { setOnClickListener { dialog.dismiss(); showXtreamDialog() } }, sourceRowParams())
        container.addView(action("＋ Importar archivo M3U").apply { setOnClickListener { dialog.dismiss(); openPlaylistFile() } }, sourceRowParams())
        container.addView(action("▣ Gestionar listas desde otro dispositivo").apply {
            setOnClickListener { dialog.dismiss(); showMobileControlDialog() }
        }, sourceRowParams())
        container.addView(action("Configurar guía XMLTV").apply { setOnClickListener { dialog.dismiss(); showEpgDialog() } }, sourceRowParams())
        val scroll = ScrollView(this).apply { addView(container) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Mis listas")
            .setMessage(if (activeId == null) "No hay ninguna lista activa." else "Pulsa OK sobre una lista para ver sus opciones.")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
    }

    private fun showSourceActions(source: PlaylistSource, selected: Boolean) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!selected) add("Usar esta lista" to { activatePlaylistSource(source.id) })
            if (selected && source.type != PlaylistSourceType.FILE && source.endpoint.isNotBlank()) {
                add("Actualizar lista" to { refreshSelectedPlaylist() })
            }
            add("Desactivar lista" to { setPlaylistSourceEnabled(source, false) })
            add("Eliminar lista…" to { confirmDeleteSource(source) })
        }
        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Volver") { _, _ -> showSourcesDialog() }
            .show()
    }

    private fun showDisabledSources(sources: List<PlaylistSource>) {
        AlertDialog.Builder(this)
            .setTitle("Listas desactivadas")
            .setItems(sources.map(PlaylistSource::name).toTypedArray()) { _, index ->
                val source = sources[index]
                AlertDialog.Builder(this)
                    .setTitle(source.name)
                    .setItems(arrayOf("Reactivar lista", "Eliminar lista…")) { _, option ->
                        if (option == 0) setPlaylistSourceEnabled(source, true) else confirmDeleteSource(source)
                    }
                    .setNegativeButton("Volver") { _, _ -> showDisabledSources(sources) }
                    .show()
            }
            .setNegativeButton("Volver") { _, _ -> showSourcesDialog() }
            .show()
    }

    private fun setPlaylistSourceEnabled(source: PlaylistSource, enabled: Boolean) {
        repository.setPlaylistSourceEnabled(source.id, enabled) { result -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            result.onSuccess {
                val current = repository.loadSavedPlaylist()
                if (playlist?.name != current?.name) selectedGroup = "Todos"
                playlist = current
                refresh()
                val status = if (enabled) "reactivada" else "desactivada"
                Toast.makeText(this, "${source.name} $status", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, it.message ?: "No se pudo cambiar el estado de la lista", Toast.LENGTH_LONG).show()
            }
            showSourcesDialog()
        } }
    }

    private fun showMobileControlDialog() {
        LocalControlRuntime.start(this)
        val url = LocalControlRuntime.mobileUrl(this)
        if (url == null || !LocalControlRuntime.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle("Gestionar listas desde otro dispositivo")
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
        container.addView(label("1. Conecta el móvil, tableta u ordenador a la misma red.\n2. Escanea el QR o escribe la dirección en el navegador.\n3. Si Android lo solicita, permite el acceso a la red local.", 17f, secondary = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)))
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
            .setTitle("Gestionar listas desde otro dispositivo")
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
                if (source.isNotBlank() && preferences.epgEnabled) importEpg(source)
                else if (!preferences.epgEnabled) Toast.makeText(this, "La guía está desactivada en Ajustes", Toast.LENGTH_SHORT).show()
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
            result.onSuccess {
                programs = it.programs
                guideChannelIdsByName = it.channelIdsByDisplayName
                indexedProgramsSource = null
                indexedProgramsByChannel = emptyMap()
                guideLoaded = true
                refresh()
                Toast.makeText(this, "Guía actualizada", Toast.LENGTH_SHORT).show()
            }
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

    private fun confirmDeleteSource(source: PlaylistSource) {
        AlertDialog.Builder(this).setTitle("Eliminar ${source.name}")
            .setMessage("Se eliminará esta lista y su copia local. Las demás listas no se modificarán.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                repository.deletePlaylistSource(source.id) { result -> runOnUiThread {
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

    override fun onDestroy() { repository.shutdown(); sportsMetadataClient.shutdown(); super.onDestroy() }

    private data class FeaturedEvent(
        val key: String,
        val program: Program,
        val channels: List<Channel>,
    )

    companion object {
        const val EXTRA_STARTED_FROM_BOOT = "started_from_boot"
        private const val REQUEST_PLAYLIST = 40
        private val EVENT_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val FEATURED_DATE_FORMAT = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        private val EVENT_QUALITY_REGEX = Regex("\\b(directo|live|hd|fhd|uhd|4k)\\b")
        private val EVENT_SEPARATOR_REGEX = Regex("[^a-z0-9áéíóúñ]+")
        private val API_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private const val HERO_ROTATION_MS = 9_000L
        private const val FEATURED_WINDOW_MS = 7 * 24 * 60 * 60 * 1_000L
        private const val EVENT_DEDUPE_WINDOW_MS = 30 * 60 * 1_000L
        private const val MAX_FEATURED_EVENTS = 10
        private const val HOME_CHANNEL_LIMIT = 12
        private const val DEMO_BANNER_URL = "https://r2.thesportsdb.com/images/media/league/fanart/6am8r81707716890.jpg"
        private val CURRENT_DATE_FORMAT = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))
        private val MATCH_MARKERS = listOf(" - ", " vs ", " vs. ", " v ")
        private val MATCH_TITLE = Regex("^(.+?)\\s+(?:-|vs\\.?|v)\\s+(.+?)(?:\\s+-\\s+.+)?$", RegexOption.IGNORE_CASE)
        private val TEAM_STOP_WORDS = setOf("cf", "fc", "club", "de", "del", "la", "el", "real")
        private val TEAM_BADGE_IDS = mapOf(
            "real madrid" to 8633, "barcelona" to 8634, "fc barcelona" to 8634,
            "atletico de madrid" to 9906, "athletic club" to 8315, "real betis" to 8603,
            "sevilla" to 8302, "real sociedad" to 8560, "villarreal" to 10205,
            "valencia" to 10267, "girona" to 7732, "celta" to 9910, "osasuna" to 8371,
            "rayo vallecano" to 8370, "mallorca" to 8661, "alaves" to 9866,
            "espanyol" to 8558, "getafe" to 8305, "levante" to 8581, "elche" to 10268,
            "manchester city" to 8456, "manchester united" to 10260, "liverpool" to 8650,
            "arsenal" to 9825, "chelsea" to 8455, "bayern munich" to 9823,
            "borussia dortmund" to 9789, "psg" to 9847, "paris saint germain" to 9847,
            "inter" to 8636, "ac milan" to 8564, "juventus" to 9885,
        )
        private val FEATURED_SPORT_MARKERS = listOf("gran premio", "formula 1", "fórmula 1", "motogp", "nba finals", "final four")
        private val FOOTBALL_MARKERS = listOf(
            "fútbol", "futbol", "laliga", "champions", "liga de campeones", "uefa", "europa league",
            "conference league", "copa del rey", "supercopa", "premier league", "serie a", "bundesliga",
        )
        private val OTHER_SPORT_MARKERS = listOf("asobal", "balonmano", "basket", "baloncesto", "acb", "euroliga", "nba")
        private val EXCLUDED_EVENT_TERMS = listOf(
            "resumen", "resúmenes", "highlights", "repetición", "repeticion", "documental", "magazine",
            "previa", "post partido", "postpartido", "rueda de prensa", "programa ", "noche de champions",
        )
        private val FEATURED_PRIORITIES = listOf(
            listOf("real madrid"),
            listOf("barcelona", "barça", "barca"),
            listOf("atlético de madrid", "atletico de madrid"),
            listOf("athletic club", "sevilla", "real betis", "real sociedad", "villarreal", "valencia cf"),
            listOf("selección española", "espana", "españa"),
            listOf("champions league", "liga de campeones"),
            listOf("manchester city", "liverpool", "arsenal", "chelsea", "bayern", "psg", "paris saint", "inter", "milan", "juventus"),
            listOf("formula 1", "fórmula 1", "motogp", "gran premio"),
        )
        private val SPORT_TERMS = listOf(
            "deport", "fútbol", "futbol", "laliga", "liga de campeones", "champions", "uefa",
            "dazn", "f1", "formula 1", "motor", "motogp", "tenis", "tennis", "golf", "nba",
            "baloncesto", "basket", "nfl", "eurosport", "partido", "gran premio",
        )
    }
}
