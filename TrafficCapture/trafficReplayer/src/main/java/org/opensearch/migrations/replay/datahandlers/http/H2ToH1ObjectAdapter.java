package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.opensearch.migrations.replay.H2Accumulation;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

/**
 * — converts a completed {@link H2Accumulation.StreamState} into a sequence
 * of Netty H1 {@link HttpObject}s ({@code DefaultHttpRequest} / {@code DefaultHttpResponse}
 * + {@code HttpContent}* + {@code LastHttpContent}). The output is fed unchanged into the
 * existing {@link HttpJsonTransformingConsumer} pipeline; downstream JSON transformers
 * never know the source was H2.
 *
 * <p>Mapping rules implemented from table:
 * <ul>
 *   <li>{@code :method} → request line method (required, otherwise MalformedH2RequestException)</li>
 *   <li>{@code :path} → request line target (required, except CONNECT)</li>
 *   <li>{@code :scheme} → dropped (H1 request line has no scheme for clients)</li>
 *   <li>{@code :authority} → {@code Host:} header</li>
 *   <li>{@code :status} → response status line</li>
 *   <li>connection-specific headers ({@code connection}, {@code keep-alive},
 *       {@code proxy-connection}, {@code upgrade}) — dropped per RFC 7540 §8.1.2.2</li>
 *   <li>{@code transfer-encoding: chunked} — dropped (forbidden in H2)</li>
 *   <li>{@code te} — preserved only if value is {@code trailers}</li>
 *   <li>multiple {@code cookie} crumbs — folded into one {@code cookie:} header with
 *       {@code "; "} separator per RFC 7540 §8.1.2.5</li>
 *   <li>header values containing CR/LF — fail with MalformedH2RequestException</li>
 *   <li>trailers (HEADERS frame after body with endStream) — attached to LastHttpContent</li>
 * </ul>
 */
@Slf4j
public final class H2ToH1ObjectAdapter {

    private H2ToH1ObjectAdapter() {}

    private static final Set<AsciiString> CONNECTION_SPECIFIC_HEADERS = Set.of(
            AsciiString.cached("connection"),
            AsciiString.cached("keep-alive"),
            AsciiString.cached("proxy-connection"),
            AsciiString.cached("upgrade"));

    /**
     * Convert the request side of the H2 stream to H1 objects. Throws
     * {@link MalformedH2RequestException} if the stream is missing required pseudo-headers
     * or contains forbidden bytes.
     */
    public static List<HttpObject> toH1RequestObjects(H2Accumulation.StreamState s) {
        var pseudo = s.getRequestPseudoHeaders();
        var methodPseudo = pseudo.get(":method");
        if (methodPseudo == null) {
            throw new MalformedH2RequestException("missing :method on stream " + s.getStreamId());
        }
        String method = methodPseudo.toString(StandardCharsets.UTF_8);
        var pathPseudo = pseudo.get(":path");
        boolean isConnect = "CONNECT".equals(method);
        if (pathPseudo == null && !isConnect) {
            throw new MalformedH2RequestException("missing :path on non-CONNECT stream " + s.getStreamId());
        }
        if (pathPseudo != null && isConnect) {
            throw new MalformedH2RequestException("CONNECT must not have :path on stream " + s.getStreamId());
        }

        var h1Headers = new DefaultHttpHeaders(true /* validate */);
        var authority = pseudo.get(":authority");
        boolean haveAuthority = authority != null;
        if (haveAuthority) {
            h1Headers.set(HttpHeaderNames.HOST, authority.toString(StandardCharsets.UTF_8));
        }

        applyRegularHeaders(s.getRequestHeaderFields(), h1Headers, haveAuthority);
        foldCookieCrumbs(h1Headers);

        var req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.valueOf(method),
                pathPseudo == null ? "" : pathPseudo.toString(StandardCharsets.UTF_8),
                h1Headers);

        return assembleObjects(req, s.getRequestBody(), s.getRequestTrailers());
    }

    /**
     * Convert the response side of the H2 stream to H1 objects. Mirrors {@link #toH1RequestObjects}
     * but uses {@code :status} as the status code.
     */
    public static List<HttpObject> toH1ResponseObjects(H2Accumulation.StreamState s) {
        var pseudo = s.getResponsePseudoHeaders();
        var statusPseudo = pseudo.get(":status");
        if (statusPseudo == null) {
            throw new MalformedH2RequestException("missing :status on response stream " + s.getStreamId());
        }
        int status;
        try {
            status = Integer.parseInt(statusPseudo.toString(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            throw new MalformedH2RequestException("non-numeric :status on stream " + s.getStreamId());
        }

        var h1Headers = new DefaultHttpHeaders(true);
        applyRegularHeaders(s.getResponseHeaderFields(), h1Headers, /*haveAuthority*/ false);
        foldCookieCrumbs(h1Headers);

        var resp = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status),
                h1Headers);

        return assembleObjects(resp, s.getResponseBody(), s.getResponseTrailers());
    }

    private static void applyRegularHeaders(List<Http2HeaderField> fields,
                                             DefaultHttpHeaders out, boolean haveAuthority) {
        for (var f : fields) {
            String name = f.getName().toStringUtf8().toLowerCase(Locale.ROOT);
            String val = f.getValue().toStringUtf8();
            if (shouldSkipH2Header(name, val, haveAuthority)) {
                continue;
            }
            if (val.indexOf('\r') >= 0 || val.indexOf('\n') >= 0) {
                throw new MalformedH2RequestException("CRLF in header value: " + name);
            }
            out.add(name, val);
        }
    }

    /**
     * H2-to-H1 emission rules per RFC 7540 §8.1.2.2:
     * connection-specific headers, chunked transfer-encoding, non-trailers TE, and Host
     * (when :authority is present) all drop. Other headers pass through.
     */
    private static boolean shouldSkipH2Header(String name, String value, boolean haveAuthority) {
        if (CONNECTION_SPECIFIC_HEADERS.contains(AsciiString.cached(name))) return true;
        if ("transfer-encoding".equals(name) && "chunked".equalsIgnoreCase(value)) return true;
        if ("te".equals(name) && !"trailers".equalsIgnoreCase(value)) return true;
        return "host".equals(name) && haveAuthority; // :authority wins
    }

    private static void foldCookieCrumbs(DefaultHttpHeaders headers) {
        var crumbs = headers.getAll(HttpHeaderNames.COOKIE);
        if (crumbs.size() > 1) {
            headers.set(HttpHeaderNames.COOKIE, String.join("; ", crumbs));
        }
    }

    private static List<HttpObject> assembleObjects(HttpObject lineAndHeaders, List<ByteBuf> body,
                                                     List<Http2HeaderField> trailers) {
        var out = new ArrayList<HttpObject>(body.size() + 2);
        out.add(lineAndHeaders);
        for (int i = 0; i < body.size() - 1; i++) {
            out.add(new DefaultHttpContent(body.get(i).retainedDuplicate()));
        }
        ByteBuf lastBuf = body.isEmpty()
                ? Unpooled.EMPTY_BUFFER
                : body.get(body.size() - 1).retainedDuplicate();
        var last = new DefaultLastHttpContent(lastBuf);
        if (trailers != null) {
            for (var t : trailers) {
                last.trailingHeaders().add(
                        t.getName().toStringUtf8().toLowerCase(Locale.ROOT),
                        t.getValue().toStringUtf8());
            }
        }
        out.add(last);
        return out;
    }

    /** Thrown when an H2 stream cannot be safely materialized as an H1 message. */
    public static class MalformedH2RequestException extends RuntimeException {
        public MalformedH2RequestException(String message) { super(message); }
    }
}
