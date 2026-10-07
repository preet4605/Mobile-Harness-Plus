package com.jarves.mh.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import com.jarves.mh.ui.theme.PocketMotion.Token

/**
 * Navigation transitions built on [PocketMotion] springs. Springs keep velocity when a
 * transition is interrupted (for example tapping back mid-push), so nothing jumps.
 */
object PocketTransitions {
    /** How far the covered screen shifts while the new one slides over it. */
    const val ParallaxFraction: Float = 0.3f

    /** Opacity of the covered screen at the end of a push (reads as a dim). */
    const val CoveredAlpha: Float = 0.6f

    /**
     * Push (forward) or pop (back): the deeper screen slides over the shallower one, which
     * shifts by [ParallaxFraction] and dims. The deeper screen always draws on top.
     */
    fun push(forward: Boolean): ContentTransform {
        if (PocketMotion.reduced) return crossFade()
        val slide = PocketMotion.spec(Token.Smooth, IntOffset.VisibilityThreshold)
        val fade = PocketMotion.spec<Float>(Token.Smooth)
        return if (forward) {
            ContentTransform(
                targetContentEnter = slideInHorizontally(slide) { it },
                initialContentExit = slideOutHorizontally(slide) { -(it * ParallaxFraction).toInt() } +
                    fadeOut(fade, targetAlpha = CoveredAlpha),
                targetContentZIndex = 1f,
                sizeTransform = null,
            )
        } else {
            ContentTransform(
                targetContentEnter = slideInHorizontally(slide) { -(it * ParallaxFraction).toInt() } +
                    fadeIn(fade, initialAlpha = CoveredAlpha),
                initialContentExit = slideOutHorizontally(slide) { it },
                targetContentZIndex = -1f,
                sizeTransform = null,
            )
        }
    }

    /** Startup and setup into the app: the new screen settles from 98% scale as it fades in. */
    fun settle(): ContentTransform {
        if (PocketMotion.reduced) return crossFade()
        val fade = PocketMotion.spec<Float>(Token.Smooth)
        return ContentTransform(
            targetContentEnter = fadeIn(fade) + scaleIn(PocketMotion.spec(Token.Smooth), initialScale = 0.98f),
            initialContentExit = fadeOut(PocketMotion.spec(Token.Quick)),
            sizeTransform = null,
        )
    }

    /** Peer switch (tabs): content cross-fades in place, with no slide. */
    fun crossFade(): ContentTransform {
        val fade = PocketMotion.spec<Float>(Token.Quick)
        return ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = null)
    }
}

/** True while this AnimatedContent child is the destination, false while it animates out. */
val AnimatedVisibilityScope.isTransitionTarget: Boolean
    get() = transition.targetState == EnterExitState.Visible

/**
 * Returns [value] while this child is the destination, and the last such value while it
 * animates out. Outgoing screens keep rendering what they showed (for example the project
 * that was just closed) instead of reading state that already belongs to the next screen.
 */
@Composable
fun <T> AnimatedVisibilityScope.heldWhileExiting(value: T): T {
    val held = remember { arrayOfNulls<Any?>(1).also { it[0] = value } }
    if (isTransitionTarget) held[0] = value
    @Suppress("UNCHECKED_CAST")
    return held[0] as T
}

/** Root shared-transition scope, so titles can morph between screens. Null outside it. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The app-level navigation AnimatedContent child scope. Null outside it. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Lets a title morph between two screens: the same [key] on the outgoing and incoming text
 * animates its bounds with the [Token.Smooth] spring. A no-op outside the navigation scopes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTitle(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedTitle.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = SmoothBounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val SmoothBounds = BoundsTransform { _, _ -> PocketMotion.spec(Token.Smooth, Rect.VisibilityThreshold) }
