package com.jarves.mh.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.ui.kit.*
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.glass.hostBackdropSource

/**
 * The engine test (spec v2 P3), opened from the Mods menu. It checks whether this phone can run
 * mod code in the system's JavaScript sandbox and how fast it is, then lets the user copy or
 * share the results.
 */
@Composable
internal fun ModEngineTestPage(bottomBarPadding: Dp, onBack: () -> Unit, viewModel: ModsViewModel) {
    val state by viewModel.engineTest.collectAsStateWithLifecycle()
    val colors = PocketColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val banner = LocalBanner.current
    val listState = rememberLazyListState()
    val collapse by rememberTitleCollapse(listState)
    val report = state.report

    LargeTitleScaffold(
        title = "Engine test",
        collapse = { collapse },
        leading = { GlassToolbarButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            item(key = "title") { LargeTitle("Engine test", subtitle = "Can this phone run mod code?") }
            item(key = "run") {
                ListSection(
                    footer = "Checks the locked-down JavaScript sandbox that mod code would run in, which comes with " +
                        "the system WebView. It takes under a minute, runs no mods and changes nothing. " +
                        "Send the results to the agent building the engine.",
                ) {
                    ListRow(
                        title = if (state.running) "Testing…" else if (report == null) "Run test" else "Run again",
                        icon = Icons.Outlined.PlayArrow,
                        iconTile = colors.blue,
                        enabled = !state.running,
                        onClick = viewModel::runEngineTest,
                        leading = if (state.running) ({ ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }) else null,
                    )
                }
            }
            if (report != null) {
                item(key = "verdict") {
                    ListSection {
                        ListRow(
                            title = report.verdict,
                            icon = if (report.ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            iconTile = if (report.ready) colors.green else colors.orange,
                            subtitleMaxLines = 4,
                        )
                        ListRow(
                            title = "Copy results",
                            icon = Icons.Outlined.ContentCopy,
                            iconTile = colors.gray,
                            onClick = {
                                clipboard.setText(AnnotatedString(report.text()))
                                banner?.show("Copied the results.", bannerKindFor("Copied the results."))
                            },
                        )
                        ListRow(
                            title = "Share results",
                            icon = Icons.Outlined.Share,
                            iconTile = colors.gray,
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.text())
                                runCatching { context.startActivity(Intent.createChooser(send, "Share engine test results")) }
                            },
                        )
                    }
                }
            }
            state.checks.groupBy { it.group }.forEach { (group, checks) ->
                item(key = "group-$group") {
                    ListSection(header = group) {
                        checks.forEach { check ->
                            ListRow(
                                title = check.label,
                                subtitle = check.value.takeIf { it.length > SHORT_VALUE },
                                subtitleMaxLines = 4,
                                subtitleColor = if (check.passed == false) colors.orange else null,
                                value = check.value.takeIf { it.length <= SHORT_VALUE },
                                titleColor = if (check.passed == false) colors.orange else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SHORT_VALUE = 24
