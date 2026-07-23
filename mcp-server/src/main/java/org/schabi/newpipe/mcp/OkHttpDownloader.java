package org.schabi.newpipe.mcp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Downloader} backed by OkHttp, mirroring how PipePipe drives the extractor.
 *
 * <p>PipePipe's {@link Downloader} contract makes {@code executeAsync} abstract and returns a
 * {@link CancellableCall} that wraps a real {@code okhttp3.Call}; the YouTube stream extraction
 * path awaits and cancels those calls, so the server cannot use a dependency-free JDK client.</p>
 */
final class OkHttpDownloader extends Downloader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public Response execute(final Request request)
            throws IOException, ReCaptchaException {
        try (okhttp3.Response response = client.newCall(buildRequest(request)).execute()) {
            return toResponse(response, request.url());
        }
    }

    @Override
    public CancellableCall executeAsync(final Request request,
                                        final AsyncCallback callback) {
        final Call call = client.newCall(buildRequest(request));
        final CancellableCall cancellableCall = new CancellableCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onResponse(final Call c,
                                   final okhttp3.Response response) {
                try (okhttp3.Response r = response) {
                    callback.onSuccess(toResponse(r, request.url()));
                } catch (final Exception e) {
                    callback.onError(e);
                } finally {
                    cancellableCall.setFinished();
                }
            }

            @Override
            public void onFailure(final Call c, final IOException e) {
                try {
                    callback.onError(e);
                } finally {
                    cancellableCall.setFinished();
                }
            }
        });
        return cancellableCall;
    }

    private okhttp3.Request buildRequest(final Request request) {
        final String httpMethod = request.httpMethod();
        final byte[] dataToSend = request.dataToSend();

        RequestBody body = null;
        if (dataToSend != null) {
            body = RequestBody.create(dataToSend, (MediaType) null);
        } else if (requiresRequestBody(httpMethod)) {
            body = RequestBody.create(new byte[0], (MediaType) null);
        }

        final okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .method(httpMethod, body)
                .url(request.url());

        boolean hasUserAgent = false;
        for (final Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            final String name = header.getKey();
            // Content-Length is derived from the body by OkHttp.
            if ("Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            if ("User-Agent".equalsIgnoreCase(name)) {
                hasUserAgent = true;
            }
            for (final String value : header.getValue()) {
                builder.addHeader(name, value);
            }
        }
        if (!hasUserAgent) {
            builder.addHeader("User-Agent", USER_AGENT);
        }
        return builder.build();
    }

    private Response toResponse(final okhttp3.Response response, final String requestUrl)
            throws IOException, ReCaptchaException {
        final ResponseBody responseBody = response.body();
        final byte[] raw = responseBody == null ? new byte[0] : responseBody.bytes();
        final String body = new String(raw, StandardCharsets.UTF_8);

        if (response.code() == 429) {
            throw new ReCaptchaException("reCaptcha Challenge requested", requestUrl);
        }

        return new Response(response.code(), response.message(),
                response.headers().toMultimap(), body, raw, response.request().url().toString());
    }

    private static boolean requiresRequestBody(final String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                || method.equals("PROPPATCH") || method.equals("REPORT");
    }
}
