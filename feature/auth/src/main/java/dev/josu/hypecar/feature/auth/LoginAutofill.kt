@file:OptIn(ExperimentalComposeUiApi::class)

package dev.josu.hypecar.feature.auth

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

internal enum class LoginAutofillField(
    val autofillTypes: List<AutofillType>,
    val keyboardOptions: KeyboardOptions,
) {
    Username(
        autofillTypes = listOf(AutofillType.Username, AutofillType.EmailAddress),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
    ),
    Password(
        autofillTypes = listOf(AutofillType.Password),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    ),
}

@Composable
internal fun Modifier.loginAutofill(
    field: LoginAutofillField,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val currentOnFill by rememberUpdatedState(onFill)
    val autofillNode = remember(field) {
        AutofillNode(
            autofillTypes = field.autofillTypes,
            onFill = { currentOnFill(it) },
        )
    }

    DisposableEffect(autofillTree, autofillNode) {
        autofillTree += autofillNode
        onDispose {
            autofillTree.children.remove(autofillNode.id)
        }
    }

    return this
        .onGloballyPositioned { coordinates ->
            autofillNode.boundingBox = coordinates.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                autofill?.requestAutofillForNode(autofillNode)
            } else {
                autofill?.cancelAutofillForNode(autofillNode)
            }
        }
}
