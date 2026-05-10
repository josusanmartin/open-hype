@file:OptIn(ExperimentalComposeUiApi::class)

package dev.josu.hypecar.feature.auth

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoginAutofillFieldTest {
    @Test
    fun `username field is exposed as a login username for autofill services`() {
        assertThat(LoginAutofillField.Username.autofillTypes).containsExactly(
            AutofillType.Username,
            AutofillType.EmailAddress,
        ).inOrder()
        assertThat(LoginAutofillField.Username.keyboardOptions).isEqualTo(
            KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
    }

    @Test
    fun `password field is exposed as a login password for autofill services`() {
        assertThat(LoginAutofillField.Password.autofillTypes).containsExactly(AutofillType.Password)
        assertThat(LoginAutofillField.Password.keyboardOptions).isEqualTo(
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )
    }
}
