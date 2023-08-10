package at.shorty.logflow.ingest;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.auth.TokenData;
import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.data.LogAction;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.packet.impl.InPacketAuth;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.packet.impl.OutPacketLogResponse;
import at.shorty.logflow.ingest.source.IngestSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.http.Context;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.InetAddress;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Base64;

@Slf4j
@RequiredArgsConstructor
public class IngestHandler {

    private final PacketHandler packetHandler;
    private final AuthHandler authHandler;
    private final HikariConnectionPool connectionPool;

    public void wsIngest(WsConfig ws, WsContext ctx) throws IOException {
        var token = ctx.header("Authorization");
        if (token == null) {
            log.warn("Failed to authenticate websocket connection from {} (no auth token provided)", ctx.host());
            ctx.session.close();
            return;
        }
        if (!authHandler.authenticate(token)) {
            var outPacketAuthResponse = new OutPacketAuthResponse();
            outPacketAuthResponse.setSuccess(false);
            var json = packetHandler.getObjectMapper().writeValueAsString(outPacketAuthResponse);
            ctx.send(json);
            ctx.session.close();
            log.warn("Failed to authenticate websocket connection from {} (invalid auth token)", ctx.host());
            return;
        }
        var address = InetAddress.getByName(ctx.host());
        var pipedOutputStream = new PipedOutputStream();
        var pipedInputStream = new PipedInputStream(pipedOutputStream);
        var outputStream = new OutputStream() {
            @Override
            public void write(int b) {
                ctx.send(b);
            }

            @Override
            public void write(byte @NotNull [] b) {
                ctx.send(new String(b));
            }
        };
        ws.onMessage(msgCtx -> {
            try {
                pipedOutputStream.write((msgCtx.message() + "\n").getBytes());
            } catch (IOException e) {
                log.warn("Failed to write to piped output stream", e);
            }
        });
        log.info("Successfully authenticated websocket connection from {}", ctx.host());
        var ingestSource = new IngestSource(address, pipedInputStream, outputStream, (v) -> {
            try {
                pipedInputStream.close();
                pipedOutputStream.close();
                ctx.closeSession();
            } catch (IOException e) {
                log.warn("Failed to close piped streams and wsContext", e);
            }
            return null;
        }, IngestSource.Type.WEBSOCKET);
        ingest(ingestSource, true);
    }

