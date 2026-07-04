package dev.josu.hypecar.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.ui.errorLabel
import dev.josu.hypecar.core.ui.pressFeedback

@Composable
fun LoginRoute(
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val passwordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val canSubmit = !state.isLoading && username.isNotBlank() && password.isNotBlank()
    val onSubmit: () -> Unit = {
        if (canSubmit) {
            keyboardController?.hide()
            viewModel.login(username, password, onLoggedIn)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8F0E8)),
    ) {
        LoginBackgroundArt()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 54.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            LoginWaveformMark()
            Spacer(Modifier.height(74.dp))
            Text(
                text = stringResource(R.string.auth_screen_title),
                color = Color(0xFF101010),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = stringResource(R.string.auth_screen_blurb),
                color = Color(0xFF161311),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                ),
                modifier = Modifier.padding(top = 6.dp, bottom = 42.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.auth_field_username)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .loginAutofill(LoginAutofillField.Username) { username = it },
                singleLine = true,
                keyboardOptions = LoginAutofillField.Username.keyboardOptions,
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocusRequester.requestFocus() },
                ),
                shape = RoundedCornerShape(14.dp),
                colors = loginTextFieldColors(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_field_password)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocusRequester)
                    .loginAutofill(LoginAutofillField.Password) { password = it },
                singleLine = true,
                keyboardOptions = LoginAutofillField.Password.keyboardOptions
                    .copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmit() },
                    onDone = { onSubmit() },
                ),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        text = stringResource(
                            if (showPassword) R.string.auth_password_hide else R.string.auth_password_show,
                        ),
                        color = Color(0xFFD55A20),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .pressFeedback(pressedScale = 0.94f, label = "passwordVisibilityPress")
                            .clickable { showPassword = !showPassword }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
                shape = RoundedCornerShape(14.dp),
                colors = loginTextFieldColors(),
            )
            state.error?.let { kind ->
                Text(
                    text = when (kind) {
                        UiErrorKind.InvalidCredentials -> stringResource(R.string.auth_error_invalid_credentials)
                        UiErrorKind.Unknown -> stringResource(R.string.auth_error_generic)
                        else -> kind.errorLabel()
                    },
                    color = Color(0xFFB3261E),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .pressFeedback(enabled = canSubmit, pressedScale = 0.97f, label = "loginButtonPress")
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6A21),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFFFC7A8),
                    disabledContentColor = Color(0xFFFFF8F2),
                ),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.auth_button_login),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginWaveformMark() {
    Row(
        modifier = Modifier
            .padding(start = 2.dp)
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(18.dp, 32.dp, 46.dp, 28.dp, 16.dp).forEach { barHeight ->
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = barHeight)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFD55A20)),
            )
        }
    }
}

@Composable
private fun loginTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFF15100E),
        unfocusedTextColor = Color(0xFF15100E),
        focusedContainerColor = Color(0x00FFFFFF),
        unfocusedContainerColor = Color(0x00FFFFFF),
        focusedBorderColor = Color(0xFFD55A20),
        unfocusedBorderColor = Color(0xFF5F5A56),
        focusedLabelColor = Color(0xFFD55A20),
        unfocusedLabelColor = Color(0xFF6F6761),
        cursorColor = Color(0xFFD55A20),
    )

/**
 * Decorative background art for the login screen. The text inside the
 * circle ("offline / rotation / synced") is purely visual flair and is
 * marked `clearAndSetSemantics {}` so TalkBack doesn't read it out as if it
 * were meaningful UI copy.
 *
 * Offsets used to be in raw `x = 170.dp` form which doesn't mirror under
 * RTL — the circle stuck to the right edge of the screen in Arabic / Hebrew
 * regardless of layout direction. We now mirror the offsets when the layout
 * direction is RTL so the decorative orbs sit on the leading side.
 */
@Composable
private fun LoginBackgroundArt() {
    val isRtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val mirror = if (isRtl) -1 else 1
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .offset(x = (170 * mirror).dp, y = (-86).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0x8DFF7A2C),
                            Color(0x45F7A07A),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .offset(x = (248 * mirror).dp, y = 74.dp)
                .clip(CircleShape)
                .border(1.dp, Color(0x35FFFFFF), CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .align(Alignment.CenterEnd)
                    .offset(x = (-42).dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF)),
            )
            Text(
                text = stringResource(R.string.auth_watermark),
                color = Color(0x33FFFFFF),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    // Decorative flavour text — hide from screen readers so
                    // TalkBack doesn't announce "offline, rotation, synced"
                    // as if it were meaningful UI.
                    .clearAndSetSemantics { },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x66FFE0CA),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}
