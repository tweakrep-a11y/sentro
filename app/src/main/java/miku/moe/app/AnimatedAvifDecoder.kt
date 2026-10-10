package miku.moe.app

import android.os.Build
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.github.penfeizhou.animation.avif.AVIFDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.nio.ByteBuffer
import kotlinx.coroutines.runInterruptible
import okio.BufferedSource

/**
 * Decoder Coil untuk AVIF animasi (brand "avis"), yang tidak didukung ImageDecoder bawaan Android
 * maupun coil-gif. Di Android < 12 (API 31) decoder ini juga dipakai untuk AVIF statis,
 * karena sistem belum bisa membuka AVIF sama sekali.
 */
class AnimatedAvifDecoder(
    private val source: ImageSource,
    @Suppress("unused") private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        val bytes = source.source().readByteArray()
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }
        DecodeResult(drawable = AVIFDrawable(loader), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val head = readHead(result.source.source()) ?: return null
            if (head.length < 12 || head.substring(4, 8) != "ftyp") return null
            val animated = head.contains("avis")
            val avif = animated || head.contains("avif")
            if (!avif) return null
            if (!animated && Build.VERSION.SDK_INT >= 31) return null
            return AnimatedAvifDecoder(result.source, options)
        }

        private fun readHead(source: BufferedSource): String? {
            return try {
                val peek = source.peek()
                if (!peek.request(16)) return null
                val size = if (peek.request(64)) 64L else peek.buffer.size
                String(peek.readByteArray(size), Charsets.ISO_8859_1)
            } catch (e: Exception) {
                null
            }
        }
    }
}
