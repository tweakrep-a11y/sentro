package miku.moe.app.api;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * Full port of the Anime X Nonton 26.10.4 GuardInterceptor (smali i91 + h91):
 *  - derives per-request keys from MASTER + "anx-guard-v1-salt"
 *  - encrypts the original form body as anx=<urlenc b64(nonce12||ct+tag)> (AES-256-GCM, req key)
 *  - signs the request with X-Anx-Sig (HMAC over canonical string, sig key)
 *  - adds X-Anx-Ts / X-Anx-N / X-Anx-Ver / X-Anx-Cert / X-Anx-Rsp headers
 *
 * Signature algorithm verified byte-exact against a real capture:
 *   calc  : c57edfcf9f07305b1eac856a793c49d19896251a099f2b7db577cc0cab459aca
 *   target: c57edfcf9f07305b1eac856a793c49d19896251a099f2b7db577cc0cab459aca
 */
public final class AnxGuardInterceptor implements Interceptor {
    private static final String TAG = "AnxGuard";
    private static final String MASTER_HEX =
            "0f4be99c888e987925fad6650daae56e5fc002ee1286a6c24fead72f29365862"; // MyApplication.onCreate
    private static final byte[] SALT = "anx-guard-v1-salt".getBytes(StandardCharsets.UTF_8);
    private static final String CERT_FINGERPRINT = "96b4414e8477168c"; // x-anx-cert of the original build
    private static final String VERSION_CODE = "22";
    private static final MediaType FORM =
            MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    /** Set false to fall back to legacy unsigned /api/ behaviour when a request is not API2. */
    private static final java.util.Set<String> API2_PATHS = new java.util.HashSet<>(java.util.Arrays.asList(
            "animexnonton/api/phalcon/api2/get_posts/",
            "animexnonton/api/phalcon/api2/search_category_collection/",
            "animexnonton/api/phalcon/api2/get_category_not_ongoing/",
            "animexnonton/api/phalcon/api2/get_category_ongoing/",
            "animexnonton/api/phalcon/api2/get_anime_by_genre/",
            "animexnonton/api/phalcon/api2/get_anime_genre_list/",
            "animexnonton/api/phalcon/api2/get_post_description/",
            "animexnonton/api/phalcon/api2/get_category_posts_secure/",
            "animexnonton/api/phalcon/api2/get_current_next_previous/",
            "animexnonton/api/phalcon/api2/user_rating/",
            "animexnonton/api/phalcon/api2/update_view/",
            "animexnonton/api/phalcon/api2/home_info/",
            "animexnonton/api/phalcon/api2/user_count/",
            "animexnonton/api/phalcon/api2/count_views/"
    ));

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String encodedPath = request.url().encodedPath();
        int i = encodedPath.indexOf("/api2/");
        boolean isApi2 = i >= 0;
        String api2Path = isApi2 ? encodedPath.substring(i) : encodedPath;
        if (!isApi2 || !API2_PATHS.contains(request.url().encodedPath().substring(
                request.url().encodedPath().indexOf("animexnonton/")))) {
            return chain.proceed(request); // legacy endpoint: untouched
        }

        try {
            long ts = System.currentTimeMillis() / 1000L;
            byte[] nonce16 = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce16);

            byte[] session = h91b("master", hexToBytes(MASTER_HEX), SALT);
            byte[] reqKey = h91b("req", session, nonce16);
            byte[] sigKey = h91b("sig", session, nonce16);

            // 1) plaintext = original form fields "k=v&k2=v2"
            String plainBody = extractFormFields(request.body());
            // Capture parameter names/values before encryption for in-app diagnostics; device/token
            // fields are redacted by AnxDiagnosticInterceptor.
            AnxDiagnosticInterceptor.recordOriginalFormPayload(request.method(), api2Path, plainBody);
            if ("POST".equalsIgnoreCase(request.method()) && (request.body() == null || plainBody.isEmpty())) {
                MediaType originalType = request.body() == null ? null : request.body().contentType();
                throw new IOException("API2 POST form body unexpectedly empty for " + api2Path
                        + " (bodyType=" + (request.body() == null ? "<null>" : request.body().getClass().getName())
                        + ", contentType=" + (originalType == null ? "<null>" : originalType) + ")");
            }

