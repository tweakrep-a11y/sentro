package miku.moe.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Enrichment genre/status/total-episode untuk kartu Home Anime V2.
 *
 * Perbaikan 2026-10-08:
 * 1. HENTIKAN infinite rebind loop (penyebab scroll "geter"/"ketarik" khusus di
 *    Anime X Nonton): dulu `needsResolve()` selalu true untuk item yang genre-nya
 *    tak bisa di-resolve, dan `request()` tetap memicu callback walau cache hit
 *    tanpa data baru -> notifyItemChanged -> rebind -> request -> ... selamanya.
 *    Sekarang: key yang fetch-nya sudah SUKSES dicatat di `completed` (tidak
 *    di-fetch ulang), dan callback hanya dipicu bila `apply()` benar-benar
 *    mengubah data post.
 * 2. Genre XNonton: selalu baca `category.genre` dari get_category_posts_secure
 *    (reliable), dan pakai `meta["Themes"]` sebagai fallback karena HTML baru
 *    memakai label "Theme:" bukan "Genres:".
 * 3. Dukung Animeloverz (dulu key="" -> tidak pernah di-resolve): ambil
 *    genre/status/total episode dari `series.php?url=<slug>`.
 * 4. 2026-10-08: dukung Dramora — new-upload tidak mengirim genre (terbukti
 *    dari HAR), jadi genre di-enrich per-item dari
 *    `/api/v2/movie/detail-movie` (genre[]).
 */
object AnimeHomeV2Resolver {
    interface Callback {
        fun onResolved(post: AnimePost)
    }

    private class Info(val genre: String, val status: String, val totalEpisodes: Int)

    private class Waiter(val post: AnimePost, val callback: Callback)

    private const val RETRY_MS = 120000L
    private const val LOVERZ_SERIES_URL = "https://apps.animekita.org/api/v1.2.5/series.php?url="
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = HashMap<String, Info>()
    /** Key yang fetch-nya sudah sukses (walau hasilnya kosong) -> jangan fetch ulang. */
    private val completed = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, ArrayList<Waiter>>()
    private val loverzClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @JvmStatic
    fun key(post: AnimePost?): String {
        if (post == null) return ""
        if (post.sourceId == AnimeSettingsManager.SOURCE_DEFAULT) {
            if (post.categoryId > 0) return "default|category:" + post.categoryId
            if (post.channelId > 0) return "default|channel:" + post.channelId
            return ""
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_ANIMELOVERZ) {
            val slug = post.slug?.trim()?.trim('/') ?: ""
            if (slug.isNotEmpty()) return "loverz|slug:$slug"
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_DRAMORA) {
            // 2026-10-08: new-upload tidak mengirim genre -> enrich per-item
            // via detail-movie (genre[]). post.slug = id movie (UUID).
            val id = post.slug?.trim()?.trim('/') ?: ""
            if (id.isNotEmpty()) return "dramora|movie:$id"
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_DRAKORKU && post.categoryId > 0) {
            return "drakorku|category:${post.categoryId}"
        }
        return ""
    }

