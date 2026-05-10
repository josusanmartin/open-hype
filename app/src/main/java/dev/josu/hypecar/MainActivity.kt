package dev.josu.hypecar

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import dev.josu.hypecar.feature.auth.LoginRoute
import dev.josu.hypecar.feature.catalog.LatestRoute
import dev.josu.hypecar.feature.catalog.PopularRoute
import dev.josu.hypecar.feature.details.BlogDetailRoute
import dev.josu.hypecar.feature.details.TagDetailRoute
import dev.josu.hypecar.feature.details.UserDetailRoute
import dev.josu.hypecar.feature.library.LibraryRoute
import dev.josu.hypecar.feature.player.PlayerRoute
import dev.josu.hypecar.feature.search.SearchRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            AppTheme {
                MainApp()
            }
        }
    }
}

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

internal data class AppChromeMetrics(
    val bottomNavHeight: Dp?,
    val bottomBarUsesExternalSystemBarPadding: Boolean,
    val miniPlayerArtSize: Dp,
    val miniPlayerRowHorizontalPadding: Dp,
    val miniPlayerRowVerticalPadding: Dp,
    val miniPlayerRowSpacing: Dp,
    val miniPlayerProgressHorizontalPadding: Dp,
    val miniPlayerProgressHeight: Dp,
    val miniPlayerBottomSpacer: Dp,
    val miniPlayerIconButtonSize: Dp,
    val miniPlayerIconSize: Dp,
) {
    companion object {
        fun automotive() = AppChromeMetrics(
            bottomNavHeight = 62.dp,
            bottomBarUsesExternalSystemBarPadding = true,
            miniPlayerArtSize = 36.dp,
            miniPlayerRowHorizontalPadding = 8.dp,
            miniPlayerRowVerticalPadding = 4.dp,
            miniPlayerRowSpacing = 8.dp,
            miniPlayerProgressHorizontalPadding = 8.dp,
            miniPlayerProgressHeight = 2.dp,
            miniPlayerBottomSpacer = 4.dp,
            miniPlayerIconButtonSize = 40.dp,
            miniPlayerIconSize = 22.dp,
        )

        fun phone() = AppChromeMetrics(
            bottomNavHeight = 72.dp,
            bottomBarUsesExternalSystemBarPadding = true,
            miniPlayerArtSize = 48.dp,
            miniPlayerRowHorizontalPadding = 10.dp,
            miniPlayerRowVerticalPadding = 6.dp,
            miniPlayerRowSpacing = 10.dp,
            miniPlayerProgressHorizontalPadding = 10.dp,
            miniPlayerProgressHeight = 3.dp,
            miniPlayerBottomSpacer = 6.dp,
            miniPlayerIconButtonSize = 42.dp,
            miniPlayerIconSize = 22.dp,
        )
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode
    val isAutomotive = remember(context, uiMode) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR ||
            Build.PRODUCT.contains("gcar", ignoreCase = true) ||
            Build.DEVICE.contains("car", ignoreCase = true) ||
            Build.FINGERPRINT.contains("gcar", ignoreCase = true)
    }
    val chromeMetrics = if (isAutomotive) AppChromeMetrics.automotive() else AppChromeMetrics.phone()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val chromeViewModel: AppChromeViewModel = hiltViewModel()
    val queue by chromeViewModel.queue.collectAsStateWithLifecycle()
    MediaNotificationPermissionGate(
        enabled = !isAutomotive && (queue.isPlaying || queue.current != null),
    )
    val currentRoute = backStackEntry?.destination?.route
    val destinations = listOf(
        NavDestinationItem("latest", stringResource(R.string.nav_latest)) {
            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(23.dp))
        },
        NavDestinationItem("popular", stringResource(R.string.nav_popular)) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(23.dp))
        },
        NavDestinationItem("library", stringResource(R.string.nav_library)) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(23.dp))
        },
        NavDestinationItem("search", stringResource(R.string.nav_search)) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(23.dp))
        },
        NavDestinationItem("settings", stringResource(R.string.nav_settings)) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(23.dp))
        },
    )
    val showBottomBar = currentRoute in destinations.map { it.route } && currentRoute != "player"
    val miniPlayer = if (currentRoute != "player" && showBottomBar) {
        MiniPlayerUiState.fromQueue(queue)
    } else {
        null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar || miniPlayer != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E0E0F))
                        .then(
                            if (chromeMetrics.bottomBarUsesExternalSystemBarPadding) {
                                Modifier.navigationBarsPadding()
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    miniPlayer?.let { miniPlayerState ->
                        val haptics = LocalHapticFeedback.current
                        val tick: () -> Unit = {
                            if (!isAutomotive) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        MiniPlayerBar(
                            uiState = miniPlayerState,
                            onOpenPlayer = { navController.navigate("player") },
                            onTogglePlayPause = {
                                tick()
                                chromeViewModel.togglePlayPause()
                            },
                            onSkipNext = {
                                tick()
                                chromeViewModel.skipNext()
                            },
                            onSkipPrevious = {
                                tick()
                                chromeViewModel.skipPrevious()
                            },
                            metrics = chromeMetrics,
                        )
                    }
                    if (showBottomBar) {
                        NavigationBar(
                            modifier = chromeMetrics.bottomNavHeight?.let { Modifier.height(it) } ?: Modifier,
                            containerColor = Color(0xFF0E0E0F),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        if (currentRoute == destination.route) {
                                            // Same tab tapped — ask the visible route to scroll to top.
                                            dev.josu.hypecar.core.model.ScrollToTopBus.request(destination.route)
                                        } else {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = destination.icon,
                                    label = {
                                        Text(
                                            destination.label,
                                            style = if (isAutomotive) {
                                                MaterialTheme.typography.labelLarge
                                            } else {
                                                MaterialTheme.typography.bodySmall
                                            },
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFFF8A3D),
                                        selectedTextColor = Color(0xFFFF8A3D),
                                        unselectedIconColor = Color(0xFF8C8986),
                                        unselectedTextColor = Color(0xFF8C8986),
                                        indicatorColor = Color.Transparent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "latest",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("latest") {
                LatestRoute(onBlogClick = { blogId -> navController.navigate("blog/$blogId") })
            }
            composable("popular") {
                PopularRoute(onBlogClick = { blogId -> navController.navigate("blog/$blogId") })
            }
            composable("library") {
                LibraryRoute(
                    onBlogClick = { blogId -> navController.navigate("blog/$blogId") },
                    onUserClick = { username -> navController.navigate("user/${android.net.Uri.encode(username)}") },
                    onLoginClick = { navController.navigate("login") },
                )
            }
            composable("search") {
                SearchRoute(
                    onTagClick = { tag -> navController.navigate("tag/${android.net.Uri.encode(tag)}") },
                    onBlogClick = { blogId -> navController.navigate("blog/$blogId") },
                )
            }
            composable("settings") {
                OfflineSettingsRoute(compactMode = isAutomotive)
            }
            composable("player") {
                PlayerRoute()
            }
            composable("login") {
                LoginRoute(onLoggedIn = { navController.popBackStack() })
            }
            composable(
                route = "blog/{blogId}",
                arguments = listOf(navArgument("blogId") { type = NavType.IntType }),
            ) {
                BlogDetailRoute(onBlogClick = { blogId -> navController.navigate("blog/$blogId") })
            }
            composable(
                route = "user/{username}",
                arguments = listOf(navArgument("username") { type = NavType.StringType }),
            ) {
                UserDetailRoute(onBlogClick = { blogId -> navController.navigate("blog/$blogId") })
            }
            composable(
                route = "tag/{tag}",
                arguments = listOf(navArgument("tag") { type = NavType.StringType }),
            ) {
                TagDetailRoute(onBlogClick = { blogId -> navController.navigate("blog/$blogId") })
            }
        }
    }
}

@Composable
private fun MediaNotificationPermissionGate(enabled: Boolean) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Media3 already owns the notification; once granted, the active session
        // notification becomes visible without rebuilding playback state.
    }

    LaunchedEffect(context, enabled) {
        if (!enabled) return@LaunchedEffect
        val grantState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (MediaNotificationPermissionPolicy.shouldRequest(Build.VERSION.SDK_INT, grantState)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
internal fun MiniPlayerBar(
    uiState: MiniPlayerUiState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    metrics: AppChromeMetrics,
) {
    val compactMode = metrics.bottomNavHeight != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF111112),
        contentColor = Color.White,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = metrics.miniPlayerRowHorizontalPadding,
                        vertical = metrics.miniPlayerRowVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.miniPlayerRowSpacing),
            ) {
                AsyncImage(
                    model = uiState.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(metrics.miniPlayerArtSize)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenPlayer),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPlayer),
                ) {
                    Text(
                        text = uiState.title,
                        style = if (compactMode) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                    )
                    Text(
                        text = uiState.artist,
                        style = if (compactMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFFD2D2D2),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSkipPrevious, modifier = Modifier.size(metrics.miniPlayerIconButtonSize)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.action_previous),
                            tint = Color.White,
                            modifier = Modifier.size(metrics.miniPlayerIconSize),
                        )
                    }
                    IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(metrics.miniPlayerIconButtonSize)) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (uiState.isPlaying) R.string.action_pause else R.string.action_play,
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(metrics.miniPlayerIconSize),
                        )
                    }
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(metrics.miniPlayerIconButtonSize)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.action_next),
                            tint = Color.White,
                            modifier = Modifier.size(metrics.miniPlayerIconSize),
                        )
                    }
                    IconButton(onClick = onOpenPlayer, modifier = Modifier.size(metrics.miniPlayerIconButtonSize)) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.action_open_player),
                            tint = Color.White,
                            modifier = Modifier.size(metrics.miniPlayerIconSize),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (compactMode) 2.dp else 4.dp))
            LinearProgressIndicator(
                progress = { uiState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.miniPlayerProgressHorizontalPadding)
                    .height(metrics.miniPlayerProgressHeight)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFFF8A3D),
                trackColor = Color(0xFF434346),
            )
            Spacer(modifier = Modifier.height(metrics.miniPlayerBottomSpacer))
        }
    }
}
