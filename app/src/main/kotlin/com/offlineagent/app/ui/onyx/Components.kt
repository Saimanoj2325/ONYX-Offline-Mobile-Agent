package com.offlineagent.app.ui.onyx

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small rounded pill: status (Offline / Online / model / tier) and tool badges. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

val OfflineGreen = Color(0xFF7BE0A4)
val NetworkAmber = Color(0xFFFFC970)

/** Every tool shows whether it works offline. */
@Composable
fun NetworkBadge(needsNetwork: Boolean) = Pill(if (needsNetwork) "Needs network" else "Offline", if (needsNetwork) NetworkAmber else OfflineGreen)

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

/** Microphone glyph drawn on a Canvas (the core icon set has no mic). */
@Composable
fun MicGlyph(color: Color, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * 0.09f
        drawRoundRect(color, topLeft = Offset(w * 0.36f, h * 0.08f), size = Size(w * 0.28f, h * 0.52f), cornerRadius = CornerRadius(w * 0.14f))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.22f, h * 0.30f), size = Size(w * 0.56f, h * 0.46f), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, h * 0.76f), Offset(w / 2, h * 0.90f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.92f), Offset(w * 0.64f, h * 0.92f), sw, StrokeCap.Round)
    }
}

/**
 * The voice orb: a breathing gradient sphere. While listening it pulses with the microphone level; while the agents
 * work it slowly rotates its glow.
 */
@Composable
fun VoiceOrb(listening: Boolean, working: Boolean, level: Float, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (working) 2400 else 9000)), label = "spin")
    val lvl by animateFloatAsState(if (listening) 1f + level * 0.22f else 1f, tween(120), label = "lvl")
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(size + 36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size + 36.dp).scale(if (listening) lvl else breathe)) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(cs.primary.copy(alpha = if (listening) 0.45f else 0.22f), Color.Transparent), center, r), r)
            rotateGlow(spin, cs.tertiary, cs.secondary)
        }
        Box(Modifier.size(size).scale(breathe).clip(CircleShape)
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.primary.copy(alpha = 0.85f), cs.tertiary.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center) {
            MicGlyph(cs.onPrimaryContainer, 40.dp)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateGlow(angle: Float, a: Color, b: Color) {
    val r = size.minDimension / 2 * 0.82f
    val rad = Math.toRadians(angle.toDouble())
    val p1 = Offset(center.x + (r * kotlin.math.cos(rad)).toFloat(), center.y + (r * kotlin.math.sin(rad)).toFloat())
    val p2 = Offset(center.x - (r * kotlin.math.cos(rad)).toFloat(), center.y - (r * kotlin.math.sin(rad)).toFloat())
    drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.35f), Color.Transparent), p1, r * 0.55f), r * 0.55f, p1)
    drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.30f), Color.Transparent), p2, r * 0.5f), r * 0.5f, p2)
}

/** Three animated dots ("working…"). */
@Composable
fun WorkingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}
