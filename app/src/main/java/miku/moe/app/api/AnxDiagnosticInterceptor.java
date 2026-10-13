package miku.moe.app.api;

import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.net.URLDecoder;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Diagnostics for Anime X Nonton requests. Sensitive request-signature values are never logged.
 * Captures responses after AnxResponseDecryptInterceptor so invalid JSON is visible as received
 * by Retrofit/Gson rather than only seeing an opaque encrypted envelope.
 */
public final class AnxDiagnosticInterceptor implements Interceptor {
    private static final String TAG = "AnxApiDiagnostic";
    private static final int MAX_EVENTS = 10;
    private static final int MAX_BODY_PREVIEW = 2800;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        long started = System.nanoTime();
        String requestInfo = summarizeRequest(request);
        try {
            Response response = chain.proceed(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            String preview = preview(redactSensitiveFields(text), MAX_BODY_PREVIEW);
            String report = timestamp() + "\n" + requestInfo
                    + "\nRESPONSE: HTTP " + response.code() + " " + response.message()
                    + "\nElapsed: " + elapsedMs + " ms"
                    + "\nProtocol: " + response.protocol()
                    + "\nContent-Type: " + value(response.header("Content-Type"))
                    + "\nContent-Length header: " + value(response.header("Content-Length"))
                    + "\nX-Anx-Enc: " + value(response.header("X-Anx-Enc"))
                    + "\nResponse headers (sensitive values redacted):\n" + dumpHeaders(response.headers())
                    + "\nBody bytes delivered to parser: " + bytes.length
                    + "\nBody classification: " + classify(text)
                    + "\nBody preview (max " + MAX_BODY_PREVIEW + " chars):\n" + (preview.isEmpty() ? "<empty body>" : preview);
            record(report, response.isSuccessful() ? Log.INFO : Log.WARN);
            if (body == null) return response;
            ResponseBody replacement = ResponseBody.create(body.contentType(), bytes);
            return response.newBuilder().body(replacement).build();
        } catch (IOException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            record(timestamp() + "\n" + requestInfo + "\nNETWORK/DECRYPT EXCEPTION:\n" + sw, Log.ERROR);
            throw e;
        } catch (RuntimeException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            record(timestamp() + "\n" + requestInfo + "\nRUNTIME EXCEPTION:\n" + sw, Log.ERROR);
            throw e;
        }
    }

