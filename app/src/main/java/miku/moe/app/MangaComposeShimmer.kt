package miku.moe.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val SHIMMER_PERIOD_MS = 1400L

// tan(18 derajat): kemiringan kilau ala Facebook, sama dengan ShimmerDrawable versi View.
private const val SHIMMER_TILT = 0.325f

/**
 * Progress shimmer 0..1 yang diturunkan dari jam frame, bukan dari waktu mulai masing-masing composable.
 * Akibatnya SEMUA blok shimmer (skeleton, placeholder gambar, kartu serupa) selalu berada di fase yang sama.
 *
 * Mengembalikan State, bukan Float: nilainya hanya dibaca di fase draw (lihat [MangaShimmerBlock]),
 * jadi animasi tidak memicu rekomposisi seluruh skeleton di setiap frame.
 */
@Composable
internal fun rememberMangaShimmerProgress(): State<Float> {
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                progress.floatValue = ((nanos / 1_000_000L) % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS
            }
        }
    }
    return progress
}

private class ShimmerOrigin {
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
}

private fun shimmerHighlight(base: Color): Color {
    val luminance = 0.2126f * base.red + 0.7152f * base.green + 0.0722f * base.blue
    val amount = if (luminance > 0.5f) 0.7f else 0.14f
    return Color(
        red = base.red + (1f - base.red) * amount,
        green = base.green + (1f - base.green) * amount,
        blue = base.blue + (1f - base.blue) * amount,
        alpha = base.alpha,
    )
}

/**
 * Blok shimmer gaya Facebook. Kilau digambar dalam koordinat ROOT layar (bukan koordinat blok),
 * jadi satu sapuan miring yang sama melintasi semua blok sekaligus. Sebelumnya tiap blok membuat
 * sapuan sendiri berdasarkan ukurannya, sehingga blok kecil dan besar berkilau tidak serempak
 * dan terlihat tidak sesuai satu sama lain.
 *
 * @param onHero true jika blok berada di atas banner gelap/gambar; memakai putih transparan
 *               supaya tetap terlihat.
 * @param baseColor warna dasar custom (mis. warna card asli) supaya container seperti CardView
 *                  ikut kena shimmer; null = memakai surfaceContainerHigh bawaan.
 * @param content isi opsional di atas shimmer (dipakai untuk card yang container-nya shimmer
 *                  tapi isinya tetap blok-blok shimmer yang sedikit lebih terang).
 */
@Composable
internal fun MangaShimmerBlock(
    modifier: Modifier,
    progress: State<Float>,
    radius: Dp = 6.dp,
    onHero: Boolean = false,
    baseColor: Color? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val defaultSurface = MaterialTheme.colorScheme.surfaceContainerHigh
    val base = baseColor ?: if (onHero) Color.White.copy(alpha = 0.14f) else defaultSurface
    val highlight = if (onHero) Color.White.copy(alpha = 0.30f) else remember(base) { shimmerHighlight(base) }
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val origin = remember { ShimmerOrigin() }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                origin.x = position.x
                origin.y = position.y
            }
            .drawBehind {
                val band = screenWidthPx * 0.75f
                val left = -band + progress.value * (screenWidthPx + band * 2f) - origin.x
                val top = -origin.y
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(0f to base, 0.5f to highlight, 1f to base),
                        start = Offset(left, top),
                        end = Offset(left + band, top + band * SHIMMER_TILT),
                    ),
                    cornerRadius = CornerRadius(radius.toPx()),
                )
            },
        content = content,
    )
}

@Composable
internal fun MangaBrokenImage(modifier: Modifier = Modifier) {
    // Sama dengan BrokenImageDrawable versi View: teks di tengah, rata tengah, ukuran font mengikuti lebar cover.
    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = (maxWidth.value * 0.13f).coerceIn(9f, 15f).sp
        Text(
            text = "Gambar Rusak",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** Kartu skeleton untuk grid "Serupa": ukuran & sudut identik dengan SerupaCardV3. */
@Composable
internal fun MangaRelatedSkeletonCard(progress: State<Float>) {
    Column(Modifier.fillMaxWidth()) {
        MangaShimmerBlock(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)), progress, 12.dp)
        Spacer(Modifier.height(6.dp))
        MangaShimmerBlock(Modifier.fillMaxWidth(0.88f).height(12.dp), progress)
        Spacer(Modifier.height(4.dp))
        MangaShimmerBlock(Modifier.fillMaxWidth(0.58f).height(12.dp), progress)
    }
}
