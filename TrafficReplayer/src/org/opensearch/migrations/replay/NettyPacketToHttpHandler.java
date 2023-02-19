package org.opensearch.migrations.replay;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
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
    public void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback)
            throws InvalidHttpStateException {
        assert !socket.isClosed();
        System.err.println("finalizeRequest begun");
        try {
            assert !socket.isClosed();
            try (var socketInput = socket.getInputStream()) {
                try (var objectStream = new ObjectInputStream(socketInput)) {
                    onResponseFinishedCallback.accept((AggregatedRawResponse)objectStream.readObject());
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
