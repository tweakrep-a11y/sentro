package miku.moe.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** API client untuk sumber Drakorku. */
public final class Drakorku {
    private static final String API_BASE = "https://kampretloh.top/v3//api";
    private static final String API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    public static final int PAGE_SIZE = 50;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    private static final Map<String, ArrayList<JSONObject>> CATEGORY_CACHE = new ConcurrentHashMap<>();
    private static volatile ArrayList<String> genreCache;

    private Drakorku() {}

    /** Six tab katalog utama; tab New Update berasal dari feed episode terbaru. */
    public static PageResult listing(String listing, int page) throws IOException {
        int targetPage = Math.max(1, page);
        String mode = listing == null ? "new-update" : listing;
        if ("new-update".equals(mode) || "latest".equals(mode)) return latest(targetPage);
        String sort = sortForListing(mode);
        ArrayList<JSONObject> all = categories(sort, "");
        return sliceCategories(all, targetPage);
    }

    public static PageResult search(String query, int page) throws IOException {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) return listing("az", page);

        // Endpoint search seharusnya menyaring kategori, tetapi beberapa versi API
        // mengembalikan katalog penuh. Selalu filter judul di sisi klien agar query
        // seperti "Maou" tidak menampilkan hasil yang sama dengan query lain.
        ArrayList<JSONObject> apiResults = categories("c.category_name ASC", value);
        ArrayList<JSONObject> filtered = filterCategoryTitle(apiResults, value);
        if (filtered.isEmpty()) {
            // Fallback bila parameter search di server diabaikan atau format search
            // server berbeda: filter katalog berdasarkan category_name, bukan tampilkan semuanya.
            filtered = filterCategoryTitle(categories("c.category_name ASC", ""), value);
        }
        return sliceCategories(filtered, Math.max(1, page));
    }

    private static ArrayList<JSONObject> filterCategoryTitle(List<JSONObject> input, String query) {
        ArrayList<JSONObject> result = new ArrayList<>();
        String needle = normalize(query);
        if (input == null || needle.isEmpty()) return result;
        for (JSONObject item : input) {
            if (item == null) continue;
            String title = normalize(item.optString("category_name", ""));
            if (title.contains(needle)) result.add(item);
        }
        return result;
    }

    /** Filter genre diterapkan ke kolom genre/status pada respons categories. */
    public static PageResult genre(String genre, int page) throws IOException {
        String target = normalize(genre);
        if (target.isEmpty()) return listing("az", page);
        ArrayList<JSONObject> all = categories("c.category_name ASC", "");
        ArrayList<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : all) {
            if (matchesGenre(item, target)) filtered.add(item);
        }
        return sliceCategories(filtered, Math.max(1, page));
    }

    public static ArrayList<String> genres() throws IOException {
        // Daftar genre yang diminta pengguna menjadi sumber kanonis agar daftar
        // tidak bergantung pada endpoint index yang kadang tidak lengkap.
        ArrayList<String> result = new ArrayList<>();
        Collections.addAll(result,
                "OnGoing", "Movie", "Drakor", "Dorama", "Dracin", "Drama Shorts",
                "Action", "Adventure", "Business", "Comedy", "Crime", "Documentary",
                "Drama", "Family", "Fantasy", "Food", "Historical", "Horror", "Law",
                "Life", "Mature", "Medical", "Melodrama", "Military", "Music", "Mystery",
                "Psychological", "Romance", "Sci-Fi", "Sitcom", "Sports", "Supernatural",
                "Thriller", "Variety Show", "War", "Wuxia", "Youth", "Zombies");
        return result;
    }

    /**
     * Memuat informasi drama beserta daftar episode. Jika dibuka dari tab
     * kategori, cari ID video terbaru berdasarkan cat_id sebelum memanggil
     * detail agar request detail tetap memakai vid yang valid.
     */
    public static DetailResult detail(AnimePost initial) throws IOException {
        if (initial == null || initial.categoryId <= 0) {
            throw new IOException("ID drama Drakorku tidak valid");
        }
        int categoryId = initial.categoryId;
        int videoId = initial.channelId;
        JSONObject detailJson = null;

        if (videoId > 0) {
            detailJson = requestDetail(videoId, categoryId, initial.categoryName);
        }
        if (!hasPost(detailJson)) {
            JSONObject found = findEpisodeForCategory(categoryId, initial.categoryName);
            if (found != null) {
                videoId = found.optInt("vid", -1);
                if (videoId > 0) detailJson = requestDetail(videoId, categoryId, initial.categoryName);
                if (!hasPost(detailJson)) {
                    try {
                        JSONObject fallback = new JSONObject();
                        fallback.put("post", found);
                        fallback.put("suggested", new JSONArray().put(found));
                        detailJson = fallback;
                    } catch (Exception ignored) { }
                }
            }
        }
        if (!hasPost(detailJson)) {
            // Fallback terakhir untuk server versi lama yang mungkin menerima
            // category ID pada parameter id.
            detailJson = requestDetail(categoryId, categoryId, initial.categoryName);
        }
        if (!hasPost(detailJson)) throw new IOException("Detail drama tidak ditemukan pada API Drakorku");

        JSONObject current = detailJson.optJSONObject("post");
        AnimePost post = videoPost(current, initial);
        post.sourceId = AnimeSettingsManager.SOURCE_DRAKORKU;
        post.categoryId = current.optInt("cat_id", categoryId);
        int resolvedVideoId = current.optInt("vid", videoId);
        post.channelId = resolvedVideoId > 0 ? resolvedVideoId : videoId;
        post.categoryName = firstUseful(current.optString("category_name", ""), initial.categoryName);
        post.imgUrl = firstUseful(current.optString("category_image", ""), firstUseful(current.optString("video_thumbnail", ""), initial.imgUrl));
        post.description = cleanSynopsis(firstUseful(current.optString("desc_anime", ""), firstUseful(current.optString("video_description", ""), initial.description)));

        ArrayList<String> genres = splitGenres(firstUseful(current.optString("genre", ""), initial.genre));
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        // Status, tahun, rating, jumlah episode, dan views sudah ditampilkan
        // oleh metadata umum AnimeDetailV1Fragment. Jangan tambahkan ulang di
        // bagian Informasi Anime untuk sumber Drakorku.
        put(rows, "Rating Umur", current.optString("content_rating", ""));
        String contentInfo = current.optString("info_content", "");
        if (!"0".equals(contentInfo.trim())) put(rows, "Info Konten", cleanHtml(contentInfo));
        put(rows, "Tanggal Update", firstUseful(current.optString("update_date_time", ""), current.optString("date_time", "")));
        String tag = current.optString("tag", "").trim();
        // Beberapa respons API mengisi tag dengan judul drama, bukan tag genre.
        if (!tag.isEmpty() && !tag.equalsIgnoreCase(post.categoryName == null ? "" : post.categoryName.trim())) {
            put(rows, "Tag", tag);
        }

        ArrayList<EpisodeResult> episodes = parseEpisodes(detailJson.optJSONArray("suggested"), current);
        if (episodes.isEmpty()) episodes = parseEpisodes(null, current);
        if (!episodes.isEmpty()) {
            post.totalEpisodes = Math.max(post.totalEpisodes, episodes.size());
            if (post.episodeCount.isEmpty()) post.episodeCount = String.valueOf(post.totalEpisodes);
        }
        return new DetailResult(post, post.description, genres, rows, episodes);
    }

    /** Resolusi tersedia di post: URL standar dan URL HD; tidak menganggap dub sebagai resolusi. */
    public static ArrayList<QualityResult> playback(String episodeId, int categoryId, String title) throws IOException {
        ArrayList<QualityResult> result = new ArrayList<>();
        String id = episodeId == null ? "" : episodeId.trim();
        if (id.isEmpty() || categoryId <= 0) return result;
        JSONObject json = requestDetail(id, categoryId, title);
        JSONObject post = json == null ? null : json.optJSONObject("post");
        if (post == null) return result;
        addQuality(result, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), post.optString("video_url", ""));
        addQuality(result, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD), post.optString("video_url_hd", ""));
        return result;
    }

    public static String sourceLabel() { return "Drakorku"; }

    private static PageResult latest(int page) throws IOException {
        Map<String, String> params = withApiKey(null);
        params.put("page", String.valueOf(page));
        params.put("count", String.valueOf(PAGE_SIZE));
        JSONObject json = parseJson(get("/get_videos", params));
        ArrayList<AnimePost> posts = new ArrayList<>();
        JSONArray array = json.optJSONArray("latest_anime");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                AnimePost post = videoPost(array.optJSONObject(i), null);
                if (post != null) posts.add(post);
            }
        }
        int total = json.optInt("count_total", -1);
        boolean more = total > 0 ? page * PAGE_SIZE < total : posts.size() >= PAGE_SIZE;
        return new PageResult(posts, more, posts.size());
    }

    private static ArrayList<JSONObject> categories(String sort, String search) throws IOException {
        String normalizedSearch = search == null ? "" : search.trim();
        String cacheKey = sort + "\u0000" + normalizedSearch.toLowerCase(Locale.ROOT);
        if (normalizedSearch.isEmpty()) {
            ArrayList<JSONObject> cached = CATEGORY_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        Map<String, String> params = withApiKey(null);
        params.put("search", normalizedSearch);
        params.put("sort", sort);
        JSONObject json = parseJson(get("/get_category_genre", params));
        JSONArray array = json.optJSONArray("categories");
        if (array == null) throw new IOException("Respons daftar kategori Drakorku tidak memiliki categories");
        ArrayList<JSONObject> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optInt("cid", -1) > 0 && useful(item.optString("category_name", ""))) result.add(item);
        }
        // Endpoint katalog mengembalikan ribuan objek. Cache hanya dua urutan
        // katalog kosong agar pemakaian heap tetap terkendali; hasil pencarian
        // tidak disimpan karena query bisa berubah pada setiap ketikan.
        if (normalizedSearch.isEmpty()) {
            CATEGORY_CACHE.put(cacheKey, result);
            if (CATEGORY_CACHE.size() > 2) {
                for (String key : CATEGORY_CACHE.keySet()) {
                    if (!key.equals(cacheKey)) { CATEGORY_CACHE.remove(key); break; }
                }
            }
        }
        return result;
    }

    private static PageResult sliceCategories(List<JSONObject> all, int page) {
        int start = Math.max(0, page - 1) * PAGE_SIZE;
        if (start >= all.size()) return new PageResult(new ArrayList<>(), false, 0);
        int end = Math.min(start + PAGE_SIZE, all.size());
        ArrayList<AnimePost> result = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            AnimePost post = categoryPost(all.get(i));
            if (post != null) result.add(post);
        }
        return new PageResult(result, end < all.size(), end - start);
    }

    private static AnimePost categoryPost(JSONObject item) {
        if (item == null) return null;
        int categoryId = item.optInt("cid", item.optInt("cat_id", -1));
        String title = item.optString("category_name", "").trim();
        if (categoryId <= 0 || title.isEmpty()) return null;
        AnimePost post = new AnimePost(firstUseful(item.optString("category_image", ""), item.optString("image_landscape", "")), title, categoryId, -1);
        post.sourceId = AnimeSettingsManager.SOURCE_DRAKORKU;
        fillMetadata(post, item);
        return post;
    }

    private static AnimePost videoPost(JSONObject item, AnimePost fallback) {
        if (item == null) return null;
        int categoryId = item.optInt("cat_id", item.optInt("cid", fallback == null ? -1 : fallback.categoryId));
        int videoId = item.optInt("vid", fallback == null ? -1 : fallback.channelId);
        String title = firstUseful(item.optString("category_name", ""), fallback == null ? "" : fallback.categoryName);
        if (categoryId <= 0 || title.isEmpty()) return null;
        AnimePost post = new AnimePost(firstUseful(item.optString("category_image", ""), firstUseful(item.optString("image_landscape", ""), firstUseful(item.optString("video_thumbnail", ""), fallback == null ? "" : fallback.imgUrl))), title, categoryId, videoId);
        post.sourceId = AnimeSettingsManager.SOURCE_DRAKORKU;
        fillMetadata(post, item);
        post.channelName = firstUseful(item.optString("video_title", ""), fallback == null ? "" : fallback.channelName);
        String count = numberText(item.opt("video_count"));
        post.totalEpisodes = Math.max(parseInt(count), fallback == null ? 0 : fallback.totalEpisodes);
        post.episodeCount = firstUseful(count, fallback == null ? "" : fallback.episodeCount);
        post.created = firstUseful(item.optString("date_time", ""), firstUseful(item.optString("update_date_time", ""), fallback == null ? "" : fallback.created));
        post.countView = firstUseful(numberText(item.opt("total_views")), fallback == null ? "" : fallback.countView);
        post.description = cleanSynopsis(firstUseful(item.optString("desc_anime", ""), firstUseful(item.optString("video_description", ""), fallback == null ? "" : fallback.description)));
        return post;
    }

    private static void fillMetadata(AnimePost post, JSONObject item) {
        post.genre = item.optString("genre", "").trim();
        post.rating = numberText(item.opt("rating"));
        post.year = item.optInt("year", 0);
        post.statusVideo = normalizeStatus(item.optString("status_video", ""));
        post.ongoing = isOngoing(post.statusVideo);
        post.episodeCount = numberText(item.opt("video_count"));
        post.totalEpisodes = parseInt(post.episodeCount);
        post.created = firstUseful(item.optString("update_date_time", ""), item.optString("new_date_time", ""));
        post.countView = numberText(item.opt("total_views"));
        post.description = cleanSynopsis(item.optString("desc_anime", ""));
    }

    private static ArrayList<EpisodeResult> parseEpisodes(JSONArray suggested, JSONObject current) {
        LinkedHashMap<Integer, EpisodeResult> byId = new LinkedHashMap<>();
        if (suggested != null) {
            for (int i = 0; i < suggested.length(); i++) {
                JSONObject item = suggested.optJSONObject(i);
                EpisodeResult episode = episode(item, i + 1, current);
                if (episode != null) byId.put(episode.id, episode);
            }
        }
        EpisodeResult currentEpisode = episode(current, byId.size() + 1, current);
        if (currentEpisode != null) byId.put(currentEpisode.id, currentEpisode);
        ArrayList<EpisodeResult> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparingInt((EpisodeResult e) -> e.episodeNumber).thenComparingInt(e -> e.id));
        return result;
    }

    private static EpisodeResult episode(JSONObject item, int fallbackNumber, JSONObject parent) {
        if (item == null) return null;
        int id = item.optInt("vid", item.optInt("id", -1));
        if (id <= 0) return null;
        String show = firstUseful(item.optString("category_name", ""), parent == null ? "" : parent.optString("category_name", ""));
        String title = firstUseful(item.optString("video_title", ""), "Episode " + fallbackNumber);
        if (!show.isEmpty() && title.toLowerCase(Locale.ROOT).startsWith(show.toLowerCase(Locale.ROOT))) title = title.substring(show.length()).replaceFirst("^[\\s:–—-]+", "").trim();
        if (title.isEmpty()) title = "Episode " + fallbackNumber;
        Matcher match = Pattern.compile("(?i)(?:eps?|episode|ep)\\s*[-:#]*\\s*(\\d+)").matcher(title);
        int number = fallbackNumber;
        if (match.find()) {
            try { number = Integer.parseInt(match.group(1)); } catch (Exception ignored) { }
        }
        String thumbnail = firstUseful(item.optString("video_thumbnail", ""), item.optString("category_image", ""));
        // Only use an explicit episode release/air date field. The HAR exposes
        // date_time as a server timestamp, not an official episode release date.
        // If the API has no release-date field, leave subtitle empty (the UI hides it).
        String releaseDate = firstUseful(item.optString("episode_release_date", ""),
                firstUseful(item.optString("release_date", ""),
                firstUseful(item.optString("episode_air_date", ""), item.optString("air_date", ""))));
        String subtitle = formatEpisodeReleaseDate(releaseDate);
        return new EpisodeResult(id, "Episode " + number, subtitle, String.valueOf(id), thumbnail, number);
    }

    /** Format only an actual per-video API date. Missing dates remain empty. */
    private static String formatEpisodeReleaseDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value) || "-".equals(value)) return "";
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat input = new SimpleDateFormat(pattern, Locale.ROOT);
                input.setLenient(false);
                Date parsed = input.parse(value);
                if (parsed != null) return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(parsed);
            } catch (Exception ignored) { }
        }
        // If the API supplied a date we don't recognize, show that raw date rather
        // than falsely filling the field with a drama title.
        return value.matches("\\d{4}-\\d{2}-\\d{2}.*") ? value.substring(0, 10) : "";
    }

    private static JSONObject findEpisodeForCategory(int categoryId, String title) throws IOException {
        // Endpoint paling spesifik: bila API mendukung filter cat_id, gunakan dulu.
        Map<String, String> filtered = withApiKey(null);
        filtered.put("cat_id", String.valueOf(categoryId));
        filtered.put("page", "1");
        filtered.put("count", String.valueOf(PAGE_SIZE));
        JSONObject response = tryJsonGet("/get_videos", filtered);
        JSONObject found = matchingEpisode(response, categoryId);
        if (found != null) return found;

        // Beberapa versi API menggunakan search alih-alih cat_id untuk feed video.
        if (useful(title)) {
            Map<String, String> search = withApiKey(null);
            search.put("search", title.trim());
            search.put("page", "1");
            search.put("count", String.valueOf(PAGE_SIZE));
            found = matchingEpisode(tryJsonGet("/get_videos", search), categoryId);
            if (found != null) return found;
        }

        // Fallback terbatas untuk versi endpoint yang mengabaikan parameter filter.
        for (int page = 1; page <= 8; page++) {
            Map<String, String> params = withApiKey(null);
            params.put("page", String.valueOf(page));
            params.put("count", String.valueOf(PAGE_SIZE));
            response = tryJsonGet("/get_videos", params);
            found = matchingEpisode(response, categoryId);
            if (found != null) return found;
            if (response == null) break;
            int total = response.optInt("count_total", -1);
            JSONArray arr = response.optJSONArray("latest_anime");
            if ((arr == null || arr.length() < PAGE_SIZE) || (total > 0 && page * PAGE_SIZE >= total)) break;
        }
        return null;
    }

    private static JSONObject matchingEpisode(JSONObject response, int categoryId) {
        if (response == null) return null;
        JSONArray array = response.optJSONArray("latest_anime");
        if (array == null) array = response.optJSONArray("videos");
        if (array == null) return null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optInt("cat_id", item.optInt("cid", -1)) == categoryId && item.optInt("vid", -1) > 0) return item;
        }
        return null;
    }

    private static JSONObject requestDetail(int videoId, int categoryId, String title) throws IOException {
        return requestDetail(String.valueOf(videoId), categoryId, title);
    }

    private static JSONObject requestDetail(String videoId, int categoryId, String title) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", videoId == null ? "" : videoId.trim());
        params.put("cat_id", String.valueOf(categoryId));
        params.put("search", title == null ? "" : title.trim());
        return tryJsonGet("/get_post_detail", params);
    }

    private static JSONObject tryJsonGet(String path, Map<String, String> params) throws IOException {
        try {
            return parseJson(get(path, params));
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean hasPost(JSONObject value) {
        return value != null && value.optJSONObject("post") != null;
    }

    private static Map<String, String> withApiKey(Map<String, String> input) {
        Map<String, String> params = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        params.put("api_key", API_KEY);
        return params;
    }

    private static String get(String path, Map<String, String> params) throws IOException {
        HttpUrl parsed = HttpUrl.parse(API_BASE + path);
        if (parsed == null) throw new IOException("URL API Drakorku tidak valid");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        // Samakan header dengan request API resmi yang terekam di HAR Drakorku.
        // Server dapat mengirim halaman proteksi/response berbeda bila Data-Agent
        // dan media type khusus dari API ini tidak disertakan.
        Request request = new Request.Builder().url(builder.build())
                .header("Cache-Control", "max-age=0")
                .header("Data-Agent", "Your Videos Channel")
                .header("Accept", "application/vnd.yourapi.v1.full+json")
                .header("User-Agent", "Dalvik/7.1.12.1.0 (com.drakorku.dramakoreasubindo U; Android ; 20175 Build/NMF260)")
                .get().build();
        try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " pada API Drakorku");
            return response.body() == null ? "" : response.body().string();
        }
    }

    private static JSONObject parseJson(String raw) throws IOException {
        String body = raw == null ? "" : raw;
        // Beberapa gateway menambahkan BOM atau spasi sebelum body JSON.
        if (!body.isEmpty() && body.charAt(0) == '\uFEFF') body = body.substring(1);
        body = body.trim();
        if (body.startsWith(")]}'" + ",")) body = body.substring(5).trim();
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.startsWith("<head") || lower.startsWith("<body")) {
                throw new IOException("Server Drakorku mengirim HTML, bukan JSON (kemungkinan halaman proteksi atau redirect). Periksa header/API server.", e);
            }
            String preview = body.replace('\r', ' ').replace('\n', ' ').trim();
            if (preview.length() > 140) preview = preview.substring(0, 140) + "…";
            throw new IOException("Respons API Drakorku bukan JSON valid. Awal respons: " + (preview.isEmpty() ? "(kosong)" : preview), e);
        }
    }

    private static String sortForListing(String listing) {
        switch (listing) {
            case "popular": return "c.total_views DESC";
            case "rating":
            case "top-rating": return "c.rating DESC";
            case "za":
            case "top-za": return "c.category_name DESC";
            case "oldest":
            case "date-added-oldest": return "c.cid ASC";
            case "az":
            case "a-z":
            default: return "c.category_name ASC";
        }
    }

    private static boolean matchesGenre(JSONObject item, String target) {
        if (item == null || target == null || target.trim().isEmpty()) return false;
        String normalizedTarget = normalizeGenre(target);
        String status = normalizeGenre(firstUseful(item.optString("status_video", ""), item.optString("status", "")));
        String description = item.optString("desc_anime", "");
        String genreValue = firstUseful(item.optString("genre", ""), firstUseful(item.optString("genre_anime", ""), firstUseful(item.optString("genres", ""), extractGenresFromDescription(description))));
        String genre = normalizeGenre(genreValue);
        String tags = normalizeGenre(firstUseful(item.optString("tag", ""), firstUseful(item.optString("category_type", ""), item.optString("type", ""))));
        String name = normalizeGenre(item.optString("category_name", ""));
        String descriptionText = normalizeGenre(Jsoup.parse(description).text());

        if ("ongoing".equals(normalizedTarget)) {
            return status.contains("ongoing") || status.contains("on going") || status.contains("on air") || status.contains("airing");
        }
        if ("movie".equals(normalizedTarget)) {
            return status.contains("movie") || genreTokenMatch(genre, normalizedTarget) || name.equals("movie");
        }
        // These four labels are catalogue categories, not always literal genres.
        // Their meaning comes from explicit API fields/description metadata.
        if ("drakor".equals(normalizedTarget)) {
            return countryContains(descriptionText, "south korea") || countryContains(descriptionText, "korea")
                    || countryContains(normalizeGenre(item.optString("country", "")), "south korea");
        }
        if ("dorama".equals(normalizedTarget)) {
            return countryContains(descriptionText, "japan") || countryContains(normalizeGenre(item.optString("country", "")), "japan");
        }
        if ("dracin".equals(normalizedTarget)) {
            return countryContains(descriptionText, "china") || countryContains(descriptionText, "cina")
                    || countryContains(descriptionText, "taiwan") || countryContains(descriptionText, "hong kong")
                    || countryContains(normalizeGenre(item.optString("country", "")), "china")
                    || countryContains(normalizeGenre(item.optString("country", "")), "cina");
        }
        if ("drama shorts".equals(normalizedTarget)) {
            return genreTokenMatch(genre, normalizedTarget) || genreTokenMatch(tags, normalizedTarget)
                    || descriptionText.contains("type drama shorts") || descriptionText.contains("drama shorts");
        }
        // Token matching handles comma/dot/pipe separated API genre fields and avoids
        // accidental substring matches (for example, "War" inside another word).
        if (genreTokenMatch(genre, normalizedTarget) || genreTokenMatch(status, normalizedTarget)
                || genreTokenMatch(tags, normalizedTarget)) return true;
        return genreTokenMatch(name, normalizedTarget);
    }

    private static boolean countryContains(String descriptionOrCountry, String country) {
        if (descriptionOrCountry == null || country == null || country.isEmpty()) return false;
        String value = descriptionOrCountry.toLowerCase(Locale.ROOT);
        String term = country.toLowerCase(Locale.ROOT);
        return value.contains("country " + term) || value.equals(term) || value.contains(" country " + term + " ");
    }

    private static String extractGenresFromDescription(String description) {
        if (description == null || description.isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?i)Genres\\s*:\\s*([^<\\r\\n]+)").matcher(description);
        return matcher.find() ? Jsoup.parse(matcher.group(1)).text().trim() : "";
    }

    private static boolean genreTokenMatch(String haystack, String target) {
        if (haystack == null || target == null || haystack.isEmpty() || target.isEmpty()) return false;
        String[] tokens = haystack.split("[,|/;·]+|\\s{2,}");
        for (String token : tokens) {
            String normalized = normalizeGenre(token);
            if (normalized.equals(target)) return true;
        }
        // Genre API kadang menyimpan daftar dipisahkan satu spasi. Cek batas kata.
        return (" " + haystack + " ").contains(" " + target + " ");
    }

    private static String normalizeGenre(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT).replace('&', ' ')
                .replace('_', ' ').replace('-', ' ').replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        if ("on going".equals(text) || "on going status".equals(text)) return "ongoing";
        if ("sci fi".equals(text) || "science fiction".equals(text)) return "sci fi";
        if ("drama short".equals(text) || "short drama".equals(text)) return "drama shorts";
        return text;
    }

    private static void addGenre(LinkedHashSet<String> names, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) names.add(text);
    }

    private static ArrayList<String> splitGenres(String value) {
        ArrayList<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return result;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String piece : value.split("[,|]")) {
            String item = piece.trim();
            if (!item.isEmpty()) unique.add(item);
        }
        result.addAll(unique);
        return result;
    }

    private static void addQuality(ArrayList<QualityResult> result, String quality, String label, String url) {
        if (playable(url)) result.add(new QualityResult(quality, label, url.trim()));
    }

    private static void put(LinkedHashMap<String, String> rows, String key, String value) {
        if (useful(value)) rows.put(key, value.trim());
    }

    private static String numberText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (n == Math.rint(n)) return String.valueOf((long) n);
        }
        String text = String.valueOf(value).trim();
        return "0".equals(text) || "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); } catch (Exception ignored) { return 0; }
    }

    private static String cleanHtml(String value) {
        if (!useful(value)) return "";
        return Jsoup.parse(value.trim()).text().trim();
    }

    /**
     * desc_anime is an HTML article containing metadata followed by a Synopsis section.
     * Extract only the content belonging to that section; never flatten the whole article
     * into the synopsis field.
     */
    private static String cleanSynopsis(String value) {
        if (!useful(value)) return "";
        org.jsoup.nodes.Document document = Jsoup.parse(value.trim());
        Element heading = null;
        for (Element candidate : document.select("strong, b, h1, h2, h3, h4")) {
            if ("synopsis".equalsIgnoreCase(candidate.text().trim().replaceAll(":$", ""))) {
                heading = candidate;
                break;
            }
        }
        if (heading == null) {
            String plain = document.text().trim();
            String lower = plain.toLowerCase(Locale.ROOT);
            boolean metadataBlock = lower.contains("alternative titles")
                    || lower.contains("native title:") || lower.contains("also known as:")
                    || lower.contains("content rating:") || lower.contains("original network:");
            return metadataBlock ? "" : plain;
        }

        StringBuilder synopsis = new StringBuilder();
        // Common API format: <p><strong>Synopsis</strong><br>actual plot...</p>
        Element parent = heading.parent();
        if (parent != null) {
            boolean afterHeading = false;
            for (Node node : parent.childNodes()) {
                if (node == heading || (node instanceof Element && ((Element) node).equals(heading))) {
                    afterHeading = true;
                    continue;
                }
                if (!afterHeading) continue;
                String part = node instanceof Element ? ((Element) node).text() : node.toString();
                part = Jsoup.parse(part).text().trim();
                if (!part.isEmpty() && !"Synopsis".equalsIgnoreCase(part.replaceAll(":$", ""))) {
                    appendSynopsis(synopsis, part);
                }
            }
        }
        // If the plot is placed in following paragraph(s), collect them until the next
        // labelled metadata section or a source attribution.
        Element block = parent;
        if (block != null) {
            for (Element next = block.nextElementSibling(); next != null; next = next.nextElementSibling()) {
                String label = next.select("strong, b, h1, h2, h3, h4").text().trim();
                if (!label.isEmpty()) break;
                String part = next.text().trim();
                if (part.isEmpty()) continue;
                if (part.matches("(?i)^(source|sumber)\\s*:.*")) break;
                appendSynopsis(synopsis, part);
            }
        }
        String result = synopsis.toString().trim();
        result = result.replaceAll("(?i)\\s*\\((?:source|sumber)\\s*:[^)]*\\)\\s*$", "").trim();
        result = result.replaceAll("(?i)\\s*(?:source|sumber)\\s*:[^\\n]*$", "").trim();
        return result;
    }

    private static void appendSynopsis(StringBuilder target, String part) {
        if (part == null) return;
        String clean = part.trim();
        if (clean.isEmpty()) return;
        if (target.length() > 0) target.append(' ');
        target.append(clean);
    }

    private static String normalizeStatus(String value) {
        String raw = value == null ? "" : value.trim();
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        if (lower.contains("complete") || lower.contains("finished") || lower.equals("finish")) return "Completed";
        if (lower.contains("ongoing") || lower.contains("on going")) return "Ongoing";
        return raw;
    }

    private static boolean isOngoing(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("ongoing") || lower.contains("on-going") || lower.contains("on going");
    }

    private static String firstUseful(String first, String second) {
        if (useful(first)) return first.trim();
        return useful(second) ? second.trim() : "";
    }

    private static boolean useful(String value) {
        String text = value == null ? "" : value.trim();
        return !text.isEmpty() && !"null".equalsIgnoreCase(text) && !"-".equals(text);
    }

    private static boolean playable(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }

    public static final class PageResult {
        public final ArrayList<AnimePost> items;
        public final boolean hasMore;
        public final int rawCount;
        PageResult(ArrayList<AnimePost> items, boolean hasMore, int rawCount) { this.items = items; this.hasMore = hasMore; this.rawCount = rawCount; }
    }

    public static final class DetailResult {
        public final AnimePost post;
        public final String description;
        public final ArrayList<String> genres;
        public final LinkedHashMap<String, String> rows;
        public final ArrayList<EpisodeResult> episodes;
        DetailResult(AnimePost post, String description, ArrayList<String> genres, LinkedHashMap<String, String> rows, ArrayList<EpisodeResult> episodes) {
            this.post = post; this.description = description; this.genres = genres; this.rows = rows; this.episodes = episodes;
        }
    }

    public static final class EpisodeResult {
        public final int id;
        public final String title;
        public final String subtitle;
        public final String episodeId;
        public final String thumbnail;
        public final int episodeNumber;
        EpisodeResult(int id, String title, String subtitle, String episodeId, String thumbnail, int episodeNumber) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.episodeId = episodeId; this.thumbnail = thumbnail; this.episodeNumber = episodeNumber;
        }
    }

    public static final class QualityResult {
        public final String quality;
        public final String label;
        public final String url;
        QualityResult(String quality, String label, String url) { this.quality = quality; this.label = label; this.url = url; }
    }
}
