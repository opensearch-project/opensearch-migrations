package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * A raw-socket HTTP/1.1 server whose answer to each request is scripted, including answers a real HTTP server
 * library won't give: never responding, or resetting the connection before the request body is read.
 */
final class ScriptedHttpServer implements AutoCloseable {
    sealed interface Action {}

    /** Only valid before the body is read: read it, then ask again. */
    record ReadBody() implements Action {}

    /** Answer with this status, content type, and body. */
    record Respond(int status, String contentType, String body) implements Action {}

    /** Read the request and never answer it. */
    record Hang() implements Action {}

    /** Read the request headers, then reset the connection (TCP RST) without reading the body. */
    record ResetMidRequest() implements Action {}

    private final ServerSocket serverSocket;
    private final BiFunction<Integer, String, Action> script;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    /**
     * @param script decides the answer from the 1-based request number and the request body. It is called twice per
     *               request: first with a null body once the headers arrive (return {@link ResetMidRequest} to reset
     *               there or {@link ReadBody} to go on), then with the body.
     */
    ScriptedHttpServer(BiFunction<Integer, String, Action> script) throws IOException {
        this.script = script;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::acceptLoop, "scripted-http-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String uri() {
        return "http://localhost:" + serverSocket.getLocalPort();
    }

    /** Bodies of every request seen, in arrival order; empty for requests reset before the body was read. */
    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                var socket = serverSocket.accept();
                sockets.add(socket);
                var handler = new Thread(() -> serve(socket), "scripted-http-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            while (true) {
                var headers = readHeaders(in);
                if (headers == null) {
                    return;
                }
                int requestNumber;
                Action action;
                synchronized (this) {
                    requestNumber = requestBodies.size() + 1;
                    action = script.apply(requestNumber, null);
                    if (action instanceof ResetMidRequest) {
                        requestBodies.add("");
                    }
                }
                if (action instanceof ResetMidRequest) {
                    socket.setSoLinger(true, 0);
                    return;
                }
                var body = readBody(in, headers);
                synchronized (this) {
                    requestBodies.add(body);
                    action = script.apply(requestNumber, body);
                }
                switch (action) {
                    case Respond respond -> write(out, respond);
                    case ReadBody readBody -> throw new IllegalStateException("The body was already read");
                    case Hang hang -> {
                        // Hold the connection open until the client gives up and closes it.
                        while (in.read() >= 0) {
                            // discard
                        }
                        return;
                    }
                    case ResetMidRequest reset -> {
                        socket.setSoLinger(true, 0);
                        return;
                    }
                }
            }
        } catch (IOException e) {
            // The client closed the connection.
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return buffer.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static String readBody(InputStream in, String headers) throws IOException {
        var lower = headers.toLowerCase(Locale.ROOT);
        if (lower.contains("transfer-encoding: chunked")) {
            var body = new ByteArrayOutputStream();
            while (true) {
                int size = Integer.parseInt(readLine(in).trim(), 16);
                if (size == 0) {
                    readLine(in);
                    return body.toString(StandardCharsets.UTF_8);
                }
                body.write(in.readNBytes(size));
                readLine(in);
            }
        }
        for (var line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                int length = Integer.parseInt(line.substring("content-length:".length()).trim());
                return new String(in.readNBytes(length), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }

    private static void write(OutputStream out, Respond respond) throws IOException {
        var body = respond.body().getBytes(StandardCharsets.UTF_8);
        var head = "HTTP/1.1 " + respond.status() + " Scripted\r\n"
            + "Content-Type: " + respond.contentType() + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (var socket : sockets) {
            socket.close();
        }
    }
}
