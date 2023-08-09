package at.shorty.logflow.ingest;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.ingest.data.LogAction;
import at.shorty.logflow.ingest.packet.Packet;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.packet.PacketInfo;
import at.shorty.logflow.ingest.packet.impl.InPacketAuth;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.packet.impl.OutPacketLogResponse;
import at.shorty.logflow.ingest.source.IngestSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Base64;

@Slf4j
public class IngestHandler {

    public IngestHandler(IngestSource ingestSource, PacketHandler packetHandler, AuthHandler authHandler, LogAction logAction, boolean preAuth) {
        new Thread(() -> {
            try (var bufferedReader = new BufferedReader(new InputStreamReader(ingestSource.inputStream()))) {
                String line;
                var authenticated = preAuth;
                while ((line = bufferedReader.readLine()) != null) {
                    if (!authenticated) {
                        try {
                            Packet packet = packetHandler.handleJsonInput(line);
                            if (packet instanceof InPacketAuth inPacketAuth) {
                                authenticated = authHandler.authenticate(inPacketAuth.getToken());
                                var outPacketAuthResponse = new OutPacketAuthResponse();
                                outPacketAuthResponse.setSuccess(authenticated);
                                var wrappedPacket = packetHandler.handlePacket(outPacketAuthResponse);
                                var json = packetHandler.getObjectMapper().writeValueAsString(wrappedPacket);
                                ingestSource.outputStream().write((json + "\n").getBytes());
                                if (authenticated) {
                                    log.info("Successfully authenticated {} connection from {}", ingestSource.type().friendlyName.toLowerCase(), ingestSource.address().getHostAddress());
                                } else {
                                    log.warn("Failed to authenticate {} connection from {}", ingestSource.type().friendlyName.toLowerCase(), ingestSource.address().getHostAddress());
                                }
                            } else {
                                log.warn("Invalid packet received at authentication stage ({}) -> id {}", ingestSource.address().getHostAddress(), packet.getClass().getAnnotation(PacketInfo.class).id());
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("Invalid packet received at authentication stage ({}) -> {}", ingestSource.address().getHostAddress(), line);
                        }
                    } else {
                        try {
                            var packet = packetHandler.handleJsonInput(line);
                            if (packet instanceof InPacketLog inPacketLog) {
                                inPacketLog.setSourceIp(ingestSource.address().getHostAddress());
                                if (inPacketLog.getContent() != null) {
                                    inPacketLog.setContent(new String(Base64.getDecoder().decode(inPacketLog.getContent())));
                                }
                                if (inPacketLog.getTags() == null) {
                                    inPacketLog.setTags(new String[0]);
                                }
                                var outPacketLogResponse = createOutPacketLogResponse(inPacketLog);
                                var wrappedPacket = packetHandler.handlePacket(outPacketLogResponse);
                                var json = packetHandler.getObjectMapper().writeValueAsString(wrappedPacket);
                                ingestSource.outputStream().write((json + "\n").getBytes());
                                if (!outPacketLogResponse.isSuccess()) {
                                    log.warn("Failed to log from {} -> Reason: {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), outPacketLogResponse.getMessage());
                                    continue;
                                }
                                logAction.log(inPacketLog);
                                log.debug("Received log from {} -> {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), inPacketLog.getContent());
                            } else {
                                log.warn("Invalid packet received ({}) -> id {}", ingestSource.address().getHostAddress(), packet.getClass().getAnnotation(PacketInfo.class).id());
                            }
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
    private static OutPacketLogResponse createOutPacketLogResponse(InPacketLog inPacketLog) {
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
        }  else {
            outPacketLogResponse.setSuccess(true);
            outPacketLogResponse.setMessage("OK");
        }
        return outPacketLogResponse;
    }

}
