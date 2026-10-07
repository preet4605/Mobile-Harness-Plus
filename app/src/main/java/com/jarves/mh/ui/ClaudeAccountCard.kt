package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jarves.mh.runtime.ClaudeAuthState
import com.jarves.mh.runtime.ClaudeAuthStatusState
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing

/**
 * "Sign in with Claude": signs the user's own Claude.ai account into the Claude Code CLI
 * through the CLI's official OAuth flow, as a grouped section. The app only shows state; it
 * never sees tokens.
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
    val colors = PocketColors.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val banner = rememberBanner()
    // Deliberately not rememberSaveable: the pasted code is a secret and must not be written to saved state.
    var code by remember { mutableStateOf("") }
    val accent = MaterialTheme.colorScheme.primary

    val footer = when (auth.status) {
        ClaudeAuthStatusState.SIGNED_IN -> "Claude Code uses your subscription directly. The app stores no API key or token."
        ClaudeAuthStatusState.AWAITING_AUTH -> auth.message ?: "Finish signing in on Claude.ai, or paste the code it shows."
        ClaudeAuthStatusState.ERROR -> auth.message ?: "Sign-in did not complete."
        ClaudeAuthStatusState.SIGNED_OUT ->
            "Use your Claude Pro, Max, Team or Enterprise account. You approve the sign-in in your browser; no second device needed."
        else -> null
    }

    ListSection(modifier = modifier, header = "Claude account", footer = footer) {
        when (auth.status) {
            ClaudeAuthStatusState.SIGNED_IN -> {
                ListRow(auth.displayStatus, icon = Icons.Outlined.CheckCircle, iconTile = colors.green)
                ListRow("Refresh status", icon = Icons.Outlined.Refresh, iconTile = colors.gray, onClick = onRefresh)
                ListRow("Sign out", destructive = true, enabled = !busy, onClick = onSignOut)
            }

            ClaudeAuthStatusState.STARTING, ClaudeAuthStatusState.VERIFYING -> {
                ListRow(
                    auth.message ?: auth.displayStatus,
                    leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                )
                ListRow("Cancel", titleColor = accent, onClick = onCancel)
            }

            ClaudeAuthStatusState.AWAITING_AUTH -> {
                auth.authorizationUrl?.let { url ->
                    ListRow(
                        "Open sign-in page",
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        iconTile = accent,
                        onClick = { uriHandler.openUri(url) },
                    )
                    ListRow(
                        "Copy sign-in link",
                        icon = Icons.Outlined.ContentCopy,
                        iconTile = colors.gray,
                        onClick = {
                            clipboard.setText(AnnotatedString(url))
                            banner("Sign-in link copied", BannerKind.Success)
                        },
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(ListInset),
                    verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
                ) {
                    PocketTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = "Code from Claude (if shown)",
                        secure = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    PocketButton(
                        "Submit code",
                        {
                            onSubmitCode(code)
                            code = ""
                        },
                        style = PocketButtonStyle.Filled,
                        enabled = code.isNotBlank(),
                        fullWidth = true,
                    )
                }
                ListRow("Cancel", titleColor = accent, onClick = onCancel)
            }

            ClaudeAuthStatusState.EXPIRED -> {
                ListRow(auth.displayStatus, icon = Icons.Outlined.Warning, iconTile = colors.orange)
                ListRow("Sign in again", titleColor = accent, enabled = !busy, onClick = onSignIn)
            }

            ClaudeAuthStatusState.ERROR -> {
                ListRow("Sign-in failed", icon = Icons.Outlined.Warning, iconTile = colors.red)
                ListRow("Try again", titleColor = accent, enabled = !busy, onClick = onSignIn)
            }

            ClaudeAuthStatusState.SIGNED_OUT -> {
                ListRow(
                    if (claudeInstalled) "Sign in with Claude" else "Install and sign in",
                    icon = Icons.Outlined.Person,
                    iconTile = colors.orange,
                    titleColor = accent,
                    enabled = !busy,
                    onClick = onSignIn,
                )
            }
        }
    }
}
