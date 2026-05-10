package dev.josu.hypecar.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.network.toApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(
        usernameOrEmail: String,
        password: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)
            val result = authRepository.login(usernameOrEmail, password)
            _uiState.value = if (result.isSuccess) {
                onSuccess()
                LoginUiState()
            } else {
                LoginUiState(
                    error = result.exceptionOrNull()
                        ?.toApiError(loginAttempt = true)
                        ?.message
                        ?: "Login failed.",
                )
            }
        }
    }

    fun dismissError() {
        if (_uiState.value.error != null) {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }
}
