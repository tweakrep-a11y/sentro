package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Parser Soul Scans — diperbarui 2026-10-07 dari analisis LIVE browser.
 *
 * Temuan live (7 Okt 2026):
 * - /allcomic SSR HTML; kartu = article.comic-card-nochap, link /comic/&lt;slug&gt;,
 *   badge type = span[title="Manhwa (Korea)"], status span uppercase, h3 judul,
 *   p "Chapter N". 50 kartu/halaman.
 * - Params resmi: sort in {latest,new,views,rate,bookmark,az,za} (+alias tak resmi
 *   "popular" -> views), order in {desc,asc}, status case-insensitive,
 *   comic_type in {MANGA,MANHWA,MANHUA} (param "type" DIIBAIKAN situs),
 *   search param = "q" (param "search" DIIBAIKAN), genre = slug, page.
 * - /projects: struktur kartu sama, 20/halaman, p bawah = cuplikan sinopsis.
 * - API https://img.soulscans.org/api TERBUKA tanpa auth/Referer:
 *   GET /api/genres -> [{"id","slug","name","created_at"}]
 *   GET /api/series/comic/&lt;slug&gt; -> object FLAT (bukan data.series);
 *     chapter array bernama "units", number/sort_number berupa STRING ("204.00"),
 *     slug chapter INKONSISTEN (chapter-03..09 zero-padded, chapter-110-5 utk desimal)
 *     -> WAJIB pakai slug dari API, jangan konstruksi.
 *   GET /api/series/comic/&lt;slug&gt;/chapter/&lt;chapter-slug&gt; ->
 *     {"series":{...},"chapter":{...,"pages":[{"page_number","image_url",...}]},...}
 *     host gambar = http://ss.dbm.my.id (HTTP biasa).
 * - comic_subtype TIDAK dapat diandalkan (contoh: "MANGA" utk konten manhwa Korea);
 *   type diambil dari badge span[title] kartu web.
 * - Halaman web detail/reader tidak menanam JSON inline (__data.json null).
 */
