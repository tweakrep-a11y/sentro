package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Parser Ikiru untuk domain https://09.ikiru.wtf/ (API baru, bukan WordPress lagi).
 *
 * Endpoint (terverifikasi live 2026-10-07):
 * - Tab Terbaru        : GET /api/public/library/search?page={p}&sortBy=updated&sort=desc   (21/halaman)
 * - Tab Populer        : GET /api/public/library/search?page={p}&sortBy=popular&sort=desc
 * - Tab Rating         : GET /api/public/library/search?page={p}&sortBy=rating&sort=desc
 * - Tab A-Z            : GET /api/public/library/search?page={p}&sortBy=title&sort=asc
 * - Tab Baru Ditambahkan: GET /api/public/library/search?page={p}&sortBy=created&sort=desc
 * - Tab Project        : GET /api/public/manga/project?page={p}&layout=vertical&limit=24     (24/halaman)
 * - Tab Manga/Manhwa/Manhua: + type=MANGA|MANHWA|MANHUA&sortBy=popular&sort=desc (fetch langsung dari endpoint)
 * - Pencarian          : + query={q}
 * - Filter genre       : + genre={uuid}
 * - Filter type        : + type=MANGA|MANHWA|MANHUA
 * - Filter status      : + status=ONGOING|COMPLETED|CANCELLED|HIATUS
 * - Daftar genre       : GET /api/user/genres  -> data.allGenres[{name,id}]
 * - Detail manga       : GET /api/public/manga/{slug}
 * - Reader chapter     : halaman /manga/{slug}/chapter-{nomor} (SSR, gambar langsung di <img src="https://cdn.ikiru.id/...">)
 */
