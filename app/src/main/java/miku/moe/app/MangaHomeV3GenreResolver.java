package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MangaHomeV3GenreResolver {
    public interface Callback {
        void onResolved(MangaPost post);
    }

    private static final int FULL_GENRE_COUNT = 3;
    private static final int MAX_ACTIVE = 3;
    private static final long RETRY_MS = 120000L;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<String, Info> CACHE = new HashMap<>();
    private static final HashMap<String, Long> FAILED_AT = new HashMap<>();
    private static final HashMap<String, ArrayList<Waiter>> WAITERS = new HashMap<>();
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static int active = 0;

    private MangaHomeV3GenreResolver() {}

    private static final class Info {
        final String genre;
        final String status;
        final String type;

        Info(String genre, String status, String type) {
            this.genre = genre;
            this.status = status;
            this.type = type;
        }
    }

    private static final class Waiter {
        final MangaPost post;
        Callback callback;

        Waiter(MangaPost post, Callback callback) {
            this.post = post;
            this.callback = callback;
        }
    }

    private static final class Request {
        final String key;
        final String sourceId;
        final String slug;
        final AtomicBoolean done = new AtomicBoolean(false);

        Request(String key, String sourceId, String slug) {
            this.key = key;
            this.sourceId = sourceId;
            this.slug = slug;
        }
    }

    public static String key(MangaPost post) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return "";
        String sourceId = post.getSourceId();
        if (sourceId == null || sourceId.trim().isEmpty()) return "";
        return sourceId.trim() + "|" + post.slug.trim();
    }

    public static boolean needsResolve(MangaPost post) {
        if (key(post).isEmpty()) return false;
        String genre = post.genre == null ? "" : post.genre.trim();
        if (genre.isEmpty()) return true;
        return MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(post.getSourceId()) && countGenres(genre) < FULL_GENRE_COUNT;
    }

    public static boolean needsResolveWithStatus(MangaPost post) {
        if (key(post).isEmpty()) return false;
        if (needsResolve(post)) return true;
        return post.status == null || post.status.trim().isEmpty();
    }

    public static boolean needsType(MangaPost post) {
        if (key(post).isEmpty()) return false;
        String type = post.getTypeLabel();
        return type == null || type.trim().isEmpty();
    }

    /** True selama detail untuk post ini masih mengantre atau sedang diproses (dipakai untuk shimmer). */
    public static boolean isPending(MangaPost post) {
        String key = key(post);
        if (key.isEmpty()) return false;
        synchronized (LOCK) {
            return WAITERS.containsKey(key);
        }
    }

    /** Membatalkan permintaan yang masih mengantre (belum berjalan) untuk post ini, mis. saat item keluar layar. */
    public static void cancelQueued(MangaPost post) {
        String key = key(post);
        if (key.isEmpty()) return;
        synchronized (LOCK) {
            ArrayList<Waiter> waiters = WAITERS.get(key);
            if (waiters == null) return;
            for (int i = waiters.size() - 1; i >= 0; i--) {
                if (waiters.get(i).post == post) waiters.remove(i);
            }
            if (!waiters.isEmpty()) return;
            java.util.Iterator<Request> iterator = QUEUE.iterator();
            while (iterator.hasNext()) {
                if (key.equals(iterator.next().key)) {
                    iterator.remove();
                    WAITERS.remove(key);
                    return;
                }
            }
            // Request sudah berjalan: biarkan selesai, hasilnya tetap masuk cache.
        }
    }

    public static boolean applyCached(MangaPost post) {
        String key = key(post);
        if (key.isEmpty()) return false;
        Info info;
        synchronized (LOCK) {
            info = CACHE.get(key);
        }
        if (info == null) return false;
        boolean changed = false;
        if (!info.genre.isEmpty() && !info.genre.equals(post.genre)) {
            post.genre = info.genre;
            changed = true;
        }
        if (!info.status.isEmpty() && (post.status == null || post.status.trim().isEmpty())) {
            post.status = info.status;
            changed = true;
        }
        if (!info.type.isEmpty() && needsType(post)) {
            post.typeLabel = info.type;
            changed = true;
        }
        return changed;
    }

    public static void request(MangaPost post, Callback callback) {
        request(post, callback, false);
    }

    public static void request(MangaPost post, Callback callback, boolean includeStatus) {
        request(post, callback, includeStatus, false);
    }

    public static void requestType(MangaPost post, Callback callback) {
        request(post, callback, false, true);
    }

    public static void request(MangaPost post, Callback callback, boolean includeStatus, boolean includeType) {
        boolean needs = includeStatus ? needsResolveWithStatus(post) : needsResolve(post);
        if (includeType && needsType(post)) needs = true;
        if (!needs) return;
        String key = key(post);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (CACHE.containsKey(key)) return;
            Long failedAt = FAILED_AT.get(key);
            if (failedAt != null && now - failedAt < RETRY_MS) return;
            ArrayList<Waiter> waiters = WAITERS.get(key);
            if (waiters != null) {
                for (Waiter waiter : waiters) {
                    if (waiter.post == post) {
                        waiter.callback = callback;
                        return;
                    }
                }
                waiters.add(new Waiter(post, callback));
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(new Waiter(post, callback));
            WAITERS.put(key, waiters);
            QUEUE.add(new Request(key, post.getSourceId(), post.slug.trim()));
        }
        pump();
    }

    private static void pump() {
        while (true) {
            Request request;
            synchronized (LOCK) {
                if (active >= MAX_ACTIVE) return;
                request = QUEUE.poll();
                if (request == null) return;
                active++;
            }
            start(request);
        }
    }

    private static void start(Request request) {
        try {
            MangaSourceFactory.createBySourceId(request.sourceId).detail(request.slug, new KomikcastClient.Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    String genre = detail == null || detail.genre == null ? "" : detail.genre.trim();
                    String status = detail == null || detail.status == null ? "" : detail.status.trim();
                    String type = "";
                    if (detail != null) {
                        String detailType = detail.getTypeLabel();
                        type = detailType == null ? "" : detailType.trim();
                    }
                    finish(request, genre, status, type);
                }

                @Override public void onError(String message) {
                    finish(request, "", "", "");
                }
            });
        } catch (Exception e) {
            finish(request, "", "", "");
        }
    }

    private static void finish(Request request, String genre, String status, String type) {
        if (!request.done.compareAndSet(false, true)) return;
        ArrayList<Waiter> waiters;
        boolean empty = genre.isEmpty() && status.isEmpty() && type.isEmpty();
        synchronized (LOCK) {
            active--;
            waiters = WAITERS.remove(request.key);
            if (empty) {
                FAILED_AT.put(request.key, System.currentTimeMillis());
            } else {
                FAILED_AT.remove(request.key);
                CACHE.put(request.key, new Info(genre, status, type));
            }
        }
        if (waiters != null && !waiters.isEmpty()) {
            // Waiter tetap diberi tahu saat gagal, supaya shimmer berhenti (tidak berputar selamanya).
            final ArrayList<Waiter> targets = waiters;
            MAIN.post(() -> {
                for (Waiter waiter : targets) {
                    if (!empty) {
                        if (!genre.isEmpty()) waiter.post.genre = genre;
                        if (!status.isEmpty() && (waiter.post.status == null || waiter.post.status.trim().isEmpty())) waiter.post.status = status;
                        if (!type.isEmpty() && needsType(waiter.post)) waiter.post.typeLabel = type;
                    }
                    if (waiter.callback != null) waiter.callback.onResolved(waiter.post);
                }
            });
        }
        pump();
    }

    private static int countGenres(String genre) {
        int count = 0;
        for (String part : genre.split(",")) {
            if (!part.trim().isEmpty()) count++;
        }
        return count;
    }
}
