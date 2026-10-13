package miku.moe.app.api;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Decrypts Anime X Nonton API2 response bodies when the server marks them with
 * X-Anx-Enc: 1. This does not encrypt/sign requests; API2 requests must already
 * be produced by a compatible request layer before changing endpoint paths.
 */
public final class AnxResponseDecryptInterceptor implements Interceptor {
    private static final String TAG = "AnxResponseDecrypt";
    private static final String MASTER_HEX =
            "0f4be99c888e987925fad6650daae56e5fc002ee1286a6c24fead72f29365862";
    private static final byte[] SALT = "anx-guard-v1-salt".getBytes(StandardCharsets.UTF_8);

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        String encrypted = response.header("X-Anx-Enc");
        if (!"1".equals(encrypted)) return response;

        String ts = response.request().header("X-Anx-Ts");
        String nonceHeader = response.request().header("X-Anx-N");
        if (ts == null || nonceHeader == null) {
            throw new IOException("Anime X Nonton API2 response terenkripsi tetapi header timestamp/nonce tidak ada");
        }

        String encodedPath = response.request().url().encodedPath();
        int api2 = encodedPath.indexOf("/api2/");
        if (api2 < 0) {
            throw new IOException("Anime X Nonton API2 response terenkripsi tetapi URL tidak memiliki path /api2/");
        }
        String path = encodedPath.substring(api2);

        ResponseBody body = response.body();
        if (body == null) return response;
        // The API sends the encrypted envelope as a Base64 TEXT body (text/plain),
        // not as raw binary bytes. Decode Base64 before splitting nonce/ciphertext.
        byte[] encodedBody = body.bytes();
        try {
            byte[] ciphertext = decodeResponsePayload(encodedBody);
            byte[] nonce16 = Base64.decode(normalizeUrlBase64(nonceHeader), Base64.DEFAULT);
            byte[] session = h91b("master", hexToBytes(MASTER_HEX), SALT);
            byte[] key = h91b("resp", session, nonce16);
            if (ciphertext.length < 28) throw new GeneralSecurityException("Decoded payload too short");

            byte[] nonce = new byte[12];
            System.arraycopy(ciphertext, 0, nonce, 0, nonce.length);
            byte[] aad = ("anx-resp|" + path + "|" + ts).getBytes(StandardCharsets.UTF_8);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] plain = cipher.doFinal(ciphertext, 12, ciphertext.length - 12);
            ResponseBody clearBody = ResponseBody.create(body.contentType(), plain);
            return response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Encoding")
                    .body(clearBody).build();
        } catch (Exception e) {
            Log.e(TAG, "Unable to decrypt API2 response for " + path, e);
            // Returning Base64 ciphertext here makes Retrofit/Gson report a misleading
            // "malformed JSON at line 1 column 1". Fail with the real transport error instead.
            throw new IOException("Anime X Nonton API2 response gagal didekripsi pada " + path, e);
        }
    }


    private static byte[] decodeResponsePayload(byte[] responseBody) throws GeneralSecurityException {
        String encoded = new String(responseBody, StandardCharsets.UTF_8).trim();
        // tolerate line breaks/whitespace commonly inserted into Base64 text bodies
        encoded = encoded.replaceAll("\\s+", "");
        if (encoded.isEmpty()) throw new GeneralSecurityException("Empty encrypted response body");

        // Normalize URL-safe Base64 as well as standard Base64, and restore omitted padding.
        encoded = encoded.replace('-', '+').replace('_', '/');
        int remainder = encoded.length() & 3;
        if (remainder == 1) throw new GeneralSecurityException("Invalid Base64 response length");
        if (remainder != 0) {
            StringBuilder padded = new StringBuilder(encoded);
            while ((padded.length() & 3) != 0) padded.append('=');
            encoded = padded.toString();
        }
        try {
            byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
            if (decoded.length < 28) throw new GeneralSecurityException("Decoded payload too short");
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Response body is not valid Base64", e);
        }
    }

    private static byte[] h91b(String label, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        byte[] effectiveNonce = nonce.length == 0 ? new byte[32] : nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(effectiveNonce, "HmacSHA256"));
        byte[] first = mac.doFinal(key);
        mac.init(new SecretKeySpec(first, "HmacSHA256"));
        byte[] labelBytes = (label + "\u0001").getBytes(StandardCharsets.UTF_8);
        return mac.doFinal(labelBytes);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String normalizeUrlBase64(String value) {
        String normalized = value.replace('-', '+').replace('_', '/');
        while ((normalized.length() & 3) != 0) normalized += "=";
        return normalized;
    }
}