    private static String summarizeRequest(Request request) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("REQUEST: ").append(request.method()).append(' ').append(request.url());
        out.append("\nData-Agent: ").append(value(request.header("Data-Agent")));
        out.append("\nUser-Agent: ").append(value(request.header("User-Agent")));
        out.append("\nContent-Type: ").append(value(request.header("Content-Type")));
        out.append("\nAccept: ").append(value(request.header("Accept")));
        out.append("\nCache-Control: ").append(value(request.header("Cache-Control")));
        out.append("\nX-Anx-Ts: ").append(value(request.header("X-Anx-Ts")));
        out.append("\nX-Anx-Ver: ").append(value(request.header("X-Anx-Ver")));
        out.append("\nX-Anx-Cert: ").append(value(request.header("X-Anx-Cert")));
        out.append("\nX-Anx-N present: ").append(request.header("X-Anx-N") != null);
        out.append("\nX-Anx-Sig present: ").append(request.header("X-Anx-Sig") != null).append(" (value redacted)");
        out.append("\nX-Anx-Rsp: ").append(value(request.header("X-Anx-Rsp")));
        if (request.body() == null) {
            out.append("\nRequest body: <none>");
        } else {
            out.append("\nRequest body type: ").append(value(request.body().contentType() == null ? null : request.body().contentType().toString()));
            out.append("\nRequest body length: ").append(request.body().contentLength());
            out.append("\nRequest body is encrypted anx form: ").append(request.header("X-Anx-Sig") != null);
        }
        out.append("\nRequest headers (sensitive values redacted):\n").append(dumpHeaders(request.headers()));
        return out.toString();
    }

    private static String dumpHeaders(Headers headers) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            String lower = name.toLowerCase(Locale.ROOT);
            String value = headers.value(i);
            if (lower.equals("authorization") || lower.equals("proxy-authorization") || lower.equals("cookie")
                    || lower.equals("set-cookie") || lower.equals("x-anx-sig") || lower.equals("x-anx-n")
                    || lower.contains("token") || lower.contains("secret")) {
                value = "<redacted>";
            }
            if (out.length() > 0) out.append('\n');
            out.append(name).append(": ").append(value);
        }
        if (out.length() == 0) out.append("<no headers>");
        return out.toString();
    }

    private static String classify(String text) {
        if (text == null || text.trim().isEmpty()) return "EMPTY";
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "JSON-shaped";
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.startsWith("<")) return "HTML/XML/text (not JSON)";
        if (trimmed.startsWith("eyJ") || trimmed.matches("[A-Za-z0-9_+/=-]{40,}")) return "Base64/token-like text (possible still-encrypted payload)";
        return "Plain text or malformed JSON";
    }

    private static String redactSensitiveFields(String text) {
        if (text == null) return "";
        // Keep diagnostic structure while avoiding dumping playback tokens/URL credentials.
        return text.replaceAll("(?i)(\"(?:secretKey|video_auth|x-anx-sig|authorization|access_token|refresh_token)\"\\s*:\\s*\")[^\"]*(\")", "$1<redacted>$2")
                .replaceAll("(?i)(\"(?:channel_url(?:_hd|_fhd)?|download_url|episode_url_[0-9]+)\"\\s*:\\s*\")[^\"]*(\")", "$1<redacted-url>$2");
    }

    private static String preview(String text, int max) {
        if (text == null) return "";
        String visible = text.replace("\u0000", "\\0");
        if (visible.length() <= max) return visible;
        return visible.substring(0, max) + "\n… <truncated; total chars=" + visible.length() + ">";
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    private static String value(String value) { return value == null ? "<absent>" : value; }

    private static synchronized void record(String report, int priority) {
        EVENTS.addLast(report);
        while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        // Keep normal successful traces discoverable in Logcat, and errors visually prominent.
        String[] lines = report.split("\\n", -1);
        for (String line : lines) {
            if (priority == Log.ERROR) Log.e(TAG, line);
            else if (priority == Log.WARN) Log.w(TAG, line);
            else Log.i(TAG, line);
        }
    }

    /** Records original form parameters before the Guard interceptor encrypts them. */
    public static void recordOriginalFormPayload(String method, String path, String plainFormBody) {
        StringBuilder out = new StringBuilder();
        out.append(timestamp()).append("\nORIGINAL REQUEST PAYLOAD (before API2 encryption)")
                .append("\nMethod/path: ").append(method).append(' ').append(path);
        if (plainFormBody == null || plainFormBody.isEmpty()) {
            out.append("\nForm fields: <empty or no form body>");
        } else {
            String[] pairs = plainFormBody.split("&", -1);
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                String name = decodeFormComponent(rawName);
                String value = decodeFormComponent(rawValue);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("device") || lower.contains("token") || lower.contains("secret")
                        || lower.contains("password") || lower.contains("authorization") || lower.equals("api_key")) {
                    value = "<redacted>";
                }
                if (out.length() > 0) out.append('\n');
                out.append(name).append('=').append(value);
                if (out.length() > MAX_BODY_PREVIEW) {
                    out.append("\n… <payload fields truncated>");
                    break;
                }
            }
        }
        record(out.toString(), Log.INFO);
    }

    private static String decodeFormComponent(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    /** Recent request/response records for the in-app diagnostic dialog. */
    public static synchronized String getRecentTrace() {
        if (EVENTS.isEmpty()) return "<Belum ada rekaman HTTP dari Anime X Nonton di sesi ini>";
        StringBuilder out = new StringBuilder();
        for (String event : EVENTS) {
            if (out.length() > 0) out.append("\n\n============================================================\n\n");
            out.append(event);
        }
        return out.toString();
    }

    /** Store a source/parser-side error alongside the HTTP trace. */
    public static void logParserError(String context, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        record(timestamp() + "\nPARSER ERROR: " + context + "\n" + sw, Log.ERROR);
    }
}
