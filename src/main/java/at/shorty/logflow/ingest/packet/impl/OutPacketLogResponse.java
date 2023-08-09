package at.shorty.logflow.ingest.packet.impl;

import at.shorty.logflow.ingest.packet.Packet;
import at.shorty.logflow.ingest.packet.PacketInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@PacketInfo(id = 1)
@Data
@EqualsAndHashCode(callSuper=false)
public class OutPacketLogResponse extends Packet {

        private boolean success;
        private String message;

}
