package miku.moe.app;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class MangaChapterPageStore {
    static final int PRIORITY_VISIBLE = 0;
    static final int PRIORITY_FORWARD = 1;
    static final int PRIORITY_BACKWARD = 2;

    private static final String DIR_NAME = "manga_reader_pages";
    private static final int WORKERS = 3;
    private static final long MAX_STORE_BYTES = 256L * 1024L * 1024L;
    private static final long KEEP_RECENT_MS = 3L * 60L * 1000L;
    private static final long STALE_TMP_MS = 10L * 60L * 1000L;
    private static final int PRUNE_EVERY = 12;

    private static final Object LOCK = new Object();
    private static final Map<String, Job> JOBS = new HashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger COMPLETED = new AtomicInteger();
    private static volatile File directory;
    private static volatile OkHttpClient client;

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            WORKERS, WORKERS, 30L, TimeUnit.SECONDS,
            new PriorityBlockingQueue<Runnable>(32, new Comparator<Runnable>() {
                @Override public int compare(Runnable a, Runnable b) {
                    if (!(a instanceof Task) || !(b instanceof Task)) return 0;
                    Task left = (Task) a;
                    Task right = (Task) b;
                    if (left.priority != right.priority) return left.priority < right.priority ? -1 : 1;
                    return Long.compare(left.sequence, right.sequence);
                }
            }),
            new ThreadFactory() {
                private final AtomicInteger index = new AtomicInteger();
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "MangaChapterPage-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    interface Listener {
        void onResult(File file, Throwable error);
    }

    static final class PageException extends IOException {
        PageException(String message) { super(message); }
    }

    private static final class Job {
        final String key;
        final String url;
        final String sourceId;
        final File target;
        final List<Listener> listeners = new ArrayList<>();
        final AtomicBoolean started = new AtomicBoolean(false);
        volatile boolean cancelled;
        int attempt;

        Job(String key, String url, String sourceId, File target, int attempt) {
            this.key = key;
            this.url = url;
            this.sourceId = sourceId;
            this.target = target;
            this.attempt = attempt;
        }
    }

    private static final class Task implements Runnable {
        final Job job;
        final Context context;
        final int priority;
        final long sequence;

        Task(Job job, Context context, int priority) {
            this.job = job;
            this.context = context;
            this.priority = priority;
            this.sequence = SEQUENCE.incrementAndGet();
        }

        @Override public void run() {
            execute(job, context);
        }
    }

    private MangaChapterPageStore() {}

    static File cached(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty()) return null;
        File file = fileFor(context, url.trim(), sourceId);
        if (usable(file)) {
            try { file.setLastModified(System.currentTimeMillis()); } catch (Exception ignored) { }
            return file;
        }
        return null;
    }

    static void request(Context context, String url, String sourceId, int priority, int attempt, Listener listener) {
        if (context == null || url == null || url.trim().isEmpty()) {
            if (listener != null) listener.onResult(null, new PageException("alamat gambar kosong"));
            return;
        }
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        File file = fileFor(app, safeUrl, sourceId);
        if (usable(file)) {
            try { file.setLastModified(System.currentTimeMillis()); } catch (Exception ignored) { }
            if (listener != null) listener.onResult(file, null);
            return;
        }
        String key = keyFor(safeUrl, sourceId);
        Job job;
        synchronized (LOCK) {
            job = JOBS.get(key);
            if (job == null || job.cancelled) {
                job = new Job(key, safeUrl, sourceId, file, attempt);
                JOBS.put(key, job);
            } else if (attempt > job.attempt) {
                job.attempt = attempt;
            }
            if (listener != null) job.listeners.add(listener);
        }
        try {
            EXECUTOR.execute(new Task(job, app, priority));
        } catch (Exception e) {
            finish(job, null, e);
        }
    }

    static void cancelPreloads() {
        synchronized (LOCK) {
            for (Job job : JOBS.values()) {
                if (job.listeners.isEmpty() && !job.started.get()) job.cancelled = true;
            }
        }
    }

    static void delete(Context context, List<String> urls, String sourceId) {
        if (context == null || urls == null || urls.isEmpty()) return;
        Context app = context.getApplicationContext();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            String safeUrl = url.trim();
            synchronized (LOCK) {
                Job job = JOBS.get(keyFor(safeUrl, sourceId));
                if (job != null && job.listeners.isEmpty() && !job.started.get()) job.cancelled = true;
            }
            File file = fileFor(app, safeUrl, sourceId);
            try { file.delete(); } catch (Exception ignored) { }
        }
    }

    private static void execute(Job job, Context context) {
        if (!job.started.compareAndSet(false, true)) return;
        if (job.cancelled) {
            synchronized (LOCK) {
                if (JOBS.get(job.key) == job) JOBS.remove(job.key);
            }
            finish(job, null, new PageException("dibatalkan"));
            return;
        }
        File result = null;
        Throwable failure = null;
        try {
            if (usable(job.target)) result = job.target;
            else result = download(job, context);
        } catch (Throwable error) {
            failure = error;
        }
        synchronized (LOCK) {
            if (JOBS.get(job.key) == job) JOBS.remove(job.key);
        }
        finish(job, result, failure);
        if (result != null && COMPLETED.incrementAndGet() % PRUNE_EVERY == 0) {
            try { prune(context); } catch (Throwable ignored) { }
        }
    }

    private static void finish(Job job, File file, Throwable error) {
        List<Listener> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(job.listeners);
            job.listeners.clear();
            if (JOBS.get(job.key) == job) JOBS.remove(job.key);
        }
        for (Listener listener : copy) {
            try { listener.onResult(file, error); } catch (Throwable ignored) { }
        }
    }

    private static File download(Job job, Context context) throws IOException {
        File dir = directory(context);
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new PageException("folder cache tidak bisa dibuat");
        String resolved = MangaImageLoader.resolveImageUrl(job.url, job.sourceId);
        if (resolved == null || resolved.trim().isEmpty()) throw new PageException("alamat gambar kosong");
        Request request;
        try {
            request = new Request.Builder()
                    .url(resolved)
                    .headers(MangaImageLoader.readerHeaders(resolved, job.sourceId, job.attempt))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new PageException("alamat gambar tidak valid");
        }
        File temp = new File(dir, job.target.getName() + ".tmp" + SEQUENCE.incrementAndGet());
        Call call = httpClient().newCall(request);
        try {
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new PageException("HTTP " + response.code());
                ResponseBody body = response.body();
                if (body == null) throw new PageException("respons kosong");
                String type = response.header("Content-Type");
                if (type != null) {
                    String lower = type.toLowerCase(java.util.Locale.ROOT);
                    if (lower.startsWith("text/") || lower.contains("html") || lower.contains("json") || lower.contains("xml")) {
                        throw new PageException("bukan gambar (" + lower + ")");
                    }
                }
                long expected = body.contentLength();
                long written = 0L;
                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        written += read;
                    }
                    out.flush();
                }
                if (written <= 0L) throw new PageException("respons kosong");
                if (expected > 0L && written != expected) throw new PageException("unduhan terputus");
            }
            if (!validImage(temp)) throw new PageException("file bukan gambar valid");
            if (job.target.exists() && !job.target.delete()) throw new PageException("file lama tidak bisa diganti");
            if (!temp.renameTo(job.target)) throw new PageException("gagal menyimpan file");
            return job.target;
        } finally {
            if (temp.exists()) {
                try { temp.delete(); } catch (Exception ignored) { }
            }
        }
    }

    private static boolean usable(File file) {
        return file != null && file.isFile() && file.length() > 0L;
    }

    private static boolean validImage(File file) {
        if (!usable(file)) return false;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth > 0 && options.outHeight > 0) return true;
        } catch (Throwable ignored) { }
        return hasImageSignature(file);
    }

    private static boolean hasImageSignature(File file) {
        byte[] head = new byte[16];
        int count = 0;
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while (count < head.length && (read = in.read(head, count, head.length - count)) > 0) count += read;
        } catch (Exception e) {
            return false;
        }
        if (count < 12) return false;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return true;
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true;
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return true;
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return true;
        if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return true;
        return false;
    }

    private static OkHttpClient httpClient() {
        OkHttpClient current = client;
        if (current != null) return current;
        synchronized (LOCK) {
            if (client == null) {
                OkHttpClient base = MikuMoeApplication.chapterHttpClient();
                client = base != null ? base : CloudflareHelper.apply(new OkHttpClient.Builder()).cache(null).build();
            }
            return client;
        }
    }

    private static File directory(Context context) {
        File current = directory;
        if (current != null) return current;
        synchronized (LOCK) {
            if (directory == null) directory = new File(context.getApplicationContext().getCacheDir(), DIR_NAME);
            return directory;
        }
    }

    private static String keyFor(String url, String sourceId) {
        return (sourceId == null ? "" : sourceId.trim()) + "|" + url;
    }

    private static File fileFor(Context context, String url, String sourceId) {
        return new File(directory(context), hash(keyFor(url, sourceId)));
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode()) + Integer.toHexString(value.length());
        }
    }

    private static void prune(Context context) {
        File dir = directory(context);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long now = System.currentTimeMillis();
        long total = 0L;
        ArrayList<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            if (file.getName().contains(".tmp")) {
                if (now - file.lastModified() > STALE_TMP_MS) {
                    try { file.delete(); } catch (Exception ignored) { }
                }
                continue;
            }
            total += file.length();
            candidates.add(file);
        }
        if (total <= MAX_STORE_BYTES) return;
        File[] sorted = candidates.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long target = (MAX_STORE_BYTES * 3L) / 4L;
        for (File file : sorted) {
            if (total <= target) break;
            if (now - file.lastModified() < KEEP_RECENT_MS) continue;
            long size = file.length();
            try {
                if (file.delete()) total -= size;
            } catch (Exception ignored) { }
        }
    }
}
