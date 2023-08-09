package at.shorty.logflow.ingest.packet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

@Getter
public class PacketHandler {

    private final ObjectMapper objectMapper;

    public PacketHandler() {
        objectMapper = new ObjectMapper();
    }

    public <T extends Packet> T handleJsonInput(String json, Class<T> packetClass) throws JsonProcessingException {
        return objectMapper.readValue(json, packetClass);
    }

}
