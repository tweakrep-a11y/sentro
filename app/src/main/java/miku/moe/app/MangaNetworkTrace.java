package miku.moe.app;

import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public final class MangaNetworkTrace {
    public static final class Entry {
        public final long time;
        public final String method;
        public final String url;
        public final String finalUrl;
        public final int code;
        public final String message;
        public final String server;
        public final String contentType;
        public final boolean fromCache;
        public final String errorClass;
        public final String errorMessage;
        public final long durationMs;

        Entry(long time, String method, String url, String finalUrl, int code, String message, String server, String contentType, boolean fromCache, String errorClass, String errorMessage, long durationMs) {
            this.time = time;
            this.method = method;
            this.url = url;
            this.finalUrl = finalUrl;
            this.code = code;
            this.message = message;
            this.server = server;
            this.contentType = contentType;
            this.fromCache = fromCache;
            this.errorClass = errorClass;
            this.errorMessage = errorMessage;
            this.durationMs = durationMs;
        }

        public boolean failed() {
            return errorClass.length() > 0 || code >= 400;
        }

        public boolean redirectedToOtherHost() {
            String from = hostOf(url);
            String to = hostOf(finalUrl);
            return !from.isEmpty() && !to.isEmpty() && !from.equalsIgnoreCase(to);
        }
    }

    private static final int MAX_ENTRIES = 40;
    private static final int MAX_URL_LENGTH = 320;
    private static final ArrayList<Entry> ENTRIES = new ArrayList<>();

    public static final Interceptor INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            long start = System.currentTimeMillis();
            try {
                Response response = chain.proceed(request);
                record(request, response, null, System.currentTimeMillis() - start);
                return response;
            } catch (IOException e) {
                record(request, null, e, System.currentTimeMillis() - start);
                throw e;
            }
        }
    };

    private MangaNetworkTrace() {}

    private static void record(Request request, Response response, Throwable error, long durationMs) {
        try {
            String url = cut(request.url().toString());
            String finalUrl = url;
            int code = 0;
            String message = "";
            String server = "";
            String contentType = "";
            boolean fromCache = false;
            if (response != null) {
                code = response.code();
                message = response.message() == null ? "" : response.message();
                finalUrl = cut(response.request().url().toString());
                String serverHeader = response.header("Server");
                server = serverHeader == null ? "" : serverHeader;
                String typeHeader = response.header("Content-Type");
                contentType = typeHeader == null ? "" : typeHeader;
                fromCache = response.cacheResponse() != null && response.networkResponse() == null;
            }
            String errorClass = error == null ? "" : error.getClass().getName();
            String errorMessage = error == null || error.getMessage() == null ? "" : error.getMessage();
            Entry entry = new Entry(System.currentTimeMillis(), request.method(), url, finalUrl, code, message, server, contentType, fromCache, errorClass, errorMessage, durationMs);
            synchronized (ENTRIES) {
                ENTRIES.add(entry);
                while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
            }
        } catch (Exception ignored) {
        }
    }

    public static ArrayList<Entry> recent() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    public static Entry lastFailure() {
        ArrayList<Entry> list = recent();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).failed()) return list.get(i);
        }
        return null;
    }

    public static Entry lastDomainRedirect() {
        ArrayList<Entry> list = recent();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).redirectedToOtherHost()) return list.get(i);
        }
        return null;
    }

    public static String format(int limit) {
        ArrayList<Entry> list = recent();
        if (list.isEmpty()) return "Belum ada request jaringan manga yang tercatat.";
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder out = new StringBuilder();
        int from = Math.max(0, list.size() - Math.max(1, limit));
        for (int i = from; i < list.size(); i++) {
            Entry e = list.get(i);
            out.append('[').append(clock.format(new Date(e.time))).append("] ").append(e.method).append(' ').append(e.url).append('\n');
            if (e.errorClass.length() > 0) {
                out.append("    GAGAL ").append(e.errorClass);
                if (e.errorMessage.length() > 0) out.append(": ").append(e.errorMessage);
                out.append(" (").append(e.durationMs).append(" ms)\n");
            } else {
                out.append("    HTTP ").append(e.code);
                if (e.message.length() > 0) out.append(' ').append(e.message);
                out.append(" (").append(e.durationMs).append(" ms)");
                if (e.server.length() > 0) out.append(" server=").append(e.server);
                if (e.contentType.length() > 0) out.append(" type=").append(e.contentType);
                if (e.fromCache) out.append(" [cache]");
                out.append('\n');
                if (!e.finalUrl.equals(e.url)) out.append("    URL akhir: ").append(e.finalUrl).append('\n');
            }
        }
        return out.toString().trim();
    }

    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cut(String value) {
        if (value == null) return "";
        return value.length() > MAX_URL_LENGTH ? value.substring(0, MAX_URL_LENGTH) + "..." : value;
    }
}