public class Ikiru extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://09.ikiru.wtf";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, Boolean> LIST_NEXT_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, 24L * 60L * 60L * 1000L);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Ikiru"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        MangaCoroutines.io(() -> {
            try {
                int safePage = Math.max(1, page);
                String key = safePage + "|" + (sort == null ? "" : sort) + "|" + (query == null ? "" : query) + "|" + (genre == null ? "" : genre);
                ArrayList<MangaPost> cached = LIST_CACHE.get(key);
                if (cached != null) {
                    Boolean cachedHasNext = LIST_NEXT_CACHE.get(key);
                    MangaCoroutines.main(() -> cb.onSuccess(new ArrayList<>(cached), cachedHasNext != null && cachedHasNext));
                    return;
                }
                SearchCall call = buildSearchCall(safePage, sort, query, genre);
                String json = execute(call.url, base() + "/");
                ArrayList<MangaPost> out = parseListingJson(json, call.project);
                int total = readTotal(json, call.project);
                boolean hasNext = total > 0 ? (long) safePage * call.pageSize < total : out.size() >= call.pageSize;
                if (out.isEmpty()) throw new IOException("Parser Ikiru menghasilkan 0 item dari " + call.url);
                LIST_CACHE.put(key, new ArrayList<>(out));
                LIST_NEXT_CACHE.put(key, hasNext);
                MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                ArrayList<GenreItem> out = fetchGenres();
                GENRE_CACHE.put("genres", new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch(Exception e) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && (p.latestChapter == null || p.latestChapter.trim().isEmpty()) && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || (p.latestChapter != null && !p.latestChapter.trim().isEmpty()) || p.slug == null || p.slug.isEmpty()) continue;
            chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                    if (chapters != null && !chapters.isEmpty()) {
                        MangaChapter newest = chapters.get(0);
                        for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                        p.latestChapter = newest.title == null || newest.title.isEmpty() ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                        p.latestChapterDate = newest.date == null ? "" : newest.date;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        MangaCoroutines.io(() -> {
            try {
                MangaPost post = parseDetailJson(fetchDetailJson(cleanSlug), cleanSlug);
                if (post == null) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru kosong")); return; }
                DETAIL_CACHE.put(cleanSlug, post);
                MangaCoroutines.main(() -> cb.onSuccess(post, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru gagal dibaca")); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                ArrayList<MangaChapter> out = parseChaptersJson(fetchDetailJson(cleanSlug), cleanSlug);
                if (out.isEmpty()) throw new IOException("Parser chapter Ikiru menghasilkan 0 item");
                CHAPTER_CACHE.put(cleanSlug, new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Ikiru gagal dibaca")); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = normalizeMangaSlug(slug) + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Ikiru tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                if (out.isEmpty()) throw new IOException("Parser reader Ikiru menghasilkan 0 gambar");
                                for (String pageUrl : out) MangaImageLoader.registerImageReferer(pageUrl, base() + "/");
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Ikiru gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private static class SearchCall {
        String url;
        boolean project;
        int pageSize;
    }

    private SearchCall buildSearchCall(int page, String sort, String query, String genre) {
        int safePage = Math.max(1, page);
        String cleanSort = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String cleanQuery = query == null ? "" : query.trim();
        String cleanGenre = genre == null ? "" : genre.trim();
        SearchCall call = new SearchCall();
        if (("project".equals(cleanSort) || "projects".equals(cleanSort)) && cleanQuery.isEmpty() && cleanGenre.isEmpty()) {
            call.project = true;
            call.pageSize = 24;
            call.url = HttpUrl.parse(base() + "/api/public/manga/project").newBuilder()
                    .addQueryParameter("page", String.valueOf(safePage))
                    .addQueryParameter("layout", "vertical")
                    .addQueryParameter("limit", "24")
                    .build().toString();
            return call;
        }
        call.project = false;
        call.pageSize = 21;
        // Tab Manga/Manhwa/Manhua: fetch langsung dari endpoint type=...&sortBy=popular&sort=desc
        String typeTab = typeParam(cleanSort);
        String sortBy = typeTab.isEmpty() ? searchSortBy(cleanSort) : "popular";
        String sortDir = typeTab.isEmpty() ? searchSortDir(cleanSort) : "desc";
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/api/public/library/search").newBuilder()
                .addQueryParameter("page", String.valueOf(safePage))
                .addQueryParameter("sortBy", sortBy)
                .addQueryParameter("sort", sortDir);
        if (!typeTab.isEmpty()) builder.addQueryParameter("type", typeTab);
        if (!cleanQuery.isEmpty()) builder.addQueryParameter("query", cleanQuery);
        applyGenreFilter(builder, cleanGenre);
        call.url = builder.build().toString();
        return call;
    }

    private static String searchSortBy(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("rating".equals(s) || "rate".equals(s)) return "rating";
        if ("updated".equals(s) || "latest".equals(s)) return "updated";
        if ("created".equals(s) || "added".equals(s) || "new".equals(s) || "newest".equals(s)) return "created";
        if ("title".equals(s) || "az".equals(s) || "za".equals(s)) return "title";
        return "popular";
    }

    private static String searchSortDir(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        return "az".equals(s) ? "asc" : "desc";
    }

    private static void applyGenreFilter(HttpUrl.Builder builder, String rawGenre) {
        String value = rawGenre == null ? "" : rawGenre.trim();
        if (value.isEmpty()) return;
        for (String part : value.split("\\|")) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty()) continue;
            String key = "genre";
            String id = item;
            int split = item.indexOf(':');
            if (split > 0 && split < item.length() - 1) {
                key = item.substring(0, split).trim().toLowerCase(Locale.ROOT);
                id = item.substring(split + 1).trim();
            }
            if ("genre".equals(key)) {
                if (!id.isEmpty()) builder.addQueryParameter("genre", id);
            } else if ("type".equals(key)) {
                String t = typeParam(id);
                if (!t.isEmpty()) builder.addQueryParameter("type", t);
            } else if ("status".equals(key)) {
                String st = statusParam(id);
                if (!st.isEmpty()) builder.addQueryParameter("status", st);
            }
        }
    }

    private static String typeParam(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("manhwa".equals(v)) return "MANHWA";
        if ("manhua".equals(v)) return "MANHUA";
        if ("manga".equals(v)) return "MANGA";
        return "";
    }

    private static String statusParam(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("ongoing".equals(v)) return "ONGOING";
        if ("completed".equals(v)) return "COMPLETED";
        if ("cancelled".equals(v)) return "CANCELLED";
        if ("hiatus".equals(v) || "on-hiatus".equals(v) || "onhiatus".equals(v)) return "HIATUS";
        return "";
    }

    private ArrayList<MangaPost> parseListingJson(String json, boolean project) {
        ArrayList<MangaPost> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject data = getObject(root, "data");
            JsonArray items = getArray(data, project ? "project" : "mangas");
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (JsonElement element : items) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String slug = getString(object, "slug");
                if (slug.isEmpty() || !seen.add(slug)) continue;
                String title = cleanTitle(getString(object, "title"));
                if (title.isEmpty()) continue;
                String cover = normalizeImageUrl(getString(object, "featuredImage"));
                if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/");
                String synopsis = project ? "" : getString(object, "description").replaceAll("\\s+", " ").trim();
                String type = typeLabel(getString(object, "type"));
                String genre = joinGenreNames(getObject(object, "metadata"));
                String latest = "";
                String date = "";
                JsonArray chapters = getArray(object, "chapter");
                if (chapters.size() > 0 && chapters.get(0).isJsonObject()) {
                    JsonObject ch = chapters.get(0).getAsJsonObject();
                    double number = getDouble(ch, "number", Double.NaN);
                    if (!Double.isNaN(number)) latest = "Chapter " + formatChapterNumber(number);
                    date = datePart(getString(ch, "updatedAt"));
                }
                MangaPost post = new MangaPost("/manga/" + slug + "/", title, cover, "", "", synopsis, genre, type, latest, date)
                        .withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
                out.add(post);
            }
        } catch(Exception ignored) { }
        return out;
    }

    private static int readTotal(String json, boolean project) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return getInt(getObject(root, "data"), "total", 0);
        } catch(Exception ignored) { return 0; }
    }

    private ArrayList<GenreItem> fetchGenres() throws Exception {
        String json = execute(base() + "/api/user/genres", base() + "/");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray all = getArray(getObject(root, "data"), "allGenres");
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : all) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = getString(object, "name");
            String id = getString(object, "id");
            if (!name.isEmpty() && !id.isEmpty() && seen.add(id)) out.add(new GenreItem(name, "genre:" + id));
        }
        if (out.isEmpty()) throw new IOException("Genre Ikiru kosong");
        appendStaticFilters(out);
        return out;
    }

    private static void appendStaticFilters(ArrayList<GenreItem> out) {
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        out.add(new GenreItem("Ongoing", "status:ongoing"));
        out.add(new GenreItem("Completed", "status:completed"));
        out.add(new GenreItem("Cancelled", "status:cancelled"));
        out.add(new GenreItem("On Hiatus", "status:hiatus"));
    }

    private String fetchDetailJson(String slug) throws Exception {
        String json = execute(base() + "/api/public/manga/" + slug, base() + "/manga/" + slug + "/");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            String message = getString(root, "message");
            throw new IOException(message.isEmpty() ? "Detail Ikiru tidak ditemukan" : message);
        }
        return json;
    }

    private MangaPost parseDetailJson(String json, String slug) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject data = getObject(root, "data");
            if (data.entrySet().isEmpty()) return null;
            String title = cleanTitle(getString(data, "title"));
            if (title.isEmpty()) return null;
            String synopsis = Jsoup.parseBodyFragment(getString(data, "description")).wholeText().trim().replaceAll("\\s+", " ");
            String cover = normalizeImageUrl(firstNonEmpty(getString(data, "featuredImage"), getString(data, "backgroundImage")));
            if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/manga/" + slug + "/");
            JsonObject metadata = getObject(data, "metadata");
            String genre = joinNames(getArray(metadata, "genre"));
            String author = joinNames(getArray(metadata, "author"));
            String artist = joinNames(getArray(metadata, "artist"));
            if (!artist.isEmpty() && !author.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))) {
                author = (author.isEmpty() ? artist : author + ", " + artist);
            }
            String type = typeLabel(getString(data, "type"));
            String status = normalizeStatus(getString(data, "status"));
            ArrayList<MangaChapter> chapters = parseChaptersJson(json, slug);
            MangaChapter newest = newestChapter(chapters);
            JsonObject chapterWrap = getObject(data, "chapters");
            int total = getInt(chapterWrap, "count", chapters.size());
            if (total <= 0) total = chapters.size();
            MangaPost post = new MangaPost("/manga/" + slug + "/", title, cover, author, status, synopsis, genre, type,
                    newest == null ? "" : newest.title, newest == null ? "" : newest.date)
                    .withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
            post.info = android.text.TextUtils.join(", ", readStringList(metadata, "alternateTitles"));
            post.totalChapters = total;
            return post;
        } catch(Exception ignored) { return null; }
    }

    private ArrayList<MangaChapter> parseChaptersJson(String json, String mangaSlug) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject data = getObject(root, "data");
            JsonArray chapters = getArray(getObject(data, "chapters"), "chapters");
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (JsonElement element : chapters) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                double number = getDouble(object, "number", Double.NaN);
                if (Double.isNaN(number)) continue;
                float index = (float) number;
                String readerPath = "/manga/" + mangaSlug + "/chapter-" + formatChapterNumber(number);
                if (!seen.add(readerPath)) continue;
                String title = cleanChapterTitle(getString(object, "title"));
                if (title.isEmpty()) title = "Chapter " + formatChapterNumber(number);
                String date = datePart(firstNonEmpty(getString(object, "createdAt"), getString(object, "updatedAt")));
                out.add(new MangaChapter(readerPath, index, title, date));
            }
        } catch(Exception ignored) { }
        return out;
    }

    private MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter newest = chapters.get(0);
        for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
        return newest;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (document == null) return out;
        Elements images = document.select("img[src*=cdn.ikiru.id/]");
        for (Element img : images) {
            String url = normalizeImageUrl(img.attr("abs:src"));
            if (url.startsWith("https://") && seen.add(url)) out.add(url);
        }
        return out;
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = request(url, base() + "/").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                Document document = Jsoup.parse(body, url);
                MAIN.post(() -> cb.onSuccess(document, false));
            }
        });
    }

    private String execute(String url) throws Exception { return execute(url, base() + "/"); }

    private String execute(String url, String referer) throws Exception {
        Response response = client.newCall(request(url, referer).cacheControl(CacheControl.FORCE_NETWORK).build()).execute();
        String body = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        return body;
    }

    private Request.Builder request(String url, String referer) {
        return new Request.Builder().url(url)
                .header("Referer", referer == null || referer.trim().isEmpty() ? base() + "/" : referer)
                .header("Origin", base())
                .header("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.7,en-US;q=0.6,en;q=0.5")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36");
    }

    private static String joinGenreNames(JsonObject metadata) {
        return joinNames(getArray(metadata, "genre"));
    }

    private static String joinNames(JsonArray items) {
        ArrayList<String> values = new ArrayList<>();
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            String name = getString(element.getAsJsonObject(), "name");
            if (!name.isEmpty()) values.add(name);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static ArrayList<String> readStringList(JsonObject object, String name) {
        ArrayList<String> values = new ArrayList<>();
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) return values;
        for (JsonElement element : object.getAsJsonArray(name)) {
            if (element != null && !element.isJsonNull()) {
                String v = element.getAsString().trim();
                if (!v.isEmpty()) values.add(v);
            }
        }
        return values;
    }

    private static String typeLabel(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("manhwa".equals(v)) return "Manhwa";
        if ("manhua".equals(v)) return "Manhua";
        return "Manga";
    }

    private static String normalizeStatus(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("ongoing".equals(v)) return "Ongoing";
        if ("completed".equals(v)) return "Completed";
        if ("cancelled".equals(v)) return "Cancelled";
        if ("hiatus".equals(v)) return "On Hiatus";
        if (v.isEmpty()) return "";
        return v.substring(0, 1).toUpperCase(Locale.ROOT) + v.substring(1);
    }

    private static String formatChapterNumber(double number) {
        if (number == Math.floor(number) && !Double.isInfinite(number)) return String.valueOf((long) number);
        String s = String.valueOf(number);
        return s;
    }

    private static String datePart(String iso) {
        if (iso == null) return "";
        String v = iso.trim();
        int t = v.indexOf('T');
        return t > 0 ? v.substring(0, t) : v;
    }

    private static String cleanTitle(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String cleanChapterTitle(String value) {
        String out = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i).*?\\bchapter\\s+([0-9]+(?:[.,][0-9]+)?)\\b.*").matcher(out);
        if (matcher.matches()) return "Chapter " + matcher.group(1).replace(",", ".");
        return out;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String normalizeImageUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.startsWith("http://") && value.toLowerCase(Locale.ROOT).contains(".ikiru.wtf/")) {
            return "https://" + value.substring("http://".length());
        }
        return value;
    }

    private static String toAbsolute(String url) { return resolveUrl(url); }

    private static String resolveUrl(String url) {
        if (url == null || url.trim().isEmpty()) return base();
        String value = url.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        try {
            HttpUrl baseUrl = HttpUrl.parse(base().endsWith("/") ? base() : base() + "/");
            HttpUrl resolved = baseUrl == null ? null : baseUrl.resolve(value);
            if (resolved != null) return resolved.toString();
        } catch(Exception ignored) { }
        if (!value.startsWith("/")) value = "/" + value;
        return base() + value;
    }

    private static String normalizeMangaSlug(String value) {
        if (value == null) return "";
        String v = value.trim();
        try {
            HttpUrl parsed = HttpUrl.parse(v.startsWith("http") || v.startsWith("//") ? (v.startsWith("//") ? "https:" + v : v) : resolveUrl(v));
            if (parsed != null && parsed.pathSegments().size() >= 2 && "manga".equals(parsed.pathSegments().get(0))) return parsed.pathSegments().get(1);
        } catch(Exception ignored) { }
        v = v.replace(base(), "").replace("/manga/", "");
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        return v.trim();
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        appendStaticFilters(out);
        return out;
    }

    private static JsonObject getObject(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(name);
    }

    private static JsonArray getArray(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(name);
    }

    private static String getString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try { return object.get(name).getAsString().trim(); } catch(Exception e) { return ""; }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try { return object.get(name).getAsInt(); } catch(Exception e) { return fallback; }
    }

    private static double getDouble(JsonObject object, String name, double fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try { return object.get(name).getAsDouble(); } catch(Exception e) { return fallback; }
    }
}
