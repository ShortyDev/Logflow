package at.shorty.logflow.ingest;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.ingest.data.LogAction;
import at.shorty.logflow.ingest.packet.Packet;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.packet.PacketInfo;
import at.shorty.logflow.ingest.packet.WrappedPacket;
import at.shorty.logflow.ingest.packet.impl.InPacketAuth;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.source.IngestSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.util.Base64;

@Slf4j
public class IngestHandler {

    public IngestHandler(IngestSource ingestSource, PacketHandler packetHandler, AuthHandler authHandler, LogAction logAction, boolean preAuth) {
        new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(ingestSource.inputStream()))) {
                String line;
                boolean authenticated = preAuth;
                while ((line = bufferedReader.readLine()) != null) {
                    if (!authenticated) {
                        try {
                            Packet packet = packetHandler.handleJsonInput(line);
                            if (packet instanceof InPacketAuth inPacketAuth) {
                                authenticated = authHandler.authenticate(inPacketAuth.getToken());
                                OutPacketAuthResponse outPacketAuthResponse = new OutPacketAuthResponse();
                                outPacketAuthResponse.setSuccess(authenticated);
                                WrappedPacket wrappedPacket = packetHandler.handlePacket(outPacketAuthResponse);
                                String json = packetHandler.getObjectMapper().writeValueAsString(wrappedPacket);
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
                            Packet packet = packetHandler.handleJsonInput(line);
                            if (packet instanceof InPacketLog inPacketLog) {
                                inPacketLog.setSourceIp(ingestSource.address().getHostAddress());
                                System.out.println(inPacketLog.getContent());
                                if (inPacketLog.getContent() != null) {
                                    inPacketLog.setContent(new String(Base64.getDecoder().decode(inPacketLog.getContent())));
                                }
                                System.out.println(inPacketLog.getContent());
                                logAction.log(inPacketLog);
                                log.debug("Received log from {} -> {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), inPacketLog.getContent());
                            } else {
                                log.warn("Invalid packet received ({}) -> id {}", ingestSource.address().getHostAddress(), packet.getClass().getAnnotation(PacketInfo.class).id());
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("Invalid packet received ({}) -> {}", ingestSource.address().getHostAddress(), line);
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

}
