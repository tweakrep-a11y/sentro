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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Parser Manhwa Indo — diperbarui 2026-10-07 dari analisis LIVE browser.
 *
 * Temuan live (7 Okt 2026):
 * - WordPress 7.1.3 + TEMA KUSTOM (manhwaindo-v1.1), BUKAN Madara. Tanpa ts_reader.
 * - /explore/: form GET (select name="sort|status|type", checkbox name="genre[]").
 *   Sort EXACT: modified (Update Terbaru, default) | title (Judul A-Z) |
 *   date (Terbaru Ditambahkan) | views (Paling Banyak Dibaca).
 *   Type EXACT (case-sensitive, kapital): Manga | Manhua | Manhwa | Comic | Novel.
 *   Status EXACT (kapital): Ongoing | Completed | Hiatus.
 *   Pagination: /explore/page/&lt;N&gt;/?&lt;query&gt; (query dipertahankan).
 * - Kartu: a.series[href="/series/&lt;slug&gt;/" title] > img.cover; di bawahnya
 *   div.line-clamp-1 (judul), span.bg-zinc-800 (chip genre), span.bg-indigo-400 "Ch. &lt;n&gt;".
 * - Search: /?s=&lt;q&gt;, pagination /page/&lt;N&gt;/?s=&lt;q&gt; (di root).
 *   Live suggestion: /wp-admin/admin-ajax.php?action=manga_search&q=&lt;kata&gt;.
 * - Detail /series/&lt;slug&gt;/: h1 judul; cover = div bg-cover[style=background-image]
 *   pola imgx-id.gmbr.pro/uploads/manga-images/&lt;huruf&gt;/&lt;slug&gt;/thumbnail.(png|jpg)
 *   (atau og:image); badge span.badge.&lt;Type&gt; / span.badge.&lt;Status&gt;;
 *   sinopsis div.page-content; info &lt;strong&gt; label: "Penulis :","Artist :",
 *   "Tahun Rilis :","Chapter Terbaru :","Ditambahkan :","Terakhir Update :";
 *   genre a.btn-outline[href="/genres/&lt;slug&gt;/"].
 * - Chapter list: inline window.agcInitialChapters=[{id,url,chapter,time}] +
 *   REST penuh GET /wp-json/agc/v1/chapters?series=&lt;seriesId&gt; (terbaru dulu).
 *   URL chapter: /&lt;series-slug&gt;-chapter-&lt;n&gt;/ (desimal 197.5 -> 197-5).
 * - Reader: &lt;img&gt; LANGSUNG di .reader-area (tanpa ts_reader JSON).
 *   Gambar: imgx-id.gmbr.pro/uploads/manga-images/&lt;huruf&gt;/&lt;slug&gt;/chapter-&lt;n&gt;/&lt;i&gt;.jpg.
 *   WAJIB header Referer: &lt;url chapter&gt; (proteksi hotlink gmbr.pro).
 */