    public void httpIngest(Context handler) {
        String authToken = handler.req().getHeader("Authorization");
        if (authToken == null) {
            log.warn("Failed to authenticate HTTP request from {} (no auth token provided)", handler.req().getRemoteAddr());
            handler.status(401);
            return;
        }
        if (!authHandler.authenticate(authToken)) {
            log.warn("Failed to authenticate HTTP request from {} (invalid auth token)", handler.req().getRemoteAddr());
            handler.status(401);
            return;
        }
        var logAction = new LogAction(connectionPool);
        try {
            var inPacketLog = packetHandler.getObjectMapper().readValue(handler.body(), InPacketLog.class);
            inPacketLog.setSourceIp(handler.ip());
            if (inPacketLog.getContent() != null) {
                inPacketLog.setContent(new String(Base64.getDecoder().decode(inPacketLog.getContent())));
            }
            if (inPacketLog.getTags() == null) {
                inPacketLog.setTags(new String[0]);
            }
            var outPacketLogResponse = validatePacketAndReturnResponse(inPacketLog);
            var json = packetHandler.getObjectMapper().writeValueAsString(outPacketLogResponse);
            handler.result(json);
            if (!outPacketLogResponse.isSuccess()) {
                log.warn("Failed to log from {} -> Reason: {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), outPacketLogResponse.getMessage());
                return;
            }
            TokenData tokenData = authHandler.getTokenDataCache().get(authToken, 5000);
            if (!tokenData.isAllowedToPush(inPacketLog.getContext())) {
                log.warn("Failed to log from {} -> Reason: No permissions - Context not allowed", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp() + " (token affected: " + tokenData.uuid() + ")");
                return;
            }
            logAction.log(inPacketLog);
            log.debug("Received log from {} -> {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), inPacketLog.getContent());
        } catch (JsonProcessingException e) {
            log.warn("Invalid packet received ({}) -> {}", handler.ip(), handler.body());
        } catch (SQLException e) {
            log.warn("Failed to save log from {} -> {}", handler.ip(), e.getMessage());
        }
    }

    public void ingest(IngestSource ingestSource, boolean preAuth) {
        new Thread(() -> {
            String authToken = null;
            var logAction = new LogAction(connectionPool);
            try (var bufferedReader = new BufferedReader(new InputStreamReader(ingestSource.inputStream()))) {
                String line;
                var authenticated = preAuth;
                while ((line = bufferedReader.readLine()) != null) {
                    if (!authenticated) {
                        try {
                            InPacketAuth inPacketAuth = packetHandler.handleJsonInput(line, InPacketAuth.class);
                            authenticated = authHandler.authenticate(inPacketAuth.getToken());
                            authToken = inPacketAuth.getToken();
                            var outPacketAuthResponse = new OutPacketAuthResponse();
                            outPacketAuthResponse.setSuccess(authenticated);
                            var json = packetHandler.getObjectMapper().writeValueAsString(outPacketAuthResponse);
                            ingestSource.outputStream().write((json + "\n").getBytes());
                            if (authenticated) {
                                log.info("Successfully authenticated {} connection from {}", ingestSource.type().friendlyName.toLowerCase(), ingestSource.address().getHostAddress());
                            } else {
                                log.warn("Failed to authenticate {} connection from {}", ingestSource.type().friendlyName.toLowerCase(), ingestSource.address().getHostAddress());
                                ingestSource.close().apply(null);
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("Invalid packet received at authentication stage ({}) -> {}", ingestSource.address().getHostAddress(), line);
                            ingestSource.close().apply(null);
                        }
                    } else {
                        try {
                            if (authToken == null) {
                                log.warn("Failed to log from {} -> Reason: No auth token", ingestSource.address().getHostAddress());
                                continue;
                            }
                            InPacketLog inPacketLog = packetHandler.handleJsonInput(line, InPacketLog.class);
                            inPacketLog.setSourceIp(ingestSource.address().getHostAddress());
                            if (inPacketLog.getContent() != null) {
                                inPacketLog.setContent(new String(Base64.getDecoder().decode(inPacketLog.getContent())));
                            }
                            if (inPacketLog.getTags() == null) {
                                inPacketLog.setTags(new String[0]);
                            }
                            var outPacketLogResponse = validatePacketAndReturnResponse(inPacketLog);
                            var json = packetHandler.getObjectMapper().writeValueAsString(outPacketLogResponse);
                            ingestSource.outputStream().write((json + "\n").getBytes());
                            if (!outPacketLogResponse.isSuccess()) {
                                log.warn("Failed to log from {} -> Reason: {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), outPacketLogResponse.getMessage());
                                continue;
                            }
                            TokenData tokenData = authHandler.getTokenDataCache().get(authToken, 5000);
                            if (!tokenData.isAllowedToPush(inPacketLog.getContext())) {
                                log.warn("Failed to log from {} -> Reason: No permissions - Context not allowed", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp() + " (token affected: " + tokenData.uuid() + ")");
                                return;
                            }
                            logAction.log(inPacketLog);
                            log.debug("Received log from {} -> {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), inPacketLog.getContent());
                        } catch (JsonProcessingException e) {
                            log.warn("Invalid packet received ({}) -> {}", ingestSource.address().getHostAddress(), line);
                        } catch (SQLException e) {
                            log.warn("Failed to save log from {} -> {}", ingestSource.address().getHostAddress(), e.getMessage());
                        }
                    }
                }
            } catch (SocketException e) {
                log.info("{} connection closed by remote host ({})", ingestSource.type().friendlyName, ingestSource.address().getHostAddress());
            } catch (IOException e) {
                log.warn("Unexpected exception while reading", e);
            }
            log.info("{} connection closed ({})", ingestSource.type().friendlyName, ingestSource.address().getHostAddress());
        }).start();
    }

    @NotNull
    public OutPacketLogResponse validatePacketAndReturnResponse(InPacketLog inPacketLog) {
        var outPacketLogResponse = new OutPacketLogResponse();
        for (String tag : inPacketLog.getTags()) {
            if (!tag.matches("^[a-zA-Z0-9_]*$")) {
                outPacketLogResponse.setSuccess(false);
                outPacketLogResponse.setMessage("Tag " + tag + " contains invalid characters (only a-z, A-Z, 0-9 and _ are allowed)");
                return outPacketLogResponse;
            }
        }
        if (String.join(",", inPacketLog.getTags()).length() > 4096) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Tags are too long (max. 4096 characters in total)");
            return outPacketLogResponse;
        }
        if (inPacketLog.getSource() == null || inPacketLog.getSource().isEmpty()) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Source is null or empty");
        } else if (inPacketLog.getTimestamp() == null) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Timestamp is null");
        } else if (inPacketLog.getLevel() == null) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Level is null");
        } else if (inPacketLog.getContext() == null || inPacketLog.getContext().isEmpty()) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Context is null or empty");
        } else if (inPacketLog.getSource().length() > 255) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Source is too long (max. 255 characters)");
        } else if (inPacketLog.getContext().length() > 255) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Context is too long (max. 255 characters)");
        } else if (!inPacketLog.getContext().matches("^[a-zA-Z0-9_]*$")) {
            outPacketLogResponse.setSuccess(false);
            outPacketLogResponse.setMessage("Context contains invalid characters (only a-z, A-Z, 0-9 and _ are allowed)");
        } else {
            outPacketLogResponse.setSuccess(true);
            outPacketLogResponse.setMessage("OK");
        }
        return outPacketLogResponse;
    }

}
