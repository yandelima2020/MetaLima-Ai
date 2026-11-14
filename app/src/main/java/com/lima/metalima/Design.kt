package com.lima.metalima

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.background
internal val SurfaceTone: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val Lime: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Panel = RoundedCornerShape(22.dp)

internal enum class Skin(val label: Int, val background: Long, val surface: Long, val accent: Long,
    val muted: Long, val text: Long, val border: Long, val onAccent: Long, val light: Boolean = false) {
    GRAPHITE(R.string.skin_graphite, 0xFF101217, 0xFF1A1D25, 0xFFD4FA77, 0xFFB4BBC7, 0xFFF5F7FA, 0xFF323743, 0xFF1A2806),
    OCEAN(R.string.skin_ocean, 0xFF0C1721, 0xFF152635, 0xFF82D9ED, 0xFFB0C4D2, 0xFFF0F7FD, 0xFF344F61, 0xFF062E38),
    AMBER(R.string.skin_amber, 0xFF1B1512, 0xFF2C2018, 0xFFFCC384, 0xFFD1B8A8, 0xFFFFF4EC, 0xFF564033, 0xFF382014),
    PORCELAIN(R.string.skin_porcelain, 0xFFF1F4F6, 0xFFFFFFFF, 0xFF175C53, 0xFF46565E, 0xFF111D24, 0xFFCAD5DA, 0xFFFFFFFF, true);

    fun colors(): ColorScheme {
        val base = if (light) lightColorScheme() else darkColorScheme()
        return base.copy(primary = Color(accent), onPrimary = Color(onAccent), secondary = Color(accent),
            background = Color(background), onBackground = Color(text), surface = Color(surface), onSurface = Color(text),
            surfaceVariant = Color(border), onSurfaceVariant = Color(muted), outline = Color(muted), outlineVariant = Color(border),
            surfaceContainer = Color(surface), surfaceContainerHigh = Color(border),
            surfaceContainerHighest = Color(border), primaryContainer = Color(accent).copy(alpha = .18f), onPrimaryContainer = Color(text))
    }
}

@Composable internal fun StudioTheme(skin: Skin, content: @Composable () -> Unit) {
    val studioFont = remember { FontFamily(
        Font(R.font.dm_sans_400, FontWeight.Normal),
        Font(R.font.dm_sans_500, FontWeight.Medium),
        Font(R.font.dm_sans_600, FontWeight.SemiBold),
        Font(R.font.dm_sans_700, FontWeight.Bold)
    ) }
    MaterialTheme(
        colorScheme = remember(skin) { skin.colors() },
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = studioFont, fontWeight = FontWeight.SemiBold, fontSize = 35.sp, lineHeight = 40.sp, letterSpacing = (-1.35).sp),
            headlineMedium = TextStyle(fontFamily = studioFont, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.7).sp),
            titleLarge = TextStyle(fontFamily = studioFont, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontFamily = studioFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
            bodyMedium = TextStyle(fontFamily = studioFont, fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontFamily = studioFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
        ), content = content
    )
}

@Composable internal fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        subtitle?.let { Text(it, color = Muted) }
    }
}

@Composable internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
}

@Composable internal fun LiveLaunchCard(model: String, open: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(palette.primary, Panel).clickable(onClick = open).padding(17.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(45.dp).background(palette.onPrimary.copy(alpha = .12f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Headphones, null, tint = palette.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.conversacion_en_directo), style = MaterialTheme.typography.titleMedium, color = palette.onPrimary)
            Text(model, style = MaterialTheme.typography.bodyMedium, color = palette.onPrimary)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = palette.onPrimary)
    }
}

@Composable internal fun ActionTile(icon: ImageVector, title: String, details: String, tint: Color = Lime, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceTone, Panel).border(1.dp, Line, Panel).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(43.dp).background(tint.copy(alpha = .13f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(details, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(17.dp))
    }
}

@Composable internal fun Block(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceTone, Panel).border(1.dp, Line, Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable internal fun DeviceArt(modifier: Modifier = Modifier) {
    val accent = Lime
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val paint = accent.copy(alpha = .88f)
        for (s in 1..4) drawCircle(accent.copy(alpha = .08f), w * (.11f + s * .05f), Offset(w * .52f, h * .55f), style = Stroke(1.dp.toPx()))
        val y = h * .38f
        val lensW = w * .27f
        val lensH = h * .28f
        val x1 = w * .19f
        val x2 = w * .54f
        val thickness = w * .023f
        val lensShape = CornerRadius(lensW * .25f, lensH * .28f)
        val fill = Brush.linearGradient(listOf(accent.copy(alpha = .12f), Color.Transparent), Offset(x1, y), Offset(x2 + lensW, y + lensH))
        for (x in listOf(x1, x2)) {
            drawRoundRect(fill, Offset(x, y), Size(lensW, lensH), lensShape)
            drawRoundRect(paint, Offset(x, y), Size(lensW, lensH), lensShape, style = Stroke(thickness))
        }
        drawLine(paint, Offset(x1 + lensW, y + lensH * .36f), Offset(x2, y + lensH * .36f), thickness)
        drawLine(paint, Offset(w * .11f, y + lensH * .12f), Offset(x1, y + lensH * .20f), thickness)
        drawLine(paint, Offset(x2 + lensW, y + lensH * .20f), Offset(w * .88f, y + lensH * .12f), thickness)
        drawCircle(accent, thickness * .52f, Offset(x2 + lensW * .78f, y + lensH * .20f))
    }
}
