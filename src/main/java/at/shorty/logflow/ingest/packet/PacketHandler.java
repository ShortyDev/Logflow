package at.shorty.logflow.ingest.packet;

import at.shorty.logflow.ingest.packet.impl.InPacketAuth;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.packet.impl.OutPacketLogResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class PacketHandler {

    private final List<Packet> packets;
    @Getter
    private final ObjectMapper objectMapper;

    public PacketHandler() {
        packets = new ArrayList<>();
        packets.add(new InPacketAuth());
        packets.add(new InPacketLog());
        packets.add(new OutPacketAuthResponse());
        packets.add(new OutPacketLogResponse());
        objectMapper = new ObjectMapper();
    }

    public Packet handleJsonInput(String json) throws JsonProcessingException {
        var wrappedPacket = objectMapper.readValue(json, WrappedPacket.class);
        return objectMapper.readValue(wrappedPacket.getPacket(), getPacket(wrappedPacket.getId()));
    }

    public WrappedPacket handlePacket(Packet packet) throws JsonProcessingException {
        var wrappedPacket = new WrappedPacket();
        wrappedPacket.setId(packet.getClass().getAnnotation(PacketInfo.class).id());
        wrappedPacket.setPacket(objectMapper.writeValueAsString(packet));
        return wrappedPacket;
    }

    private Class<? extends Packet> getPacket(int id) {
        for (var packet : packets) {
            if (packet.getClass().getAnnotation(PacketInfo.class).id() == id) {
                return packet.getClass();
            }
        }
        return null;
    }

}
