package org.opensearch.migrations.trafficcapture.netty;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMessageDecoderResult;
import io.netty.handler.codec.http.LastHttpContent;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class HttpCaptureSerializerUtil {

    public enum HttpProcessedState {
        ONGOING, FULL_MESSAGE
    }

    private static DecoderResult getDecoderResult(Object obj) {
        if (obj instanceof DefaultHttpRequest) {
            return ((DefaultHttpRequest) obj).decoderResult();
        } else if (obj instanceof DefaultHttpResponse) {
            return ((DefaultHttpResponse) obj).decoderResult();
        } else {
            return null;
        }
    }

    public static <T> HttpProcessedState addRelevantHttpMessageIndicatorEvents(
            IChannelConnectionCaptureSerializer<T> trafficOffloader,
            List<Object> parsedMsgs) throws IOException {
        Instant timestamp = Instant.now();
        for (var obj : parsedMsgs) {
            if (obj instanceof LastHttpContent) {
                trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
                return HttpProcessedState.FULL_MESSAGE;
            }
            var decoderResultLoose = getDecoderResult(obj);
            if (decoderResultLoose != null &&
                    decoderResultLoose.isSuccess() &&
                    decoderResultLoose instanceof HttpMessageDecoderResult) {
                var decoderResult = (HttpMessageDecoderResult) decoderResultLoose;
                trafficOffloader.addEndOfFirstLineIndicator(decoderResult.initialLineLength());
                trafficOffloader.addEndOfHeadersIndicator(decoderResult.headerSize());
            }
        }
        return HttpProcessedState.ONGOING;
    }
}
