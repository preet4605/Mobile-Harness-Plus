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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.MoreHoriz
import com.jarves.mh.runtime.CodexAccount
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketIconButton
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberOverlayAnchor
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
import com.jarves.mh.ui.kit.ToggleRow
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
    accounts: List<CodexAccount> = emptyList(),
    selectedAccountId: String? = null,
    inUse: Set<String> = emptySet(),
    onSelectAccount: (String) -> Unit = {},
    balancingEnabled: Boolean = false,
    onBalancingChanged: (Boolean) -> Unit = {},
    onReconnectAccount: (String) -> Unit = {},
    onEnableAccount: (String, Boolean) -> Unit = { _, _ -> },
    onRemoveAccount: (String) -> Unit = {},
    onRenameAccount: (String, String) -> Unit = { _, _ -> },
) {
    val colors = PocketColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val banner = rememberBanner()
    val accent = MaterialTheme.colorScheme.primary
    var pendingRemoval by remember { mutableStateOf<CodexAccount?>(null) }
    var renaming by remember { mutableStateOf<CodexAccount?>(null) }
    var label by remember { mutableStateOf("") }
    val signingIn = auth.status == CodexAuthStatus.STARTING || auth.status == CodexAuthStatus.AWAITING_AUTH

    val footer = if (balancingEnabled && !signingIn) {
        "Completed turns rotate across enabled accounts, skipping known recent usage limits. Completed conversation context transfers; project memory stays shared. Native goals and chats with subagents keep their account. Account-specific Codex memory stays separate. Interrupted or oversized transfers stop with recovery guidance."
    } else when (auth.status) {
        CodexAuthStatus.SIGNED_IN ->
            if (accounts.size > 1) {
                "Future tasks use the default account. Switching accounts can send visible chat history to that account. Running and queued tasks keep their account."
            } else {
                "Codex uses the selected ChatGPT plan. Credentials stay in its private runtime home."
            }
        CodexAuthStatus.AWAITING_AUTH ->
            "Open the page, sign in, and enter the code. Codes expire after 15 minutes. Never share this code with anyone."
        CodexAuthStatus.ERROR -> auth.message ?: "Sign-in did not complete."
        CodexAuthStatus.SIGNED_OUT ->
            auth.message?.takeIf { it.isNotBlank() && it != "Not signed in" }
                ?: if (accounts.size > 1) {
                    "Future tasks use the default account. Switching accounts can send visible chat history to that account. Running and queued tasks keep their account."
                } else if (accounts.isNotEmpty()) {
                    "Select or reconnect an account using the options above."
                } else {
                    "Use your ChatGPT Plus, Pro, Business or Enterprise account. You approve the sign-in on a web page, on this phone or another device."
                }
        CodexAuthStatus.STARTING -> null
    }

    ListSection(modifier = modifier, header = "ChatGPT accounts", footer = footer) {
        if (accounts.isNotEmpty()) ToggleRow("Balance accounts", checked = balancingEnabled,
            enabled = !busy && !signingIn, onCheckedChange = onBalancingChanged)
        accounts.forEach { account -> key(account.id) {
            var menu by remember { mutableStateOf(false) }
            val anchor = rememberOverlayAnchor()
            ListRow(
                account.label,
                subtitle = listOfNotNull(
                    "Default".takeIf { selectedAccountId == account.id },
                    "In use".takeIf { account.id in inUse },
                    "Disabled".takeIf { !account.enabled },
                    "Needs sign-in".takeIf { account.needsSignIn },
                    account.reportedUsedPercent?.let { "Last reported: $it% used" },
                ).joinToString(" · ").ifBlank { "ChatGPT" },
                icon = Icons.Outlined.Person,
                iconTile = if (account.enabled) colors.green else colors.gray,
                trailing = {
                    PocketIconButton(Icons.Outlined.MoreHoriz, "Options for ${account.label}",
                        onClick = { menu = true }, modifier = Modifier.overlayAnchor(anchor))
                },
            )
            GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
                if (!signingIn) GlassMenuItem("Rename", { menu = false; renaming = account; label = account.label })
                if (!signingIn && account.enabled && !account.needsSignIn && selectedAccountId != account.id) {
                    GlassMenuItem("Set default", { menu = false; onSelectAccount(account.id) })
                }
                if (!busy && !signingIn && account.id !in inUse) {
                    GlassMenuItem("Reconnect", { menu = false; onReconnectAccount(account.id) })
                    GlassMenuItem(if (account.enabled) "Disable" else "Enable", { menu = false; onEnableAccount(account.id, !account.enabled) })
                    GlassMenuItem("Remove account", { menu = false; pendingRemoval = account }, destructive = true)
                }
            }
        } }
        if (accounts.isNotEmpty() && !signingIn) {
            ListRow("Add account", icon = Icons.Outlined.Person, iconTile = colors.green, enabled = !busy, onClick = onSignIn)
        }
        when (auth.status) {
            CodexAuthStatus.SIGNED_IN -> {
                ListRow(auth.displayStatus, icon = Icons.Outlined.CheckCircle, iconTile = colors.green)
                ListRow("Refresh status", icon = Icons.Outlined.Refresh, iconTile = colors.gray, onClick = onRefresh)
                if (accounts.isEmpty()) ListRow("Sign out", destructive = true, enabled = !busy, onClick = onSignOut)
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
                if (accounts.isNotEmpty()) {
                    ListRow("Select or reconnect an account", subtitle = "Use the account options above.")
                    ListRow("Refresh status", onClick = onRefresh)
                } else ListRow(
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
    renaming?.let { account ->
        PocketAlert(
            onDismiss = { renaming = null }, title = "Account label",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { renaming = null },
                AlertAction("Save", enabled = label.isNotBlank() && !signingIn) {
                    onRenameAccount(account.id, label)
                    renaming = null
                },
            ),
        ) {
            PocketTextField(value = label, onValueChange = { label = it.take(160) }, placeholder = "Account label")
        }
    }
    pendingRemoval?.let { account ->
        PocketAlert(
            onDismiss = { pendingRemoval = null },
            title = "Remove ${account.label}?",
            message = "Signs out this account locally. Project history and private CLI transcripts are retained. You can add the account again later.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { pendingRemoval = null },
                AlertAction("Remove", AlertRole.Destructive, enabled = !busy && !signingIn) {
                    pendingRemoval = null
                    onRemoveAccount(account.id)
                },
            ),
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
