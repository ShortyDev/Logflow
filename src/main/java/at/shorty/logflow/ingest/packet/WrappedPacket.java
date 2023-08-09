package at.shorty.logflow.ingest.packet;

import lombok.Data;

@Data
public class WrappedPacket {

    private int id;
    private String packet;

}