public class ManhwaIndo extends KomikcastClient {
    public static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_MANHWAINDO;
    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static final String LABEL = "Manhwa Indo";
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
            String url = buildListUrl(Math.max(1, page), sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 10); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, Math.max(1, page), out.size());
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Manhwa Indo gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/explore/", new Result<Document>() {
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
            @Override public void onError(String ignored) {
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
        if (clean.isEmpty()) { cb.onError("Slug Manhwa Indo kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    MangaPost post;
                    try {
                        post = parseDetail(clean, document);
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Manhwa Indo gagal dibaca")); return; }
                    if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Manhwa Indo kosong")); return; }
                    final MangaPost finalPost = post;
                    // Chapter diambil async (REST agc / inline JSON / DOM fallback)
                    loadChapters(clean, document, new Result<ArrayList<MangaChapter>>() {
                        @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                            fillLatest(finalPost, chapters);
                            DETAIL_CACHE.put(clean, finalPost);
                            CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                            MangaCoroutines.main(() -> cb.onSuccess(finalPost, false));
                        }
                        @Override public void onError(String message) {
                            DETAIL_CACHE.put(clean, finalPost);
                            CHAPTER_CACHE.put(clean, new ArrayList<MangaChapter>());
                            MangaCoroutines.main(() -> cb.onSuccess(finalPost, false));
                        }
                    });
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
        if (clean.isEmpty()) { cb.onError("Slug Manhwa Indo kosong"); return; }
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

    /**
     * Chapter list: utama via REST /wp-json/agc/v1/chapters?series=&lt;seriesId&gt;
     * (seriesId dari window.agcChapterListConfig), fallback inline
     * window.agcInitialChapters, terakhir DOM (ul.grid / selector lama).
     */
    private void loadChapters(String seriesSlug, Document document, Result<ArrayList<MangaChapter>> cb) {
        String html = document == null ? "" : document.outerHtml();
        String seriesId = agcSeriesId(html);
        ArrayList<MangaChapter> inline = parseAgcInitialChapters(seriesSlug, html);
        if (!seriesId.isEmpty()) {
            getJsonArray(base() + "/wp-json/agc/v1/chapters?series=" + seriesId, new Result<JSONArray>() {
                @Override public void onSuccess(JSONArray array, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        ArrayList<MangaChapter> out = parseAgcChapters(seriesSlug, array);
                        if (out.isEmpty()) out = inline;
                        if (out.isEmpty()) out = parseChaptersDom(seriesSlug, document);
                        final ArrayList<MangaChapter> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    });
                }
                @Override public void onError(String message) {
                    ArrayList<MangaChapter> out = inline;
                    if (out.isEmpty()) out = parseChaptersDom(seriesSlug, document);
                    cb.onSuccess(out, false);
                }
            });
            return;
        }
        ArrayList<MangaChapter> out = inline;
        if (out.isEmpty()) out = parseChaptersDom(seriesSlug, document);
        cb.onSuccess(out, false);
    }

    /** window.agcChapterListConfig = {seriesId: 101778, ...} */
    private String agcSeriesId(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("agcChapterListConfig\\s*=\\s*\\{[^}]*?seriesId\\s*:\\s*(\\d+)").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    /** window.agcInitialChapters = [{id,url,chapter,time}, ...] */
    private ArrayList<MangaChapter> parseAgcInitialChapters(String seriesSlug, String html) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (html == null) return out;
        try {
            Matcher m = Pattern.compile("agcInitialChapters\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL).matcher(html);
            if (!m.find()) return out;
            out = parseAgcChapters(seriesSlug, new JSONArray(m.group(1)));
        } catch(Exception ignored) { }
        return out;
    }

    /** Item: {"id":103079,"url":".../solo-leveling-chapter-197-5/","chapter":"197.5","time":"1 day lalu"} */
    private ArrayList<MangaChapter> parseAgcChapters(String seriesSlug, JSONArray array) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (array == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "").trim();
            String num = item.optString("chapter", "").trim();
            if (url.isEmpty()) continue;
            float index = parseChapterIndex(num);
            if (index < 0) index = parseChapterIndex(url);
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index);
            if (!seen.add(key)) continue;
            String title = num.isEmpty() ? ("Chapter " + key) : ("Chapter " + num);
            String time = item.optString("time", "").trim();
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, time);
            chapter.chapterId = url;
            out.add(chapter);
        }
        Collections.sort(out, (a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    /** Fallback DOM: ul.grid (tema kustom) lalu selector lama. */
    private ArrayList<MangaChapter> parseChaptersDom(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (document == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("ul.grid li a[href], #chapterlist li a[href], .eplister li a[href], .bxcl li a[href]")) {
            String href = link.absUrl("href");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("chapter")) continue;
            String title = firstNonEmpty(cleanChapterText(link.text()), cleanChapterText(href));
            float index = parseChapterIndex(firstNonEmpty(title, href));
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index);
            if (!seen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title.isEmpty() ? ("Chapter " + key) : title, "");
            chapter.chapterId = href;
            out.add(chapter);
        }
        if (out.isEmpty()) out.addAll(parseChapterOptions(seriesSlug, document, seen));
        Collections.sort(out, (a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    private ArrayList<MangaChapter> parseChapterOptions(String seriesSlug, Document document, LinkedHashSet<String> seen) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> localSeen = seen == null ? new LinkedHashSet<>() : seen;
        for (Element option : document.select("select[name=chapter] option[value], select#chapter option[value]")) {
            String value = option.attr("value").trim();
            if (value.isEmpty() || value.equals("#") || value.startsWith("?")) continue;
            String href = option.absUrl("value");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("chapter")) continue;
            String title = text(option);
            if (title.isEmpty() || title.equalsIgnoreCase("Select Chapter")) continue;
            float index = parseChapterIndex(firstNonEmpty(attr(option, "data-num"), title, href));
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index);
            if (!localSeen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, "");
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
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
                else loadPagesFromCandidates(chapterUrlCandidates(clean, index), 0, key, cb);
            }
            @Override public void onError(String message) { loadPagesFromCandidates(chapterUrlCandidates(clean, index), 0, key, cb); }
        });
    }

    private void loadPages(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        getDocument(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> pages = parsePages(document, chapterUrl);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Manhwa Indo kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Manhwa Indo gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadPagesFromCandidates(ArrayList<String> urls, int pos, String key, Result<ArrayList<String>> cb) {
        if (urls == null || pos >= urls.size()) { cb.onError("Chapter Manhwa Indo tidak ditemukan"); return; }
        loadPages(urls.get(pos), key, new Result<ArrayList<String>>() {
            @Override public void onSuccess(ArrayList<String> data, boolean hasNext) { cb.onSuccess(data, hasNext); }
            @Override public void onError(String message) { loadPagesFromCandidates(urls, pos + 1, key, cb); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            String encoded = URLEncoder.encode(q, "UTF-8");
            if (page <= 1) return base() + "/?s=" + encoded;
            return base() + "/page/" + page + "/?s=" + encoded;
        }

        FilterParts filter = parseFilters(genre);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (filter.type.isEmpty() && isTypeSort(s)) filter.type = s;
        if (filter.status.isEmpty() && ("completed".equals(s) || "complete".equals(s))) filter.status = "completed";
        if (filter.status.isEmpty() && ("ongoing".equals(s) || "on-going".equals(s))) filter.status = "ongoing";
        if (filter.status.isEmpty() && "hiatus".equals(s)) filter.status = "hiatus";

        HttpUrl.Builder builder = HttpUrl.parse(base() + "/explore/").newBuilder();
        builder.addQueryParameter("sort", orderParam(s));
        // Nilai type/status KAPITAL & case-sensitive di situs (Manhwa, Completed, ...)
        if (!filter.type.isEmpty()) builder.addQueryParameter("type", capitalize(filter.type));
        if (!filter.status.isEmpty()) builder.addQueryParameter("status", capitalize(filter.status));
        for (String value : filter.genres) builder.addQueryParameter("genre[]", value);
        String qs = builder.build().encodedQuery();
        String suffix = (qs == null || qs.isEmpty()) ? "" : "?" + qs;
        int p = Math.max(1, page);
        if (p <= 1) return base() + "/explore/" + suffix;
        return base() + "/explore/page/" + p + "/" + suffix;
    }

    /**
     * Kartu explore: a.series[href="/series/&lt;slug&gt;/" title] > img.cover;
     * di bawahnya div.line-clamp-1 (judul), span.bg-zinc-800 (chip genre),
     * span.bg-indigo-400 "Ch. &lt;n&gt;". Fallback utk halaman search (tanpa class series).
     */
    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements cards = document.select("a.series[href*='/series/']");
        if (cards.isEmpty()) cards = document.select("a[href*='/series/']");
        for (Element card : cards) {
            String href = card.absUrl("href");
            if (href.contains("/series/list-mode")) continue;
            String slug = cleanSeriesSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            Element img = card.selectFirst("img");
            String title = firstNonEmpty(attr(card, "title"), attr(img, "alt"), attr(img, "title"), cardTitleText(card), titleFromSlug(slug));
            String cover = imageUrl(img);
            String badge = "";
            for (Element span : card.select("span")) {
                String t = span.text().trim();
                if (t.matches("(?i)ch\\.\\s*[0-9]+.*")) { badge = t; break; }
            }
            if (badge.isEmpty()) {
                Element wrap = card.parent();
                if (wrap != null) {
                    for (Element span : wrap.select("span.bg-indigo-400, span")) {
                        String t = span.text().trim();
                        if (t.matches("(?i)ch\\.\\s*[0-9]+.*")) { badge = t; break; }
                    }
                }
            }
            String latest = cleanChapterText(badge);
            String genre = "";
            Element wrap = card.parent();
            if (wrap != null) {
                ArrayList<String> gs = new ArrayList<>();
                LinkedHashSet<String> gseen = new LinkedHashSet<>();
                Elements chips = wrap.select("span.bg-zinc-800");
                if (chips.isEmpty()) chips = wrap.select("span");
                for (Element span : chips) {
                    String t = span.text().trim();
                    if (t.isEmpty() || t.length() > 30 || t.matches("(?i)ch\\.\\s*[0-9]+.*")) continue;
                    if (gseen.add(t)) gs.add(t);
                }
                genre = TextUtils.join(", ", gs);
            }
            MangaPost post = new MangaPost(slug, title, cover, "", "", "", genre, "", latest, "").withSource(SOURCE_ID, LABEL);
            out.add(post);
        }
        return out;
    }

    private String cardTitleText(Element card) {
        if (card == null) return "";
        Element wrap = card.parent();
        if (wrap == null) return "";
        Element titleEl = wrap.selectFirst("div.line-clamp-1");
        return titleEl == null ? "" : titleEl.text().trim();
    }

    private MangaPost parseDetail(String clean, Document document) {
        String title = firstNonEmpty(text(document, "h1"), document.title() == null ? "" : document.title().split("[-|]")[0].trim());
        String cover = detailCover(document);
        String synopsis = firstNonEmpty(text(document, ".page-content p"), text(document, ".page-content"));
        String genre = genreFromDetail(document);
        String typeBadge = detailBadge(document, "manga", "manhwa", "manhua", "comic", "novel");
        String type = typeBadge.isEmpty() ? "" : MangaPost.normalizeType(typeBadge, "", "");
        String status = mapStatus(firstNonEmpty(MangaStatusParser.fromInfoRows(document), detailBadge(document, "berjalan", "tamat", "hiatus", "ongoing", "completed", "complete")));
        String author = firstNonEmpty(infoValue(document, "Penulis"), infoValue(document, "Author"));
        MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = detailInfo(document);
        post.latestChapter = infoValue(document, "Chapter Terbaru");
        return post;
    }

    /**
     * Cover: 1) og:image/twitter:image, 2) div bg-cover[style*=background-image]
     * (pola imgx-id.gmbr.pro/uploads/manga-images/&lt;huruf&gt;/&lt;slug&gt;/thumbnail.*),
     * 3) tag img biasa.
     */
    private String detailCover(Document document) {
        Element og = document.selectFirst("meta[property=og:image]");
        if (og != null && !og.attr("content").trim().isEmpty()) return og.attr("content").trim();
        Element tw = document.selectFirst("meta[name=twitter:image]");
        if (tw != null && !tw.attr("content").trim().isEmpty()) return tw.attr("content").trim();
        for (Element div : document.select("div[style*=background-image]")) {
            String style = div.attr("style");
            Matcher m = Pattern.compile("url\\(['\"]?([^'\")]+)['\"]?\\)").matcher(style);
            if (m.find()) {
                String url = m.group(1).trim();
                if (!url.isEmpty() && !url.startsWith("data:")) {
                    if (url.startsWith("//")) url = "https:" + url;
                    return url;
                }
            }
        }
        String jsonLd = jsonLdImage(document);
        if (!jsonLd.isEmpty()) return jsonLd;
        return imageUrl(document.selectFirst("img[src*=manga-images], img[data-src*=manga-images]"));
    }

    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        // Tema kustom: <img> langsung di .reader-area (tanpa ts_reader JSON)
        Elements images = document.select(".reader-area img[src], .reader-area img[data-src], div.reader-area img[src], div.reader-area img[data-src]");
        if (images.isEmpty()) images = document.select("img[src*='/manga-images/'], img[data-src*='/manga-images/']");
        for (Element img : images) addPage(out, seen, imageUrl(img), chapterUrl);

        if (out.isEmpty()) {
            String html = document.outerHtml();
            Matcher direct = Pattern.compile("https?://[^\"'<>\\s)]*(?:gmbr\\.pro|kiryuu\\.to|wibulep\\.xyz|wp\\.com|ikiru\\.wtf)/[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
            while (direct.find()) addPage(out, seen, direct.group().replace("\\/", "/"), chapterUrl);
        }
        return out;
    }

    private void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String raw, String chapterUrl) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/");
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = base() + url;
        if (!url.startsWith("http")) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("loading") || lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("gravatar") || lower.contains("histats") || lower.contains("/ads") || lower.contains("/iklan")) return;
        if (!lower.matches(".*\\.(jpg|jpeg|png|webp|avif)(?:\\?.*)?$") && !lower.contains("/manga-images/")) return;
        if (seen.add(url)) {
            // gmbr.pro menerapkan proteksi hotlink: gambar WAJIB dimuat dengan
            // Referer = URL chapter (bukan base domain).
            // Pengecualian: host cabang Ikiru memblokir referer asing (403).
            String referer = (chapterUrl == null || chapterUrl.trim().isEmpty()) ? base() + "/" : chapterUrl;
            if (!lower.contains("ikiru.wtf")) MangaImageLoader.registerImageReferer(url, referer);
            out.add(url);
        }
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element label : document.select("label.checkbox-label")) {
            Element input = label.selectFirst("input[name='genre[]'][value]");
            if (input == null) continue;
            String value = input.attr("value").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            String title = text(label.selectFirst("span"));
            if (title.isEmpty()) title = titleFromSlug(value);
            if (isNoiseGenre(title)) continue;
            out.add(new GenreItem(title, value));
        }
        return out;
    }

    /**
     * Cek SEMUA elemen <nav> (nav pertama biasanya menu header, bukan pagination).
     */
    private boolean hasNextPage(Document document, int page, int size) {
        for (Element nav : document.select("nav")) {
            for (Element link : nav.select("a[href]")) {
                Matcher m = Pattern.compile("/page/([0-9]+)/").matcher(link.absUrl("href"));
                while (m.find()) {
                    try { if (Integer.parseInt(m.group(1)) > page) return true; } catch (Exception ignored) {}
                }
            }
        }
        if (document.selectFirst("a[rel=next]") != null) return true;
        return false;
    }

    private void getDocument(String url, Result<Document> cb) { getDocument(new Request.Builder().url(url).headers(headers()).build(), cb); }

    private void getJsonArray(String url, Result<JSONArray> cb) {
        Request req = new Request.Builder().url(url).headers(jsonHeaders()).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JSONArray root = new JSONArray(body);
                    main.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { main.post(() -> cb.onError("JSON Manhwa Indo gagal dibaca")); }
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
                } catch(Exception e) { main.post(() -> cb.onError("Data Manhwa Indo gagal dibaca")); }
            }
        });
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

    private Headers jsonHeaders() {
        return new Headers.Builder()
                .set("Referer", base() + "/")
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "application/json,text/plain,*/*")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    private boolean needsEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return false;
        boolean missingChapter = loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty());
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

    /**
     * URL chapter: /&lt;series-slug&gt;-chapter-&lt;n&gt;/ dengan titik desimal
     * menjadi strip (197.5 -> solo-leveling-chapter-197-5).
     */
    private ArrayList<String> chapterUrlCandidates(String slug, float index) {
        ArrayList<String> out = new ArrayList<>();
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) return out;
        String idx = MangaChapter.formatIndex(index).replace(".", "-");
        boolean integer = Math.abs(index - Math.round(index)) < 0.001f;
        String padded = integer && index > 0 && index < 10 ? String.format(Locale.ROOT, "%02d", Math.round(index)) : idx;
        addCandidate(out, clean, padded);
        if (!padded.equals(idx)) addCandidate(out, clean, idx);
        addCandidate(out, clean, padded + "-bahasa-indonesia");
        if (!padded.equals(idx)) addCandidate(out, clean, idx + "-bahasa-indonesia");
        return out;
    }

    private void addCandidate(ArrayList<String> out, String slug, String chapter) {
        String url = base() + "/" + urlSegment(slug) + "-chapter-" + urlSegment(chapter) + "/";
        if (!out.contains(url)) out.add(url);
    }

    private FilterParts parseFilters(String raw) {
        FilterParts out = new FilterParts();
        if (raw == null) return out;
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) {
                String type = normalizeType(value.substring(5));
                if ("manga".equals(type) || "manhwa".equals(type) || "manhua".equals(type)) out.type = type;
                continue;
            }
            if (lower.startsWith("status:")) { out.status = normalizeStatus(value.substring(7)); continue; }
            if (lower.startsWith("genre:")) value = value.substring(6).trim();
            String knownId = knownGenreId(value);
            if (!knownId.isEmpty()) value = knownId;
            if (!value.isEmpty() && !out.genres.contains(value)) out.genres.add(value);
        }
        return out;
    }

    private String knownGenreId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        if (value.matches("^[0-9]+$")) {
            String mapped = LEGACY_GENRE_IDS.get(value);
            return mapped == null ? "" : mapped;
        }
        String slug = value.replace("_", "-").replace(" ", "-").replace("'", "").replace("(", "").replace(")", "");
        if (KNOWN_GENRE_SLUGS.contains(slug)) return slug;
        for (String[] item : genrePairs()) {
            String title = item[0].toLowerCase(Locale.ROOT).replace(" ", "-").replace("'", "").replace("(", "").replace(")", "");
            if (title.equals(slug) || item[0].toLowerCase(Locale.ROOT).equals(value)) return item[1];
        }
        return "";
    }

    private boolean isTypeSort(String sort) {
        return "manga".equals(sort) || "manhwa".equals(sort) || "manhua".equals(sort);
    }

    /**
     * Nilai sort EXACT milik situs (terverifikasi live):
     * modified = Update Terbaru (default), views = Paling Banyak Dibaca,
     * date = Terbaru Ditambahkan, title = Judul A-Z. Situs tidak punya Z-A.
     */
    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s) || "views".equals(s)) return "views";
        if ("added".equals(s) || "new".equals(s) || "latest_added".equals(s) || "date".equals(s)) return "date";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "title";
        // site has no Z-A sort; fall back to A-Z
        return "modified";
    }

    private String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(value)) return "manga";
        if ("manhwa".equals(value)) return "manhwa";
        if ("manhua".equals(value)) return "manhua";
        if ("comic".equals(value)) return "comic";
        if ("novel".equals(value)) return "novel";
        return value;
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "complete".equals(value)) return "completed";
        if ("ongoing".equals(value) || "on-going".equals(value)) return "ongoing";
        return value;
    }

    private String infoValue(Document document, String label) {
        String wanted = label == null ? "" : label.trim().replace(":", "").toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || document == null) return "";
        for (Element strong : document.select("strong")) {
            String key = strong.text().replace(":", "").trim().toLowerCase(Locale.ROOT);
            if (!key.equals(wanted)) continue;
            Element row = strong.parent();
            if (row == null) continue;
            Element link = row.selectFirst("a");
            if (link != null && !link.text().trim().isEmpty()) return link.text().trim();
            Element span = row.selectFirst("span");
            if (span != null && !span.text().trim().isEmpty()) return span.text().trim();
            String rest = row.text().replace(strong.text(), "").replace(":", "").trim();
            if (!rest.isEmpty()) return rest;
        }
        return "";
    }

    private String detailInfo(Document document) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Penulis", infoValue(document, "Penulis"));
        addInfo(values, "Artist", infoValue(document, "Artist"));
        addInfo(values, "Tahun Rilis", infoValue(document, "Tahun Rilis"));
        addInfo(values, "Chapter Terbaru", infoValue(document, "Chapter Terbaru"));
        addInfo(values, "Ditambahkan", infoValue(document, "Ditambahkan"));
        addInfo(values, "Terakhir Update", infoValue(document, "Terakhir Update"));
        addInfo(values, "Rating", detailRating(document));
        return TextUtils.join("\n", values);
    }

    private String detailRating(Document document) {
        for (Element badge : document.select("span.badge")) {
            String t = badge.text().trim();
            if (t.matches("[0-9]+([.,][0-9]+)?")) return t;
        }
        return "";
    }

    private String detailBadge(Document document, String... names) {
        for (Element badge : document.select("span.badge")) {
            for (String cls : badge.classNames()) {
                for (String name : names) if (cls.equalsIgnoreCase(name)) return name;
            }
        }
        return "";
    }

    private String mapStatus(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("berjalan".equals(v) || "ongoing".equals(v)) return "Ongoing";
        if ("tamat".equals(v) || "completed".equals(v) || "complete".equals(v)) return "Completed";
        if ("hiatus".equals(v)) return "Hiatus";
        return raw == null ? "" : raw.trim();
    }

    private String genreFromDetail(Document document) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='/genres/']")) {
            String href = link.attr("href");
            if (!href.matches("(?i).*/genres/[a-z0-9-]+/?$")) continue;
            String t = link.text().trim();
            if (!t.isEmpty() && seen.add(t)) values.add(t);
        }
        return TextUtils.join(", ", values);
    }

    private String jsonLdImage(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                String found = findJsonLdImage(new JSONObject(script.html()));
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String findJsonLdImage(Object element) {
        if (!(element instanceof JSONObject) && !(element instanceof JSONArray)) return "";
        try {
            if (element instanceof JSONObject) {
                JSONObject o = (JSONObject) element;
                if (o.has("image")) {
                    Object img = o.get("image");
                    if (img instanceof String) return (String) img;
                    if (img instanceof JSONObject && ((JSONObject) img).has("url")) return ((JSONObject) img).optString("url", "");
                    if (img instanceof JSONArray) {
                        JSONArray arr = (JSONArray) img;
                        for (int i = 0; i < arr.length(); i++) {
                            Object child = arr.get(i);
                            if (child instanceof String) return (String) child;
                            if (child instanceof JSONObject && ((JSONObject) child).has("url")) return ((JSONObject) child).optString("url", "");
                        }
                    }
                }
                if (o.has("@graph") && o.get("@graph") instanceof JSONArray) {
                    JSONArray graph = o.getJSONArray("@graph");
                    for (int i = 0; i < graph.length(); i++) {
                        String found = findJsonLdImage(graph.get(i));
                        if (!found.isEmpty()) return found;
                    }
                }
            } else {
                JSONArray arr = (JSONArray) element;
                for (int i = 0; i < arr.length(); i++) {
                    String found = findJsonLdImage(arr.get(i));
                    if (!found.isEmpty()) return found;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || value == null || value.trim().isEmpty()) return;
        out.add(label + ": " + value.trim());
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        Matcher m = Pattern.compile("(?i)(?:chapter|ch\\.?)\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(value);
        if (m.find()) return "Chapter " + m.group(1).replace(",", ".");
        return value;
    }

    private float parseChapterIndex(String raw) {
        if (raw == null) return -1f;
        Matcher matcher = Pattern.compile("(?i)(?:chapter|chap|ch)?\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        float last = -1f;
        while (matcher.find()) {
            try { last = Float.parseFloat(matcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
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
                        if ("series".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").trim();
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("series/")) value = value.substring(7);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }


    private String cleanChapterUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http") && value.toLowerCase(Locale.ROOT).contains("chapter")) return value;
        return "";
    }

    private String seriesUrl(String slug) { return base() + "/series/" + urlSegment(slug) + "/"; }

    private String imageUrl(Element img) {
        if (img == null) return "";
        String url = firstNonEmpty(img.absUrl("data-src"), img.absUrl("data-lazy-src"), img.absUrl("data-original"), img.absUrl("data-pagespeed-lazy-src"), img.absUrl("src"), img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"), img.attr("data-pagespeed-lazy-src"), img.attr("src"), imageFromSrcset(img.attr("data-srcset")), imageFromSrcset(img.attr("srcset")));
        if (url.startsWith("//")) url = "https:" + url;
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

    private boolean isNoiseGenre(String title) {
        if (title == null) return true;
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("genres") || value.equals("genre") || value.equals("manga") || value.equals("manhwa indo") || value.equals("manhwaindo");
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : genrePairs()) out.add(new GenreItem(value[0], value[1]));
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        return out;
    }

    private static String[][] genrePairs() {
        return new String[][]{{"Action", "action"}, {"Adaptation", "adaptation"}, {"Adult", "adult"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Cooking", "cooking"}, {"Crime", "crime"}, {"Crossdressing", "crossdressing"}, {"Demons", "demons"}, {"Drama", "drama"}, {"Ecchi", "ecchi"}, {"Fantasy", "fantasy"}, {"Full Color", "full-color"}, {"Game", "game"}, {"Gender Bender", "gender-bender"}, {"Ghosts", "ghosts"}, {"Gore", "gore"}, {"Harem", "harem"}, {"Historical", "historical"}, {"Horror", "horror"}, {"Isekai", "isekai"}, {"Josei", "josei"}, {"Leveling", "leveling"}, {"Long Strip", "long-strip"}, {"Magic", "magic"}, {"Manhwa", "manhwa"}, {"Martial", "martial"}, {"Martial Art", "martial-art"}, {"Martial Arts", "martial-arts"}, {"Mature", "mature"}, {"Mecha", "mecha"}, {"Medical", "medical"}, {"Military", "military"}, {"Mirror", "mirror"}, {"Murim", "murim"}, {"Music", "music"}, {"Mystery", "mystery"}, {"One-Shot", "one-shot"}, {"Oneshot", "oneshot"}, {"Project", "project"}, {"Psychological", "psychological"}, {"Regression", "regression"}, {"Reincarnation", "reincarnation"}, {"Romance", "romance"}, {"School", "school"}, {"School Life", "school-life"}, {"Sci-fi", "sci-fi"}, {"Seinen", "seinen"}, {"Sexual Violence", "sexual-violence"}, {"Shoujo", "shoujo"}, {"Shoujo Ai", "shoujo-ai"}, {"Shoujo(G)", "shoujog"}, {"Shounen", "shounen"}, {"Shounen Ai", "shounen-ai"}, {"Slice of Life", "slice-of-life"}, {"Smut", "smut"}, {"Sports", "sports"}, {"Super Power", "super-power"}, {"Superhero", "superhero"}, {"Supernatural", "supernatural"}, {"Supranatural", "supranatural"}, {"System", "system"}, {"Thriller", "thriller"}, {"Time Travel", "time-travel"}, {"Tragedy", "tragedy"}, {"Villainess", "villainess"}, {"Web Comic", "web-comic"}, {"Webtoon", "webtoon"}, {"Webtoons", "webtoons"}, {"Wuxia", "wuxia"}, {"Yaoi", "yaoi"}, {"Yuri", "yuri"}};
    }

    private static final Map<String, String> LEGACY_GENRE_IDS = new HashMap<>();
    private static final Set<String> KNOWN_GENRE_SLUGS = new HashSet<>();
    static {
        LEGACY_GENRE_IDS.put("3", "action"); LEGACY_GENRE_IDS.put("12", "adventure");
        LEGACY_GENRE_IDS.put("5", "comedy"); LEGACY_GENRE_IDS.put("13", "fantasy");
        LEGACY_GENRE_IDS.put("18", "drama"); LEGACY_GENRE_IDS.put("16", "romance");
        LEGACY_GENRE_IDS.put("10", "shounen"); LEGACY_GENRE_IDS.put("7", "slice-of-life");
        LEGACY_GENRE_IDS.put("6", "school-life"); LEGACY_GENRE_IDS.put("28", "isekai");
        LEGACY_GENRE_IDS.put("215", "webtoons"); LEGACY_GENRE_IDS.put("22", "ecchi");
        LEGACY_GENRE_IDS.put("23", "harem"); LEGACY_GENRE_IDS.put("30", "mature");
        LEGACY_GENRE_IDS.put("39", "supernatural"); LEGACY_GENRE_IDS.put("34", "sci-fi");
        LEGACY_GENRE_IDS.put("31", "seinen"); LEGACY_GENRE_IDS.put("41", "josei");
        LEGACY_GENRE_IDS.put("53", "horror"); LEGACY_GENRE_IDS.put("60", "mystery");
        LEGACY_GENRE_IDS.put("61", "psychological"); LEGACY_GENRE_IDS.put("42", "tragedy");
        LEGACY_GENRE_IDS.put("119", "thriller"); LEGACY_GENRE_IDS.put("56", "school");
        LEGACY_GENRE_IDS.put("48", "gore"); LEGACY_GENRE_IDS.put("97", "super-power");
        LEGACY_GENRE_IDS.put("276", "sports"); LEGACY_GENRE_IDS.put("577", "music");
        LEGACY_GENRE_IDS.put("162", "medical"); LEGACY_GENRE_IDS.put("117", "military");
        LEGACY_GENRE_IDS.put("88", "mecha"); LEGACY_GENRE_IDS.put("58", "magic");
        LEGACY_GENRE_IDS.put("51", "martial-arts"); LEGACY_GENRE_IDS.put("14", "game");
        LEGACY_GENRE_IDS.put("115", "cooking"); LEGACY_GENRE_IDS.put("112", "gender-bender");
        LEGACY_GENRE_IDS.put("191", "historical"); LEGACY_GENRE_IDS.put("1764", "crime");
        LEGACY_GENRE_IDS.put("217", "demons"); LEGACY_GENRE_IDS.put("7101", "crossdressing");
        LEGACY_GENRE_IDS.put("7103", "murim"); LEGACY_GENRE_IDS.put("7410", "regression");
        LEGACY_GENRE_IDS.put("6670", "smut"); LEGACY_GENRE_IDS.put("6669", "adult");
        LEGACY_GENRE_IDS.put("7185", "yaoi"); LEGACY_GENRE_IDS.put("81", "yuri");
        LEGACY_GENRE_IDS.put("717", "shounen-ai"); LEGACY_GENRE_IDS.put("140", "shoujo-ai");
        LEGACY_GENRE_IDS.put("125", "shoujo"); LEGACY_GENRE_IDS.put("522", "superhero");
        LEGACY_GENRE_IDS.put("520", "wuxia"); LEGACY_GENRE_IDS.put("46", "reincarnation");
        LEGACY_GENRE_IDS.put("4369", "oneshot"); LEGACY_GENRE_IDS.put("9", "one-shot");
        for (String[] pair : genrePairs()) KNOWN_GENRE_SLUGS.add(pair[1]);
        KNOWN_GENRE_SLUGS.addAll(LEGACY_GENRE_IDS.values());
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static final class FilterParts {
        final ArrayList<String> genres = new ArrayList<>();
        String type = "";
        String status = "";
    }
}
