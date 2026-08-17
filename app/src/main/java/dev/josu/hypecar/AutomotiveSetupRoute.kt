package dev.josu.hypecar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.ui.hypeTokens
import dev.josu.hypecar.feature.auth.LoginRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed interface AutomotiveSetupState {
    data object Loading : AutomotiveSetupState

    data object SignedOut : AutomotiveSetupState

    data class SignedIn(val username: String) : AutomotiveSetupState
}

@HiltViewModel
internal class AutomotiveSetupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val state = authRepository.session
        .map { session ->
            if (session == null) AutomotiveSetupState.SignedOut else AutomotiveSetupState.SignedIn(session.username)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AutomotiveSetupState.Loading,
        )

    fun signOut() {
        viewModelScope.launch { authRepository.logout() }
    }
}

@Composable
internal fun AutomotiveSetupApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    SystemBarIconAppearance(lightBackground = backStackEntry?.destination?.route == "login")

    NavHost(
        navController = navController,
        startDestination = "car-setup",
    ) {
        composable("car-setup") {
            AutomotiveSetupRoute(
                onSignIn = { navController.navigate("login") { launchSingleTop = true } },
                onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
            )
        }
        composable("login") {
            LoginRoute(onLoggedIn = { navController.popBackStack() })
        }
        composable("settings") {
            OfflineSettingsRoute(compactMode = true)
        }
    }
}

@Composable
internal fun AutomotiveSetupRoute(
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AutomotiveSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutomotiveSetupScreen(
        state = state,
        onSignIn = onSignIn,
        onOpenSettings = onOpenSettings,
        onSignOut = viewModel::signOut,
    )
}

@Composable
internal fun AutomotiveSetupScreen(
    state: AutomotiveSetupState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0809))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.car_setup_title),
                modifier = Modifier.semantics { heading() },
                color = Color.White,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
            )
            Text(
                text = stringResource(R.string.car_setup_subtitle),
                color = hypeTokens.brand.primary,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.car_setup_body),
                color = Color(0xFFD0C6C0),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))

            when (state) {
                AutomotiveSetupState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = hypeTokens.brand.primary,
                    )
                    Text(
                        text = stringResource(R.string.car_setup_loading),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp),
                        color = Color(0xFFB5AAA4),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                AutomotiveSetupState.SignedOut -> {
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.car_setup_sign_in))
                    }
                }

                is AutomotiveSetupState.SignedIn -> {
                    Text(
                        text = stringResource(R.string.car_setup_signed_in, state.username),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.car_setup_sign_out))
                    }
                }
            }

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.car_setup_settings))
            }
            Text(
                text = stringResource(R.string.car_setup_parked),
                modifier = Modifier.padding(top = 20.dp),
                color = Color(0xFF9E938D),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
