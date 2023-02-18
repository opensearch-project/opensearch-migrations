package org.opensearch.migrations.replay;

import org.apache.http.HttpException;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.conn.DefaultHttpResponseParser;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.opensearch.migrations.replay.netty.NettyScanningHttpProxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.function.Consumer;

public class NettyPacketToHttpHandler implements IPacketToHttpHandler {

    private final Socket socket;

    NettyPacketToHttpHandler(int localProxyPort) throws IOException {
        socket = new Socket("127.0.0.1", localProxyPort);
    }

    @Override
    public void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException {
        try {
            var socketOutput = socket.getOutputStream();
            assert !socket.isClosed();
            socketOutput.write(nextRequestPacket);
            System.err.println("Wrote to socket output bytes: "+ nextRequestPacket.length);
            socketOutput.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void finalizeRequest(Consumer<IResponseSummary> onResponseFinishedCallback)
            throws InvalidHttpStateException {
        assert !socket.isClosed();
        System.err.println("finalizeRequest begun");
        try {
            assert !socket.isClosed();
            try (var socketInput = socket.getInputStream()) {
                try (var objectStream = new ObjectInputStream(socketInput)) {
                    onResponseFinishedCallback.accept((IResponseSummary)objectStream.readObject());
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