    @JvmStatic
    fun needsResolve(post: AnimePost?): Boolean {
        val key = key(post)
        if (key.isEmpty() || post == null) return false
        val genreBlank = post.genre.isNullOrBlank()
        val statusBlank = AnimeHomeV2Labels.statusText(post).isEmpty()
        val totalUnknown = post.totalEpisodes <= 0
        if (!genreBlank && !statusBlank && !totalUnknown) return false
        synchronized(lock) {
            // Fetch sudah pernah sukses -> berhenti, jangan loop selamanya.
            if (completed.contains(key)) return false
            val failed = failedAt[key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return false
        }
        return true
    }

    @JvmStatic
    fun applyCached(post: AnimePost?) {
        val key = key(post)
        if (key.isEmpty() || post == null) return
        val info = synchronized(lock) { cache[key] } ?: return
        apply(post, info)
    }

    @JvmStatic
    fun isPending(post: AnimePost?): Boolean {
        val key = key(post)
        if (key.isEmpty()) return false
        return synchronized(lock) { pending.contains(key) }
    }

    @JvmStatic
    fun request(post: AnimePost?, callback: Callback) {
        val key = key(post)
        if (key.isEmpty() || post == null) return
        var cached: Info? = null
        var start = false
        var settled = false
        synchronized(lock) {
            cached = cache[key]
            if (cached == null && !completed.contains(key)) {
                val failed = failedAt[key]
                if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
                val list = waiters.getOrPut(key) { ArrayList() }
                if (list.none { it.post === post }) list.add(Waiter(post, callback))
                start = pending.add(key)
            } else {
                settled = true
            }
        }
        val ready = cached
        if (ready != null) {
            // Cache hit: hanya beri kabar bila benar-benar ada data baru untuk post ini.
            // Ini pemutus utama infinite rebind loop.
            if (apply(post, ready)) main.post { callback.onResolved(post) }
            return
        }
        // Sudah pernah sukses tapi tak ada data baru -> diam, jangan callback.
        if (settled) return
        if (start) executor.execute { run(key, post) }
    }

    private fun run(key: String, source: AnimePost) {
        var info: Info? = null
        try {
            info = fetch(source)
        } catch (e: Exception) {
            info = null
        }
        val list: ArrayList<Waiter>
        synchronized(lock) {
            if (info != null) {
                cache[key] = info
                completed.add(key)
            } else {
                failedAt[key] = System.currentTimeMillis()
            }
            pending.remove(key)
            list = waiters.remove(key) ?: ArrayList()
        }
        val resolved = info
        main.post {
            for (waiter in list) {
                val changed = resolved != null && apply(waiter.post, resolved)
                if (changed) waiter.callback.onResolved(waiter.post)
            }
        }
    }

    private fun fetch(post: AnimePost): Info {
        return when (post.sourceId) {
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> fetchLoverz(post)
            AnimeSettingsManager.SOURCE_DRAMORA -> fetchDramora(post)
            AnimeSettingsManager.SOURCE_DRAKORKU -> fetchDrakorku(post)
            else -> fetchDefault(post)
        }
    }

    /**
     * 2026-10-08: genre Dramora untuk kartu Rilis Terbaru. Endpoint new-upload
     * tidak mengirim genre (terbukti dari HAR) -> ambil dari detail-movie.
     * Hanya genre yang diisi; status & total episode sudah ada dari new-upload.
     */
    private fun fetchDramora(post: AnimePost): Info {
        val id = post.slug?.trim()?.trim('/') ?: ""
        if (id.isEmpty()) return Info("", "", 0)
        val genre = Dramora.movieGenre(id)
        return Info(genre, "", 0)
    }

    // Data listing Drakorku sudah mencakup genre, status, dan jumlah episode.
    private fun fetchDrakorku(post: AnimePost): Info {
        return Info(post.genre.orEmpty(), post.statusVideo.orEmpty(), post.totalEpisodes)
    }

    private fun fetchDefault(post: AnimePost): Info {
        val scratch = AnimePost(post.imgUrl, post.categoryName, post.categoryId, post.channelId)
        scratch.sourceId = AnimeSettingsManager.SOURCE_DEFAULT
        scratch.genre = post.genre.orEmpty()
        scratch.statusVideo = post.statusVideo.orEmpty()
        scratch.rating = post.rating.orEmpty()
        scratch.year = post.year
        scratch.episodeCount = post.episodeCount.orEmpty()
        scratch.countView = post.countView.orEmpty()
        var channelId = post.channelId
        var total = 0
        // Kategori selalu diambil bila ada categoryId: sumber genre/rating/tahun
        // yang reliable + jumlah posts = total episode.
        if (post.categoryId > 0) {
            try {
                val body = postForm(DEFAULT_CATEGORY_URL, mapOf("id" to post.categoryId.toString(), "isAPKvalid" to "true"), defaultHeaders())
                val json = JSONObject(body)
                val category = json.optJSONObject("category")
                if (category != null) {
                    scratch.genre = firstUsefulAnime(category.optString("genre", ""), scratch.genre)
                    scratch.rating = firstUsefulAnime(category.optString("rating", ""), scratch.rating)
                    if (scratch.year <= 0) scratch.year = category.optInt("years", 0)
                }
                val posts = json.optJSONArray("posts")
                if (posts != null) {
                    if (posts.length() > 0) total = posts.length()
                    if (channelId <= 0) {
                        for (i in 0 until posts.length()) {
                            val id = posts.optJSONObject(i)?.optInt("channel_id", -1) ?: -1
                            if (id > 0) {
                                channelId = id
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // lanjut ke fallback deskripsi
            }
        }
        var meta: Map<String, String> = emptyMap()
        if (channelId > 0 && (scratch.genre.isNullOrBlank() || scratch.statusVideo.isNullOrBlank())) {
            try {
                meta = loadDefaultDescription(channelId, scratch)
            } catch (e: Exception) {
                meta = emptyMap()
            }
        }
        // Fallback genre: HTML baru memakai label "Theme:" (key "Themes").
        if (scratch.genre.isNullOrBlank()) {
            scratch.genre = firstUsefulAnime(scratch.genre, meta["Themes"].orEmpty())
        }
        // Total episode: meta "Episodes" (paling akurat) -> jumlah posts kategori.
        var totalEpisodes = total
        val metaNumber = AnimeEpisodeLabelUtils.numericValue(meta["Episodes"].orEmpty())
        if (metaNumber > 0) totalEpisodes = metaNumber.toInt()
        return Info(scratch.genre.orEmpty().trim(), normalizeAnimeStatus(scratch.statusVideo), totalEpisodes)
    }

    private fun fetchLoverz(post: AnimePost): Info {
        val slug = post.slug?.trim()?.trim('/') ?: ""
        if (slug.isEmpty()) return Info("", "", 0)
        val body = JSONObject()
            .put("get", "top")
            .put("post_type", "1")
            .put("post_id", slug)
            .put("token", "")
            .toString()
            .toRequestBody("text/plain; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(LOVERZ_SERIES_URL + Uri.encode(slug))
            .header("user-agent", "Dart/3.9 (dart:io)")
            .header("accept", "application/json")
            .post(body)
            .build()
        val text = loverzClient.newCall(request).execute().use { it.body?.string().orEmpty() }
        if (text.isBlank()) throw IllegalStateException("empty loverz series response")
        val json = JSONObject(text)
        val data = json.optJSONArray("data") ?: throw IllegalStateException("no loverz data")
        if (data.length() == 0) throw IllegalStateException("empty loverz data")
        val item = data.optJSONObject(0) ?: throw IllegalStateException("bad loverz item")
        val genres = ArrayList<String>()
        val genreArray = item.optJSONArray("genre")
        if (genreArray != null) {
            for (i in 0 until genreArray.length()) {
                val g = genreArray.optString(i, "").trim()
                if (g.isNotEmpty() && !genres.contains(g)) genres.add(g)
            }
        }
        val status = normalizeAnimeStatus(item.optString("status", ""))
        val chapters = item.optJSONArray("chapter")
        val total = chapters?.length() ?: 0
        return Info(genres.joinToString(", "), status, total)
    }

    /**
     * Terapkan info ke post. Return true bila ada data yang benar-benar berubah;
     * dipakai untuk memutus infinite rebind loop (callback hanya bila berubah).
     */
    private fun apply(post: AnimePost, info: Info): Boolean {
        var changed = false
        if (post.genre.isNullOrBlank() && info.genre.isNotBlank()) {
            post.genre = info.genre
            changed = true
        }
        if (post.statusVideo.isNullOrBlank() && info.status.isNotBlank()) {
            post.statusVideo = info.status
            post.ongoing = !info.status.equals("Completed", true)
            changed = true
        }
        if (post.totalEpisodes <= 0 && info.totalEpisodes > 0) {
            post.totalEpisodes = info.totalEpisodes
            changed = true
        }
        return changed
    }
}
