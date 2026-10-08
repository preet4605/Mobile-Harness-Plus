package com.jarves.mh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jarves.mh.runtime.CodexAuthState
import com.jarves.mh.runtime.CodexAuthStatus
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType

/**
 * "Sign in with ChatGPT": signs the user's own ChatGPT account into the Codex CLI through the CLI's
 * official device-code flow, as a grouped section. The app only shows the link and one-time code;
 * it never sees tokens.
 */
@Composable
internal fun CodexAccountCard(
    auth: CodexAuthState,
    codexInstalled: Boolean,
    busy: Boolean,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PocketColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val banner = rememberBanner()
    val accent = MaterialTheme.colorScheme.primary

    val footer = when (auth.status) {
        CodexAuthStatus.SIGNED_IN -> "Codex uses your ChatGPT plan directly. The app stores no API key or token."
        CodexAuthStatus.AWAITING_AUTH ->
            "Open the page, sign in, and enter the code. Codes expire after 15 minutes. Never share this code with anyone."
        CodexAuthStatus.ERROR -> auth.message ?: "Sign-in did not complete."
        CodexAuthStatus.SIGNED_OUT ->
            auth.message?.takeIf { it.isNotBlank() && it != "Not signed in" }
                ?: "Use your ChatGPT Plus, Pro, Business or Enterprise account. You approve the sign-in on a web page, on this phone or another device."
        CodexAuthStatus.STARTING -> null
    }

    ListSection(modifier = modifier, header = "ChatGPT account", footer = footer) {
        when (auth.status) {
            CodexAuthStatus.SIGNED_IN -> {
                ListRow(auth.displayStatus, icon = Icons.Outlined.CheckCircle, iconTile = colors.green)
                ListRow("Refresh status", icon = Icons.Outlined.Refresh, iconTile = colors.gray, onClick = onRefresh)
                ListRow("Sign out", destructive = true, enabled = !busy, onClick = onSignOut)
            }

            CodexAuthStatus.STARTING -> {
                ListRow(
                    auth.displayStatus,
                    leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                )
                ListRow("Cancel", titleColor = accent, onClick = onCancel)
            }

            CodexAuthStatus.AWAITING_AUTH -> {
                auth.userCode?.let { code ->
                    Column(
                        Modifier.fillMaxWidth().padding(ListInset),
                        verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
                    ) {
                        Text("Your one-time code", style = PocketType.footnote, color = colors.secondaryLabel)
                        SelectionContainer {
                            Text(code, style = PocketType.title2.copy(fontFamily = FontFamily.Monospace), color = accent)
                        }
                    }
                    ListRow(
                        "Copy code",
                        icon = Icons.Outlined.ContentCopy,
                        iconTile = colors.gray,
                        onClick = {
                            copyToClipboard(context, "ChatGPT sign-in code", code)
                            banner("Code copied", BannerKind.Success)
                        },
                    )
                }
                auth.verificationUrl?.let { url ->
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
                            copyToClipboard(context, "ChatGPT sign-in link", url)
                            banner("Sign-in link copied", BannerKind.Success)
                        },
                    )
                }
                ListRow(
                    "Waiting for you to finish…",
                    leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                )
                ListRow("Cancel", titleColor = accent, onClick = onCancel)
            }

            CodexAuthStatus.ERROR -> {
                ListRow("Sign-in failed", icon = Icons.Outlined.Warning, iconTile = colors.red)
                ListRow("Try again", titleColor = accent, enabled = !busy, onClick = onSignIn)
            }

            CodexAuthStatus.SIGNED_OUT -> {
                ListRow(
                    if (codexInstalled) "Sign in with ChatGPT" else "Install and sign in",
                    icon = Icons.Outlined.Person,
                    iconTile = colors.green,
                    titleColor = accent,
                    enabled = !busy,
                    onClick = onSignIn,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