public class SoulScans extends KomikcastClient {
    public static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_SOULSCANS;
    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static final String LABEL = "Soul Scans";
    private static final String API_ORIGIN = "https://img.soulscans.org";
    private static final String API_BASE = API_ORIGIN + "/api";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return LABEL; }

    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            String url = buildListUrl(safePage, sort, query, genre);
            int pageSize = url.contains("/projects") ? 20 : 50;
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= pageSize); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, safePage, out.size(), pageSize);
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Soul Scans gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        // API: [{"id","slug","name","created_at"}] — terbuka tanpa auth/Referer
        getJsonArray(API_BASE + "/genres", base() + "/allcomic", new Result<JSONArray>() {
            @Override public void onSuccess(JSONArray array, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenresFromApi(array);
                        if (out.isEmpty()) out = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        MangaCoroutines.main(() -> loadGenresFromWeb(cb));
                    }
                });
            }
            @Override public void onError(String message) { loadGenresFromWeb(cb); }
        });
    }

    private void loadGenresFromWeb(Result<ArrayList<GenreItem>> cb) {
        getDocument(base() + "/allcomic", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        if (out.isEmpty()) out = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(fallback));
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                GENRE_CACHE.put("genres", new ArrayList<>(fallback));
                cb.onSuccess(fallback, false);
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost post : list) if (needsEnrichment(post, loadChapter, loadType)) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost post : list) {
            if (!needsEnrichment(post, loadChapter, loadType)) continue;
            detail(post.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty())) post.latestChapter = detail.latestChapter;
                        if (loadChapter && (post.latestChapterDate == null || post.latestChapterDate.trim().isEmpty())) post.latestChapterDate = detail.latestChapterDate;
                        if (loadType && (post.typeLabel == null || post.typeLabel.trim().isEmpty())) post.typeLabel = detail.typeLabel;
                        if (post.genre == null || post.genre.trim().isEmpty()) post.genre = detail.genre;
                        if (post.status == null || post.status.trim().isEmpty()) post.status = detail.status;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Soul Scans kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(apiSeriesUrl(clean), seriesUrl(clean), new Result<JSONObject>() {
            @Override public void onSuccess(JSONObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetailFromApi(clean, root);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> loadDetailFromWeb(clean, cb)); return; }
                        ArrayList<MangaChapter> chapters = parseChaptersFromApi(clean, root);
                        fillLatest(post, chapters);
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> loadDetailFromWeb(clean, cb)); }
                });
            }
            @Override public void onError(String message) { loadDetailFromWeb(clean, cb); }
        });
    }

    private void loadDetailFromWeb(String clean, Result<MangaPost> cb) {
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Soul Scans kosong")); return; }
                        ArrayList<MangaChapter> chapters = parseChapters(clean, document);
                        fillLatest(post, chapters);
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Soul Scans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void fillLatest(MangaPost post, ArrayList<MangaChapter> chapters) {
        if (post == null || chapters == null) return;
        post.totalChapters = chapters.size();
        if (chapters.isEmpty()) return;
        MangaChapter newest = chapters.get(0);
        for (MangaChapter chapter : chapters) if (chapter != null && chapter.index > newest.index) newest = chapter;
        post.latestChapter = newest.title == null ? "" : newest.title;
        post.latestChapterDate = newest.date == null ? "" : newest.date;
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Soul Scans kosong"); return; }
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        detail(clean, new Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(clean);
                cb.onSuccess(chapters == null ? new ArrayList<>() : new ArrayList<>(chapters), false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterUrl = cleanChapterUrl(slug);
        String clean = cleanSeriesSlug(slug);
        String key = (chapterUrl.isEmpty() ? clean : chapterUrl) + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        if (!chapterUrl.isEmpty()) { loadPages(chapterUrl, key, cb); return; }
        MangaChapter chapter = findCachedChapter(clean, index);
        if (chapter != null && chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) {
            loadPages(chapter.chapterId.trim(), key, cb);
            return;
        }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) {
                MangaChapter loaded = findChapter(data, index);
                if (loaded == null) loaded = findCachedChapter(clean, index);
                if (loaded != null && loaded.chapterId != null && !loaded.chapterId.trim().isEmpty()) loadPages(loaded.chapterId.trim(), key, cb);
                else cb.onError("Chapter Soul Scans tidak ditemukan");
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadPages(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        String seriesSlug = cleanSeriesSlug(chapterUrl);
        String chapterSlug = cleanChapterSlug(chapterUrl);
        if (!seriesSlug.isEmpty() && !chapterSlug.isEmpty()) {
            String webUrl = chapterWebUrl(seriesSlug, chapterSlug);
            getJson(apiChapterUrl(seriesSlug, chapterSlug), webUrl, new Result<JSONObject>() {
                @Override public void onSuccess(JSONObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<String> pages = parsePagesFromApi(root, webUrl);
                            if (pages.isEmpty()) { MangaCoroutines.main(() -> loadPagesFromWeb(chapterUrl, key, cb)); return; }
                            PAGE_CACHE.put(key, new ArrayList<>(pages));
                            MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                        } catch(Exception e) { MangaCoroutines.main(() -> loadPagesFromWeb(chapterUrl, key, cb)); }
                    });
                }
                @Override public void onError(String message) { loadPagesFromWeb(chapterUrl, key, cb); }
            });
            return;
        }
        loadPagesFromWeb(chapterUrl, key, cb);
    }

    private void loadPagesFromWeb(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        getDocument(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> pages = parsePages(document, chapterUrl);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Soul Scans kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Soul Scans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        FilterParts filter = parseFilters(genre);
        if (filter.type.isEmpty() && isTypeSort(s)) filter.type = s;
        if (filter.status.isEmpty() && ("completed".equals(s) || "complete".equals(s))) filter.status = "completed";
        if (filter.status.isEmpty() && ("ongoing".equals(s) || "on-going".equals(s))) filter.status = "ongoing";
        if ("project".equals(s) || "projects".equals(s)) {
            HttpUrl.Builder project = HttpUrl.parse(base() + "/projects").newBuilder();
            project.addQueryParameter("sort", "latest");
            project.addQueryParameter("order", "desc");
            project.addQueryParameter("page", String.valueOf(page));
            return project.build().toString();
        }
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/allcomic").newBuilder();
        String q = query == null ? "" : query.trim();
        // Param search resmi = "q" (param "search" diabaikan situs)
        if (!q.isEmpty()) builder.addQueryParameter("q", q);
        if (!filter.genre.isEmpty()) builder.addQueryParameter("genre", filter.genre);
        if (!filter.status.isEmpty()) builder.addQueryParameter("status", filter.status);
        // Param type resmi = "comic_type" (param "type" diabaikan situs)
        if (!filter.type.isEmpty()) builder.addQueryParameter("comic_type", filter.type.toUpperCase(Locale.ROOT));
        builder.addQueryParameter("sort", sortParam(s));
        builder.addQueryParameter("order", orderParam(s));
        builder.addQueryParameter("page", String.valueOf(page));
        return builder.build().toString();
    }

    /**
     * Kartu: article.comic-card-nochap > a[href="/comic/&lt;slug&gt;"] berisi img cover,
     * span[title="Manhwa (Korea)"] (badge type), span status uppercase,
     * div bawah "* &lt;rating&gt;" + views; a > h3 judul; p "Chapter N".
     * Di /projects, p bawah = cuplikan sinopsis (bukan chapter).
     */
    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements cards = document.select("article.comic-card-nochap");
        for (Element card : cards) {
            Element link = null;
            for (Element a : card.select("a[href*='/comic/']")) {
                if (isDetailUrl(a.absUrl("href"))) { link = a; break; }
            }
            if (link == null) continue;
            MangaPost post = postFromCard(card, link);
            if (post.slug.isEmpty() || post.title.isEmpty() || !seen.add(post.slug)) continue;
            out.add(post);
        }
        if (out.isEmpty()) {
            for (Element link : document.select("a[href*='/comic/']")) {
                String href = link.absUrl("href");
                if (!isDetailUrl(href)) continue;
                String slug = cleanSeriesSlug(href);
                if (slug.isEmpty() || !seen.add(slug)) continue;
                Element root = nearestCard(link);
                MangaPost post = postFromCard(root == null ? link : root, link);
                if (!post.title.isEmpty()) out.add(post);
            }
        }
        return out;
    }

    private MangaPost postFromCard(Element card, Element link) {
        String href = link.absUrl("href");
        String slug = cleanSeriesSlug(href);
        Element img = link.selectFirst("img");
        if (img == null && card != null) img = card.selectFirst("img");
        String title = firstNonEmpty(text(link, "h3"), attr(link, "title"), attr(img, "alt"), cleanListTitle(link.text()), titleFromSlug(slug));
        String cover = imageUrl(img);
        String type = cardTypeBadge(card);
        String status = cardStatus(card);
        String latest = cardLatestChapter(card);
        if (latest.equalsIgnoreCase(title)) latest = "";
        return new MangaPost(slug, title, cover, "", status, "", "", type, latest, "").withSource(SOURCE_ID, LABEL);
    }

    /** Badge type: span[title="Manhwa (Korea)"] — satu-satunya sumber type yang andal. */
    private String cardTypeBadge(Element card) {
        if (card == null) return "";
        for (Element span : card.select("span[title]")) {
            String badge = span.attr("title").trim();
            if (badge.isEmpty() || !badge.contains("(")) continue;
            String kind = badge.substring(0, badge.indexOf('(')).trim().toUpperCase(Locale.ROOT);
            if ("MANHWA".equals(kind) || "MANGA".equals(kind) || "MANHUA".equals(kind)) return kind;
        }
        return "";
    }

    private String cardStatus(Element card) {
        if (card == null) return "";
        for (Element span : card.select("span")) {
            String t = span.text().trim();
            if (t.equalsIgnoreCase("ongoing")) return "Ongoing";
            if (t.equalsIgnoreCase("completed") || t.equalsIgnoreCase("complete")) return "Completed";
            if (t.equalsIgnoreCase("hiatus")) return "Hiatus";
        }
        return "";
    }

    /** Hanya terima pola "Chapter N"; di /projects p berisi sinopsis -> "". */
    private String cardLatestChapter(Element card) {
        if (card == null) return "";
        for (Element p : card.select("p")) {
            String t = p.text().trim();
            Matcher m = Pattern.compile("(?i)\\bchapter\\s*[0-9]+(?:[.,][0-9]+)?").matcher(t);
            if (m.find()) return m.group().replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    /**
     * Detail via API — response object FLAT (bukan data.series).
     * Type diambil dari comic_subtype (nilainya sama dengan yang ditampilkan
     * website di halaman detail; kartu list memakai badge span[title] yang
     * lebih akurat dan sudah diparsing di parseList).
     */
    private MangaPost parseDetailFromApi(String clean, JSONObject sourceRoot) {
        JSONObject root = seriesObject(sourceRoot);
        String apiSlug = firstNonEmpty(apiString(root, "slug"), clean);
        String title = firstNonEmpty(apiString(root, "title"), titleFromSlug(apiSlug));
        String cover = absolutizeApiAsset(firstNonEmpty(apiString(root, "poster_image_url"), apiString(root, "banner_image_url")));
        String synopsis = cleanSynopsis(apiString(root, "synopsis"));
        String genre = jsonGenres(root.optJSONArray("genres"));
        String status = normalizeStatusLabel(firstNonEmpty(apiString(root, "comic_status"), apiString(root, "series_status"), apiString(root, "status")));
        String type = normalizeTypeLabel(apiString(root, "comic_subtype"));
        MangaPost post = new MangaPost(apiSlug, title, cover, "", status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = apiDetailInfo(root, genre);
        JSONArray units = root.optJSONArray("units");
        post.totalChapters = units == null ? 0 : units.length();
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, seriesUrl(apiSlug));
        return post;
    }

    /** Fallback web: h1 judul, aside img cover, p.text-justify sinopsis, span pills genre. */
    private MangaPost parseDetail(String clean, Document document) {
        String title = firstNonEmpty(text(document, "main h1"), text(document, "article h1"), text(document, "h1"), meta(document, "meta[property=og:title]"), document.title());
        title = title.replace("- Comic | Soul Scans ID", "").replace("| Soul Scans ID", "").trim();
        String cover = firstNonEmpty(meta(document, "meta[property=og:image]"), meta(document, "meta[name=twitter:image]"),
                imageUrl(document.selectFirst("aside img, main img[src*='covers'], img[src*='series/covers']")));
        String synopsis = cleanSynopsis(firstNonEmpty(text(document, "p.text-justify"), text(document, ".synopsis"), text(document, ".description"), text(document, ".summary")));
        String genre = webGenrePills(document);
        String status = firstNonEmpty(infoValue(document, "Status"), detectStatus(document.text()));
        String type = cardTypeBadge(document);
        MangaPost post = new MangaPost(clean, title, cover, "", status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = detailInfo(document);
        return post;
    }

    /** Genre di halaman detail = span pills TANPA link. */
    private String webGenrePills(Document document) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='genre']")) {
            String t = link.text().trim();
            if (!t.isEmpty() && !isNoiseGenre(t) && seen.add(t.toLowerCase(Locale.ROOT))) values.add(t);
        }
        if (values.isEmpty()) {
            for (Element container : document.select("[class*=genre]")) {
                for (Element span : container.select("span")) {
                    String t = span.text().trim();
                    if (t.isEmpty() || t.length() > 30 || isNoiseGenre(t)) continue;
                    if (seen.add(t.toLowerCase(Locale.ROOT))) values.add(t);
                }
                if (!values.isEmpty()) break;
            }
        }
        return TextUtils.join(", ", values);
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='/comic/'][href*='/chapter/'], a[href*='/chapter/']")) {
            String href = link.absUrl("href");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("/chapter/")) continue;
            String linkSeries = cleanSeriesSlug(href);
            if (!linkSeries.isEmpty() && !seriesSlug.isEmpty() && !seriesSlug.equals(linkSeries)) continue;
            String title = firstNonEmpty(cleanChapterText(link.text()), cleanChapterText(text(nearestCard(link))), cleanChapterText(href), "Chapter");
            float index = parseChapterIndex(firstNonEmpty(title, href));
            if (index < 0) index = out.size() + 1;
            String urlKey = href.replaceAll("/+$", "");
            if (!seen.add(urlKey)) continue;
            String date = extractDate(text(nearestCard(link)));
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        sortChapters(out);
        return out;
    }

    /**
     * Chapter dari API: units[] dengan number/sort_number STRING ("204.00").
     * Slug chapter INKONSISTEN (chapter-03..09 zero-padded, chapter-110-5 utk desimal)
     * -> selalu pakai slug dari API, jangan konstruksi.
     */
    private ArrayList<MangaChapter> parseChaptersFromApi(String seriesSlug, JSONObject root) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONObject series = seriesObject(root);
        JSONArray units = series.optJSONArray("units");
        if (units == null && root != null && root != series) units = root.optJSONArray("units");
        if (units == null) return out;
        for (int i = 0; i < units.length(); i++) {
            JSONObject item = units.optJSONObject(i);
            if (item == null) continue;
            String chapterSlug = cleanChapterSlug(firstNonEmpty(item.optString("slug", ""), item.optString("chapter_slug", "")));
            if (chapterSlug.isEmpty()) continue;
            if (isLocked(item)) continue;
            float index = parseApiNumber(firstNonEmpty(item.optString("number", ""), item.optString("sort_number", ""), item.optString("title", ""), chapterSlug));
            if (index < 0) index = i + 1;
            String title = "Chapter " + MangaChapter.formatIndex(index);
            // Website menampilkan waktu relatif ("2 Jam yang lalu"); ikuti format itu.
            String date = relativeChapterTime(firstNonEmpty(apiString(item, "created_at"), apiString(item, "updated_at")));
            String webUrl = chapterWebUrl(seriesSlug, chapterSlug);
            if (!seen.add(webUrl)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = webUrl;
            out.add(chapter);
        }
        sortChapters(out);
        return out;
    }

    /**
     * Pages dari API: {"chapter": {..., "pages": [{"page_number","image_url",...}]}}.
     * Host gambar = http://ss.dbm.my.id (HTTP biasa).
     */
    private ArrayList<String> parsePagesFromApi(JSONObject root, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONObject chapter = root == null ? null : root.optJSONObject("chapter");
        JSONArray pages = chapter == null ? null : chapter.optJSONArray("pages");
        if (pages == null && root != null) pages = root.optJSONArray("pages");
        if (pages == null) return out;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject item = pages.optJSONObject(i);
            if (item == null) continue;
            addPage(out, seen, firstNonEmpty(item.optString("image_url", ""), item.optString("imageUrl", ""), item.optString("url", ""), item.optString("src", ""), item.optString("image", ""), item.optString("page_url", ""), item.optString("file_url", "")), chapterUrl);
        }
        return out;
    }

    /** Fallback web: img[alt="Page N"] langsung di DOM. */
    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("img[alt^=Page i], main img, article img, .reading-content img, .chapter-content img, #chapter-content img, .readerarea img, #readerarea img, .page-break img, img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-pagespeed-lazy-src], picture source[srcset], source[srcset]");
        for (Element img : images) addPage(out, seen, imageUrl(img), chapterUrl);
        String html = document.outerHtml();
        Matcher quoted = Pattern.compile("https?:\\/\\/[^\"'<>\\s)]+(?:soulscans\\.org|ss\\.dbm\\.my\\.id|dbm\\.my\\.id|wp\\.com|googleusercontent\\.com|ggpht\\.com|blogspot\\.com|blogger\\.googleusercontent\\.com)[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
        while (quoted.find()) addPage(out, seen, quoted.group().replace("\\/", "/"), chapterUrl);
        Matcher normal = Pattern.compile("https?://[^\"'<>\\s)]*(?:soulscans\\.org|ss\\.dbm\\.my\\.id|dbm\\.my\\.id|wp\\.com|googleusercontent\\.com|ggpht\\.com|blogspot\\.com|blogger\\.googleusercontent\\.com)[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
        while (normal.find()) addPage(out, seen, normal.group().replace("\\/", "/"), chapterUrl);
        return out;
    }

    private void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String raw, String chapterUrl) {
        String url = absolutize(raw);
        if (!url.startsWith("http")) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("loading") || lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("favicon") || lower.contains("placeholder") || lower.contains("profile") || lower.contains("/ads") || lower.contains("/iklan")) return;
        if (!lower.matches(".*\\.(jpg|jpeg|png|webp|avif)(?:\\?.*)?$") && !lower.contains("/manga-images/") && !lower.contains("/uploads/manga-images/")) return;
        if (!lower.contains("/manga-images/") && !lower.contains("/chapter")) return;
        if (seen.add(url)) {
            MangaImageLoader.registerImageReferer(url, chapterUrl == null || chapterUrl.trim().isEmpty() ? base() + "/" : chapterUrl);
            out.add(url);
        }
    }

    /** Fallback web: select#genre > option (value=slug, label=name). */
    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element option : document.select("select#genre option[value], select[name=genre] option[value]")) {
            String value = option.attr("value").trim();
            String title = option.text().trim();
            if (value.isEmpty() || title.isEmpty() || isNoiseGenre(title) || !seen.add(value.toLowerCase(Locale.ROOT))) continue;
            out.add(new GenreItem(title, value));
        }
        if (out.isEmpty()) {
            for (Element link : document.select("a[href*='genre='], a[href*='/genre/'], a[href*='genres']")) {
                String title = link.text().trim();
                String value = genreValue(link.absUrl("href"));
                if (title.isEmpty() || value.isEmpty() || isNoiseGenre(title) || !seen.add(value)) continue;
                out.add(new GenreItem(title, value));
            }
        }
        return out;
    }

    private boolean hasNextPage(Document document, int page, int size, int pageSize) {
        if (document.selectFirst("a[rel=next], .pagination a.next, a.next, a[href*='page=" + (page + 1) + "']") != null) return true;
        for (Element link : document.select("a[href*='page=']")) {
            String href = link.absUrl("href");
            Matcher m = Pattern.compile("[?&]page=([0-9]+)").matcher(href);
            while (m.find()) {
                try { if (Integer.parseInt(m.group(1)) > page) return true; } catch(Exception ignored) { }
            }
        }
        return size >= pageSize;
    }

    private void getDocument(String url, Result<Document> cb) { getDocument(new Request.Builder().url(url).headers(headers()).build(), cb); }

    private void getJson(String url, Result<JSONObject> cb) { getJson(url, base() + "/", cb); }

    private void getJson(String url, String referer, Result<JSONObject> cb) { getJson(new Request.Builder().url(url).headers(apiHeaders(referer)).build(), cb); }

    private void getJsonArray(String url, String referer, Result<JSONArray> cb) { getJsonArray(new Request.Builder().url(url).headers(apiHeaders(referer)).build(), cb); }

    private void getJson(Request req, Result<JSONObject> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JSONObject root = new JSONObject(body);
                    main.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { main.post(() -> cb.onError("JSON Soul Scans gagal dibaca")); }
            }
        });
    }

    private void getJsonArray(Request req, Result<JSONArray> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JSONArray root = new JSONArray(body);
                    main.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { main.post(() -> cb.onError("JSON Soul Scans gagal dibaca")); }
            }
        });
    }

    private void getDocument(Request req, Result<Document> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    Document doc = Jsoup.parse(body, req.url().toString());
                    main.post(() -> cb.onSuccess(doc, false));
                } catch(Exception e) { main.post(() -> cb.onError("Data Soul Scans gagal dibaca")); }
            }
        });
    }

    /** API terbuka tanpa auth/Referer (terverifikasi live: GET polos = 200). */
    private Headers apiHeaders(String referer) {
        String ref = referer == null || referer.trim().isEmpty() ? base() + "/" : referer.trim();
        return new Headers.Builder()
                .set("Referer", ref)
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "application/json,text/plain,*/*")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .set("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    private Headers headers() {
        String ref = base() + "/";
        return new Headers.Builder()
                .set("Referer", ref)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "max-age=0")
                .build();
    }

    private String apiSeriesUrl(String slug) { return API_BASE + "/series/comic/" + urlSegment(slug); }

    private String apiChapterUrl(String seriesSlug, String chapterSlug) { return API_BASE + "/series/comic/" + urlSegment(seriesSlug) + "/chapter/" + urlSegment(chapterSlug); }

    private String chapterWebUrl(String seriesSlug, String chapterSlug) { return base() + "/comic/" + urlSegment(seriesSlug) + "/chapter/" + urlSegment(chapterSlug); }

    private String absolutizeApiAsset(String raw) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/").replace("&amp;", "&");
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/api/")) return API_ORIGIN + url;
        if (url.startsWith("/uploads/")) return "http://ss.dbm.my.id" + url;
        if (url.startsWith("/")) return base() + url;
        return url;
    }

    private String apiDetailInfo(JSONObject root, String genre) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Judul Alternatif", apiString(root, "alternative_titles"));
        addInfo(values, "Genre", genre);
        addInfo(values, "Author", apiString(root, "author_name"));
        addInfo(values, "Artist", apiString(root, "artist_name"));
        addInfo(values, "Publisher", apiString(root, "publisher_name"));
        addInfo(values, "Rilis", firstNonEmpty(apiString(root, "release_year"), MangaDateFormatter.format(apiString(root, "first_release_date"))));
        addInfo(values, "Rating", formatRating(apiString(root, "rating_average")));
        addInfo(values, "Views", formatCount(apiString(root, "view_count")));
        addInfo(values, "Followers", formatCount(apiString(root, "followers_count")));
        addInfo(values, "Uploader", primaryUploader(root.optJSONArray("uploaders")));
        addInfo(values, "Diperbarui", MangaDateFormatter.format(apiString(root, "updated_at")));
        return TextUtils.join("\n", values);
    }

    private JSONObject seriesObject(JSONObject root) {
        if (root == null) return new JSONObject();
        JSONObject data = root.optJSONObject("data");
        if (data != null) root = data;
        JSONObject series = root.optJSONObject("series");
        return series == null ? root : series;
    }

    private String apiString(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        String text = String.valueOf(value).replace("\\/", "/").replace("&amp;", "&").replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (text.equalsIgnoreCase("null") || text.equalsIgnoreCase("undefined")) return "";
        return text;
    }

    private String cleanSynopsis(String raw) {
        String value = raw == null ? "" : Jsoup.parseBodyFragment(raw).wholeText().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("undefined")) return "Belum ada sinopsis.";
        return value;
    }

    private String normalizeStatusLabel(String raw) {
        String value = raw == null ? "" : raw.trim().replace("_", " ");
        value = value.replaceAll("\\s+", " ").trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("ongoing") || lower.equals("on going")) return "Ongoing";
        if (lower.equals("completed") || lower.equals("complete")) return "Completed";
        if (lower.equals("hiatus")) return "Hiatus";
        if (lower.equals("dropped")) return "Dropped";
        return titleCase(value);
    }

    private String normalizeTypeLabel(String raw) {
        String value = raw == null ? "" : raw.trim().replace("_", " ");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.equalsIgnoreCase("comic")) return "";
        return value.toUpperCase(Locale.ROOT);
    }

    private String titleCase(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : value.split(" ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.length() > 1 ? part.substring(1) : "");
        }
        return out.toString();
    }

    private ArrayList<GenreItem> parseGenresFromApi(JSONArray array) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String slug = firstNonEmpty(apiString(item, "slug"), apiString(item, "value"), apiString(item, "id"));
                String title = firstNonEmpty(apiString(item, "name"), apiString(item, "title"), titleFromSlug(slug));
                if (slug.isEmpty() || title.isEmpty() || isNoiseGenre(title) || !seen.add(slug.toLowerCase(Locale.ROOT))) continue;
                out.add(new GenreItem(title, slug));
            }
        }
        addGenreItem(out, seen, "Manga", "type:manga");
        addGenreItem(out, seen, "Manhwa", "type:manhwa");
        addGenreItem(out, seen, "Manhua", "type:manhua");
        addGenreItem(out, seen, "Ongoing", "status:ongoing");
        addGenreItem(out, seen, "Completed", "status:completed");
        return out;
    }

    private void addGenreItem(ArrayList<GenreItem> out, LinkedHashSet<String> seen, String title, String value) {
        if (out == null || seen == null || title == null || value == null || value.trim().isEmpty()) return;
        if (seen.add(value.toLowerCase(Locale.ROOT))) out.add(new GenreItem(title, value));
    }

    private String jsonGenres(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = "";
            Object item = array.opt(i);
            if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                value = firstNonEmpty(apiString(object, "name"), apiString(object, "title"), apiString(object, "slug"));
            } else if (item != null) value = String.valueOf(item);
            value = value == null ? "" : value.trim();
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) values.add(value);
        }
        return TextUtils.join(", ", values);
    }

    private String formatRating(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try { return String.format(Locale.ROOT, "%.1f", Double.parseDouble(value)); } catch(Exception ignored) { }
        return value;
    }

    private String formatCount(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try {
            long number = Long.parseLong(value.replaceAll("[^0-9]", ""));
            return String.format(Locale.ROOT, "%,d", number).replace(',', '.');
        } catch(Exception ignored) {
            return value;
        }
    }

    private String primaryUploader(JSONArray array) {
        if (array == null) return "";
        String fallback = "";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String username = apiString(item, "username");
            if (username.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = username;
            if (optBoolean(item, "is_primary")) return username;
        }
        return fallback;
    }

    private boolean isLocked(JSONObject item) {
        boolean locked = optBoolean(item, "is_locked");
        boolean premium = optBoolean(item, "is_premium");
        boolean purchased = optBoolean(item, "is_purchased");
        return locked && premium && !purchased;
    }

    private boolean optBoolean(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * Waktu relatif ala website ("2 Jam yang lalu", "3 Minggu yang lalu",
     * "1 Bulan yang lalu") dari timestamp ISO-8601 UTC milik API.
     */
    private String relativeChapterTime(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        try {
            long millis = parseIsoMillis(value);
            if (millis <= 0) return MangaDateFormatter.format(raw);
            long diff = System.currentTimeMillis() - millis;
            if (diff < 0) diff = 0;
            long seconds = diff / 1000;
            if (seconds < 60) return "Baru saja";
            long minutes = seconds / 60;
            if (minutes < 60) return minutes + " Menit yang lalu";
            long hours = minutes / 60;
            if (hours < 24) return hours + " Jam yang lalu";
            long days = hours / 24;
            if (days < 7) return days + " Hari yang lalu";
            if (days < 30) return (days / 7) + " Minggu yang lalu";
            if (days < 365) return (days / 30) + " Bulan yang lalu";
            return (days / 365) + " Tahun yang lalu";
        } catch (Exception ignored) { }
        return MangaDateFormatter.format(raw);
    }

    private long parseIsoMillis(String value) {
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})[T\\s](\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?").matcher(value);
        if (!m.find()) return -1;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
            cal.set(java.util.Calendar.YEAR, Integer.parseInt(m.group(1)));
            cal.set(java.util.Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
            cal.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(3)));
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            cal.set(java.util.Calendar.MINUTE, Integer.parseInt(m.group(5)));
            cal.set(java.util.Calendar.SECOND, m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception ignored) { return -1; }
    }

    private float parseApiNumber(String raw) {
        String value = raw == null ? "" : raw.trim().replace(",", ".");
        if (!value.isEmpty()) {
            try { return Float.parseFloat(value); } catch(Exception ignored) { }
        }
        return parseChapterIndex(raw);
    }

    private void sortChapters(ArrayList<MangaChapter> chapters) {
        if (chapters == null) return;
        java.util.Collections.sort(chapters, (a, b) -> Float.compare(b == null ? -1f : b.index, a == null ? -1f : a.index));
    }

    private boolean needsEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return false;
        boolean missingChapter = loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty() || post.latestChapter.equalsIgnoreCase("Belum ada chapter."));
        boolean missingType = loadType && (post.typeLabel == null || post.typeLabel.trim().isEmpty());
        return missingChapter || missingType;
    }

    private MangaChapter findCachedChapter(String clean, float index) {
        ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(clean);
        return findChapter(chapters, index);
    }

    private MangaChapter findChapter(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null) return null;
        MangaChapter nearest = null;
        for (MangaChapter chapter : chapters) {
            if (chapter == null) continue;
            if (Math.abs(chapter.index - index) < 0.001f) return chapter;
            if (nearest == null && MangaChapter.formatIndex(chapter.index).equals(MangaChapter.formatIndex(index))) nearest = chapter;
        }
        return nearest;
    }

    private FilterParts parseFilters(String raw) {
        FilterParts out = new FilterParts();
        if (raw == null) return out;
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) { out.type = normalizeType(value.substring(5)); continue; }
            if (lower.startsWith("status:")) { out.status = normalizeStatus(value.substring(7)); continue; }
            if (lower.startsWith("genre:")) value = value.substring(6).trim();
            if (!value.isEmpty()) out.genre = value;
        }
        return out;
    }

    private boolean isTypeSort(String sort) { return "manga".equals(sort) || "manhwa".equals(sort) || "manhua".equals(sort); }

    /**
     * Sort resmi situs: latest/new/views/rate/bookmark/az/za.
     * Tab app: Populer -> views/desc; Terbaru -> latest/desc (default);
     * Added -> new/desc; A-Z -> az/asc; Z-A -> za/desc.
     */
    private String sortParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s) || "views".equals(s)) return "views";
        if ("added".equals(s) || "new".equals(s) || "latest_added".equals(s) || "date".equals(s)) return "new";
        if ("rate".equals(s) || "rating".equals(s)) return "rate";
        if ("bookmark".equals(s)) return "bookmark";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "az";
        if ("za".equals(s) || "z-a".equals(s) || "titlereverse".equals(s)) return "za";
        return "latest";
    }

    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "asc";
        return "desc";
    }

    private String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(value)) return "manga";
        if ("manhwa".equals(value)) return "manhwa";
        if ("manhua".equals(value)) return "manhua";
        return value;
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "complete".equals(value)) return "completed";
        if ("ongoing".equals(value) || "on-going".equals(value)) return "ongoing";
        return value;
    }

    private String detectStatus(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean done = java.util.regex.Pattern.compile("\\b(completed|complete|tamat)\\b").matcher(value).find();
        boolean going = java.util.regex.Pattern.compile("\\b(ongoing|on going|berjalan)\\b").matcher(value).find();
        if (done == going) return ""; // tidak ada / ambigu: jangan menebak
        return done ? "Completed" : "Ongoing";
    }

    private String infoValue(Document document, String label) {
        String wanted = label == null ? "" : label.trim().replace(":", "").toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || document == null) return "";
        for (Element row : document.select("tr, .info, .meta, .property, .properties > *, .detail-info > *, .comic-info > *")) {
            String full = row.text().trim();
            if (full.isEmpty()) continue;
            String lower = full.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(wanted)) continue;
            return full.replaceFirst("(?i)^" + Pattern.quote(label), "").replace(":", "").trim();
        }
        return "";
    }

    private String detailInfo(Document document) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Artist", infoValue(document, "Artist"));
        addInfo(values, "Released", infoValue(document, "Released"));
        addInfo(values, "Updated", infoValue(document, "Updated"));
        return TextUtils.join("\n", values);
    }

    private void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || value == null || value.trim().isEmpty()) return;
        out.add(label + ": " + value.trim());
    }

    private boolean isDetailUrl(String href) {
        String lower = href == null ? "" : href.toLowerCase(Locale.ROOT);
        if (!lower.contains("/comic/")) return false;
        if (lower.contains("/chapter/")) return false;
        if (lower.contains("/login") || lower.contains("/profile") || lower.contains("/bookmark") || lower.contains("/history")) return false;
        return !cleanSeriesSlug(href).isEmpty();
    }

    private Element nearestCard(Element element) {
        Element cur = element;
        for (int i = 0; i < 5 && cur != null; i++) {
            String tag = cur.tagName().toLowerCase(Locale.ROOT);
            if ("li".equals(tag) || "article".equals(tag)) return cur;
            String cls = cur.className().toLowerCase(Locale.ROOT);
            if (cls.contains("card") || cls.contains("item") || cls.contains("comic") || cls.contains("series") || cls.contains("chapter")) return cur;
            cur = cur.parent();
        }
        return element == null ? null : element.parent();
    }

    private String cleanListTitle(String text) {
        if (text == null) return "";
        String value = text.replaceAll("(?i)chapter\\s*[0-9]+(?:[.,-][0-9]+)?", "").replace("Belum ada chapter.", "").trim();
        return value.replaceAll("\\s+", " ").trim();
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile("(?i)chapter\\s*[#:-]?\\s*[0-9]+(?:[.,-][0-9]+)?").matcher(value);
        if (matcher.find()) return matcher.group().replace("-", ".").replaceAll("\\s+", " ").trim();
        Matcher path = Pattern.compile("(?i)(?:^|/)chapter[-_]?([0-9]+(?:[-_.][0-9]+)?)").matcher(value);
        if (path.find()) return "Chapter " + path.group(1).replace("-", ".");
        return value;
    }

    private String extractDate(String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("(?:\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4}|[A-Za-z]{3,9}\\s+\\d{1,2},\\s*\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{2,4})").matcher(raw);
        return m.find() ? m.group().trim() : "";
    }

    private float parseChapterIndex(String raw) {
        if (raw == null) return -1f;
        Matcher matcher = Pattern.compile("(?i)(?:chapter|chap|ch)?\\s*([0-9]+(?:[.,-][0-9]+)?)").matcher(raw);
        float last = -1f;
        while (matcher.find()) {
            try { last = Float.parseFloat(matcher.group(1).replace(",", ".").replace("-", ".")); } catch(Exception ignored) { }
        }
        return last;
    }

    private String cleanSeriesSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) {
            try {
                HttpUrl url = HttpUrl.parse(value);
                if (url != null) {
                    for (int i = 0; i < url.pathSize(); i++) {
                        if ("comic".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").trim();
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("comic/")) value = value.substring(6);
        int chapter = value.indexOf("/chapter/");
        if (chapter >= 0) value = value.substring(0, chapter);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private String cleanChapterUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http") && value.toLowerCase(Locale.ROOT).contains("/chapter/")) return value.replaceAll("/+$", "");
        return "";
    }

    private String cleanChapterSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("/+$", "");
        if (value.startsWith("http")) {
            try {
                HttpUrl url = HttpUrl.parse(value);
                if (url != null) {
                    for (int i = 0; i < url.pathSize(); i++) {
                        if ("chapter".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        int pos = value.toLowerCase(Locale.ROOT).indexOf("/chapter/");
        if (pos >= 0) value = value.substring(pos + 9);
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private String seriesUrl(String slug) { return base() + "/comic/" + urlSegment(slug); }

    private String imageUrl(Element img) {
        if (img == null) return "";
        String url = firstNonEmpty(img.absUrl("data-src"), img.absUrl("data-lazy-src"), img.absUrl("data-original"), img.absUrl("data-pagespeed-lazy-src"), img.absUrl("src"), img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"), img.attr("data-pagespeed-lazy-src"), img.attr("src"), imageFromSrcset(img.attr("data-srcset")), imageFromSrcset(img.attr("srcset")));
        return absolutize(url);
    }

    private String absolutize(String raw) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/").replace("&amp;", "&");
        // Host gambar chapter hanya melayani HTTP (ss.dbm.my.id)
        if (url.startsWith("//")) url = (url.contains("ss.dbm.my.id") ? "http:" : "https:") + url;
        if (url.startsWith("/")) url = base() + url;
        if (url.startsWith("data:")) return "";
        return url.trim();
    }

    private String imageFromSrcset(String raw) {
        if (raw == null) return "";
        String best = "";
        int bestWidth = -1;
        for (String part : raw.split(",")) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty()) continue;
            String[] pieces = item.split("\\s+");
            String url = pieces.length > 0 ? pieces[0].trim() : "";
            if (url.isEmpty()) continue;
            int width = 0;
            if (pieces.length > 1) {
                try { width = Integer.parseInt(pieces[1].replaceAll("[^0-9]", "")); } catch(Exception ignored) { }
            }
            if (best.isEmpty() || width > bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    private String meta(Document document, String selector) {
        Element element = document == null ? null : document.selectFirst(selector);
        return element == null ? "" : element.attr("content").trim();
    }

    private String text(Document document, String selector) {
        Element element = document == null ? null : document.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    private String text(Element root, String selector) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    private String text(Element element) { return element == null ? "" : element.text().trim(); }

    private String attr(Element element, String name) {
        if (element == null || name == null) return "";
        return element.attr(name).trim();
    }

    private String joinTexts(Elements elements) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : elements) {
            String text = element.text().trim();
            if (!text.isEmpty() && seen.add(text)) values.add(text);
        }
        return TextUtils.join(", ", values);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private String urlSegment(String value) {
        if (value == null) return "";
        return value.trim().replace(" ", "%20");
    }

    private String titleFromSlug(String slug) {
        if (slug == null) return "";
        StringBuilder builder = new StringBuilder();
        for (String part : slug.split("-")) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.length() > 1 ? part.substring(1) : "");
        }
        return builder.toString();
    }

    private String genreValue(String href) {
        String value = href == null ? "" : href.trim();
        try {
            HttpUrl url = HttpUrl.parse(value);
            if (url != null) {
                String q = url.queryParameter("genre");
                if (q != null && !q.trim().isEmpty()) return q.trim();
                for (int i = 0; i < url.pathSize(); i++) {
                    String segment = url.pathSegments().get(i);
                    if (("genre".equals(segment) || "genres".equals(segment)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                }
            }
        } catch(Exception ignored) { }
        return value.replace(base(), "").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private boolean isNoiseGenre(String title) {
        if (title == null) return true;
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("genres") || value.equals("genre") || value.equals("comic") || value.equals("soul scans") || value.equals("soulscans") || value.equals("soul scans id");
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : fallbackGenrePairs()) out.add(new GenreItem(value[0], value[1]));
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        out.add(new GenreItem("Ongoing", "status:ongoing"));
        out.add(new GenreItem("Completed", "status:completed"));
        return out;
    }

    private String[][] fallbackGenrePairs() {
        return new String[][]{{"Action", "action"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Drama", "drama"}, {"Fantasy", "fantasy"}, {"Harem", "harem"}, {"Historical", "historical"}, {"Horror", "horror"}, {"Isekai", "isekai"}, {"Magic", "magic"}, {"Manhua", "manhua"}, {"Manhwa", "manhwa"}, {"Martial Arts", "martial-arts"}, {"Mature", "mature"}, {"Mystery", "mystery"}, {"Psychological", "psychological"}, {"Reincarnation", "reincarnation"}, {"Romance", "romance"}, {"School Life", "school-life"}, {"Sci-Fi", "sci-fi"}, {"Seinen", "seinen"}, {"Shoujo", "shoujo"}, {"Shounen", "shounen"}, {"Slice of Life", "slice-of-life"}, {"Supernatural", "supernatural"}, {"Thriller", "thriller"}, {"Tragedy", "tragedy"}, {"Webtoon", "webtoon"}};
    }

    private static final class FilterParts {
        String genre = "";
        String type = "";
        String status = "";
    }
}
