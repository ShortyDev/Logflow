package at.shorty.logflow.ingest.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.function.Function;

@Slf4j
public record IngestSource(InetAddress address, InputStream inputStream, OutputStream outputStream, Function<Void, Void> close, Type type) {

    public static IngestSource from(Socket socket) throws IOException {
        return new IngestSource(socket.getInetAddress(), socket.getInputStream(), socket.getOutputStream(), (v) -> {
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("Failed to close socket", e);
            }
            return null;
        }, Type.SOCKET);
    }

    @RequiredArgsConstructor
    public enum Type {
        SOCKET("Socket"), WEBSOCKET("Websocket");

        public final String friendlyName;

    }

}
