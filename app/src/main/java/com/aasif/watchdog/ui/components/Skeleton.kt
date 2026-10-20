package com.aasif.watchdog.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import com.aasif.watchdog.ui.theme.watchdog
import kotlinx.coroutines.delay

private const val SWEEP_MILLIS = 1400
private const val SHOW_AFTER_MILLIS = 150L
private const val MIN_VISIBLE_MILLIS = 400L

private val StaticPhase: State<Float> = object : State<Float> {
    override val value: Float get() = 0.5f
}
private val LocalShimmerPhase = compositionLocalOf { StaticPhase }

/**
 * One shimmer clock for every block beneath it, so a screen of skeletons runs a single animation. Blocks stay
 * still when the system has animations turned off.
 */
@Composable
fun ProvideShimmer(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (!animate) {
        CompositionLocalProvider(LocalShimmerPhase provides StaticPhase, content = content)
        return
    }
    val phase = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-phase",
    )
    CompositionLocalProvider(LocalShimmerPhase provides phase, content = content)
}

/**
 * A placeholder shaped like the content it stands in for, so nothing moves when the real thing arrives.
 * [stagger] (0..1) offsets this block's sweep so a list reads as a ripple; [onHero] uses the night band's palette.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6),
    stagger: Float = 0f,
    onHero: Boolean = false,
) {
    val phase = LocalShimmerPhase.current
    val w = MaterialTheme.watchdog
    val base = if (onHero) w.skeletonHero else w.skeleton
    val highlight = if (onHero) w.skeletonHeroHi else w.skeletonHi
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val p = (phase.value + stagger).mod(1f)
                val x = -size.width + p * size.width * 3f
                drawRect(base)
                drawRect(Brush.linearGradient(listOf(base, highlight, base), Offset(x, 0f), Offset(x + size.width, 0f)))
            },
    )
}

enum class SkeletonPhase {
    /** Loading just began: show nothing yet, so a fast load never flashes a skeleton. */
    Waiting,
    Skeleton,
    Content,
}

/**
 * Gates a skeleton so it appears only if loading outlasts 150 ms, and then stays at least 400 ms rather than
 * flickering away.
 */
@Composable
fun rememberSkeletonPhase(loading: Boolean): SkeletonPhase {
    var showing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(SHOW_AFTER_MILLIS)
            shownAt = System.currentTimeMillis()
            showing = true
        } else if (showing) {
            val left = MIN_VISIBLE_MILLIS - (System.currentTimeMillis() - shownAt)
            if (left > 0) delay(left)
            showing = false
        }
    }
    return when {
        showing -> SkeletonPhase.Skeleton
        loading -> SkeletonPhase.Waiting
        else -> SkeletonPhase.Content
    }
}