            // 2) encrypt: AES-GCM(reqKey), AAD "anx-req|<path>|<ts>", output b64(nonce12||ct+tag)
            byte[] nonce12 = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(reqKey, "AES"),
                    new GCMParameterSpec(128, nonce12));
            cipher.updateAAD(("anx-req|" + api2Path + "|" + ts).getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plainBody.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[12 + ct.length];
            System.arraycopy(nonce12, 0, payload, 0, 12);
            System.arraycopy(ct, 0, payload, 12, ct.length);
            String encB64 = Base64.encodeToString(payload, Base64.NO_WRAP);
            String finalBody = "anx=" + URLEncoder.encode(encB64, "UTF-8");

            // 3) canonical signature string (VERIFIED against real capture)
            String params = sortedQueryParamValues(request.url()); // values only, sorted, '&'-joined
            String bodyHash = sha256Hex(finalBody.getBytes(StandardCharsets.UTF_8));
            String canonical = request.method() + "\n"
                    + api2Path + "\n"
                    + params + "\n"
                    + ts + "\n"
                    + VERSION_CODE + "\n"
                    + CERT_FINGERPRINT + "\n"
                    + bodyHash;
            String sig = hex(hmacSha256(sigKey, canonical.getBytes(StandardCharsets.UTF_8)));

            // 4) rebuild request
            RequestBody body = RequestBody.create(finalBody, FORM);
            Request signed = request.newBuilder()
                    .method(request.method(), body)
                    .header("Data-Agent", "AnimeXNonton 26.10.4/22")
                    .header("User-Agent", "okhttp/5.5.0")
                    .header("Cache-Control", "max-age=0")
                    .header("X-Anx-Ts", String.valueOf(ts))
                    .header("X-Anx-N", urlSafeNoPad(nonce16))
                    .header("X-Anx-Ver", VERSION_CODE)
                    .header("X-Anx-Cert", CERT_FINGERPRINT)
                    .header("X-Anx-Rsp", "1")
                    .header("X-Anx-Sig", sig)
                    .build();
            return chain.proceed(signed);
        } catch (Exception e) {
            Log.e(TAG, "Guard signing failed for " + api2Path, e);
            AnxDiagnosticInterceptor.logParserError("Guard signing failed for " + api2Path, e);
            if (e instanceof IOException) throw (IOException) e;
            return chain.proceed(request); // preserve original behavior for non-I/O signing failures
        }
    }

    /**
     * Read the *serialized* url-encoded body before encrypting it.
     *
     * Do not rely only on `body instanceof FormBody`: Retrofit versions can supply a
     * different RequestBody implementation even for @FormUrlEncoded calls. The old
     * check silently returned an empty string, so a valid request was encrypted as
     * `anx=<encryption of empty text>` and the API answered with no posts.
     */
    private static String extractFormFields(RequestBody body) throws IOException {
        if (body == null) return "";
        MediaType type = body.contentType();
        boolean urlEncoded = type != null
                && "application".equalsIgnoreCase(type.type())
                && "x-www-form-urlencoded".equalsIgnoreCase(type.subtype());
        if (urlEncoded) {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        }
        // Compatibility fallback for FormBody implementations with an absent/unusual content type.
        if (body instanceof FormBody) {
            FormBody form = (FormBody) body;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < form.size(); i++) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(form.name(i), "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(form.value(i), "UTF-8"));
            }
            return sb.toString();
        }
        return "";
    }

    /** i91.a(HttpUrl): query param VALUES (not k=v), sorted, joined with '&'. */
    private static String sortedQueryParamValues(HttpUrl url) {
        List<String> values = new ArrayList<>();
        for (String name : url.queryParameterNames()) {
            for (String v : url.queryParameterValues(name)) values.add(v == null ? "" : v);
        }
        Collections.sort(values);
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append('&');
            sb.append(v);
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(byte[] key, byte[] msg) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg);
    }

    /** h91.b: two-layer HMAC key derivation with label + 0x01. */
    static byte[] h91b(String label, byte[] key, byte[] nonce) throws GeneralSecurityException {
        byte[] effectiveNonce = nonce.length == 0 ? new byte[32] : nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(effectiveNonce, "HmacSHA256"));
        byte[] first = mac.doFinal(key);
        mac.init(new SecretKeySpec(first, "HmacSHA256"));
        return mac.doFinal((label + "\u0001").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** x-anx-n: URL-safe Base64 without padding. */
    private static String urlSafeNoPad(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE)
                .replace("\n", "").replace("=", "");
    }
}
