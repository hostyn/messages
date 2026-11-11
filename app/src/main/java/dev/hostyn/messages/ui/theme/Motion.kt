package dev.hostyn.messages.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density

/*
 * M3 transition patterns. The M3 guidelines still define transitions with the easing and duration
 * system (component motion uses the expressive spring scheme from the theme), so these use the M3
 * emphasized easings and duration tokens.
 */

/** M3 easing tokens. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** M3 duration tokens (ms). */
const val DurationShort3 = 150
const val DurationMedium1 = 250
const val DurationMedium2 = 300
const val DurationMedium3 = 350
const val DurationLong1 = 450
const val DurationLong2 = 500

/** Distance screens slide in the forward and backward pattern (M3: 30dp). */
private fun Density.slide() = (30 * density).toInt()

/** Forward and backward: slide 30dp along x while fading through. */
fun Density.forwardEnter(): EnterTransition =
    slideInHorizontally(tween(DurationLong1, easing = EmphasizedDecelerate)) { slide() } +
        fadeIn(tween(DurationMedium1, delayMillis = DurationShort3 / 2, easing = LinearEasing))

fun Density.forwardExit(): ExitTransition =
    slideOutHorizontally(tween(DurationLong1, easing = EmphasizedAccelerate)) { -slide() } +
        fadeOut(tween(DurationShort3 / 2 + 25, easing = LinearEasing))

fun Density.backwardEnter(): EnterTransition =
    slideInHorizontally(tween(DurationLong1, easing = EmphasizedDecelerate)) { -slide() } +
        fadeIn(tween(DurationMedium1, delayMillis = DurationShort3 / 2, easing = LinearEasing))

fun Density.backwardExit(): ExitTransition =
    slideOutHorizontally(tween(DurationLong1, easing = EmphasizedAccelerate)) { slide() } +
        fadeOut(tween(DurationShort3 / 2 + 25, easing = LinearEasing))

/** Enter and exit beyond the screen bounds, from the bottom edge (like a sheet). */
fun bottomEnter(): EnterTransition =
    slideInVertically(tween(DurationLong2, easing = EmphasizedDecelerate)) { it / 4 } + fadeIn(tween(DurationMedium1))

fun bottomExit(): ExitTransition =
    slideOutVertically(tween(DurationMedium2, easing = EmphasizedAccelerate)) { it / 4 } + fadeOut(tween(DurationMedium1))

/** Top level: the old destination quickly fades out, then the new one fades in. */
fun topLevelEnter(): EnterTransition =
    fadeIn(tween(DurationMedium1, delayMillis = 90, easing = EmphasizedDecelerate)) +
        scaleIn(tween(DurationMedium1, delayMillis = 90, easing = EmphasizedDecelerate), initialScale = 0.96f)

fun topLevelExit(): ExitTransition = fadeOut(tween(90, easing = LinearEasing))

/**
 * Container transforms run at medium speed: M3 suggests up to 500ms, but this is a utility app and
 * opening a conversation should feel immediate.
 */
private const val TransformDuration = DurationMedium3

/** While a container transform runs, the screens underneath only fade. */
fun transformEnter(): EnterTransition = fadeIn(tween(TransformDuration, easing = LinearEasing))
fun transformExit(): ExitTransition = fadeOut(tween(TransformDuration, easing = LinearEasing))

// --- Container transform ---

/**
 * Which element the next screen should grow out of. Rows set it right before navigating; the
 * navigation reads it (once) and passes it to the new screen.
 */
class TransformOrigin {
    var key: String? = null

    fun take(): String? = key.also { key = null }
}

val LocalTransformOrigin = staticCompositionLocalOf { TransformOrigin() }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * M3 container transform: the element with this [key] on one screen and on the next one share
 * their bounds, so one grows into the other while their contents cross-fade. Without a key (or
 * outside navigation) it does nothing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.containerTransform(key: String?, shape: Shape = RectangleShape): Modifier {
    val shared = LocalSharedTransitionScope.current
    val scope = LocalNavAnimatedScope.current
    if (key == null || shared == null || scope == null) return this
    return with(shared) {
        this@containerTransform.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = scope,
            // Contents fade through, overlapping a little so the container never looks empty.
            enter = fadeIn(tween(200, delayMillis = 35, easing = LinearEasing)),
            exit = fadeOut(tween(100, easing = LinearEasing)),
            boundsTransform = { _, _ -> tween(TransformDuration, easing = Emphasized) },
            // The growing container lays its contents out at its current size, so the new screen
            // unfolds from the top (app bar, header…) as it grows out of the element.
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
