package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.runtime.ClaudeAuthState
import com.jarves.mh.runtime.ClaudeAuthStatusState
import com.jarves.mh.ui.theme.PocketOrange
import com.jarves.mh.ui.theme.glass.LiquidGlassCard
import com.jarves.mh.ui.theme.glass.LiquidGlassMaterial

/**
 * "Sign in with Claude" — signs the user's own Claude.ai account into the Claude Code CLI
 * through the CLI's official OAuth flow. The app only shows state; it never sees tokens.
 */
@Composable
internal fun ClaudeAccountCard(
    auth: ClaudeAuthState,
    claudeInstalled: Boolean,
    busy: Boolean,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    // Deliberately not rememberSaveable: the pasted code is a secret and must not be written to saved state.
    var code by remember { mutableStateOf("") }

    LiquidGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        material = LiquidGlassMaterial.UltraThin,
        layerSource = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Claude account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            when (auth.status) {
                ClaudeAuthStatusState.SIGNED_IN -> {
                    Text(auth.displayStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Text(
                        "Claude Code uses your subscription directly. No API key or token is stored by the app.",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClaudeCardButton("Refresh", onClick = onRefresh, primary = false, modifier = Modifier.weight(1f))
                        ClaudeCardButton("Sign out", onClick = onSignOut, primary = false, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                }

                ClaudeAuthStatusState.STARTING, ClaudeAuthStatusState.VERIFYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(auth.message ?: auth.displayStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ClaudeCardButton("Cancel", onClick = onCancel, primary = false)
                }

                ClaudeAuthStatusState.AWAITING_AUTH -> {
                    Text(
                        auth.message ?: "Finish signing in on Claude.ai, or paste the code below.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    auth.authorizationUrl?.let { url ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClaudeCardButton("Open browser", onClick = { uriHandler.openUri(url) }, primary = true, modifier = Modifier.weight(1f))
                            ClaudeCardButton("Copy link", onClick = { clipboard.setText(AnnotatedString(url)) }, primary = false, modifier = Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Code from Claude (if shown)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClaudeCardButton(
                            "Submit code",
                            onClick = {
                                onSubmitCode(code)
                                code = ""
                            },
                            primary = true,
                            enabled = code.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        )
                        ClaudeCardButton("Cancel", onClick = onCancel, primary = false, modifier = Modifier.weight(1f))
                    }
                }

                ClaudeAuthStatusState.EXPIRED -> {
                    Text(auth.displayStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    ClaudeCardButton("Sign in again", onClick = onSignIn, primary = true, enabled = !busy)
                }

                ClaudeAuthStatusState.ERROR -> {
                    Text(auth.message ?: "Sign-in did not complete.", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.error)
                    ClaudeCardButton("Try again", onClick = onSignIn, primary = true, enabled = !busy)
                }

                ClaudeAuthStatusState.SIGNED_OUT -> {
                    Text(
                        "Use your Claude Pro, Max, Team or Enterprise account. You'll approve the sign-in in your browser; no second device needed.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ClaudeCardButton(
                        if (claudeInstalled) "Sign in with Claude" else "Install & sign in",
                        onClick = onSignIn,
                        primary = true,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaudeCardButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sized = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = sized, shape = RoundedCornerShape(12.dp)) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = sized, shape = RoundedCornerShape(12.dp)) { Text(text) }
    }
}
