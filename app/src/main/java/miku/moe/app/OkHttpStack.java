package miku.moe.app;

import com.android.volley.AuthFailureError;
import com.android.volley.Header;
import com.android.volley.Request;
import com.android.volley.toolbox.BaseHttpStack;
import com.android.volley.toolbox.HttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

public final class OkHttpStack extends BaseHttpStack {
    private final OkHttpClient client;

    public OkHttpStack(OkHttpClient client) {
        this.client = client.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public HttpResponse executeRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        int timeoutMs = request.getTimeoutMs();
        OkHttpClient perCall = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(request.getUrl())
                .method(methodFor(request), requestBodyFor(request));
        Map<String, String> requestHeaders = request.getHeaders();
        for (String name : requestHeaders.keySet()) {
            builder.header(name, requestHeaders.get(name));
        }
        if (additionalHeaders != null) {
            for (String name : additionalHeaders.keySet()) {
                builder.header(name, additionalHeaders.get(name));
            }
        }

        Call call = perCall.newCall(builder.build());
        okhttp3.Response okResponse = call.execute();
        byte[] data = okResponse.body() == null ? new byte[0] : okResponse.body().bytes();
        List<Header> headers = new ArrayList<>();
        Headers hs = okResponse.headers();
        for (int i = 0; i < hs.size(); i++) {
            headers.add(new Header(hs.name(i), hs.value(i)));
        }
        return new HttpResponse(okResponse.code(), headers, data.length, new ByteArrayInputStream(data));
    }

    private static String methodFor(Request<?> request) {
        switch (request.getMethod()) {
            case Request.Method.GET: return "GET";
            case Request.Method.POST: return "POST";
            case Request.Method.PUT: return "PUT";
            case Request.Method.DELETE: return "DELETE";
            case Request.Method.HEAD: return "HEAD";
            case Request.Method.OPTIONS: return "OPTIONS";
            case Request.Method.TRACE: return "TRACE";
            case Request.Method.PATCH: return "PATCH";
            default: return "GET";
        }
    }

    private static RequestBody requestBodyFor(Request<?> request) throws AuthFailureError {
        byte[] body = request.getBody();
        if (body == null) return null;
        return RequestBody.create(body, MediaType.parse(request.getBodyContentType()));
    }
}
