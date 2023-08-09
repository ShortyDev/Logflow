package at.shorty.logflow.ingest.packet.impl;

import at.shorty.logflow.ingest.packet.Packet;
import at.shorty.logflow.ingest.packet.PacketInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@PacketInfo(id = 0)
@Data
@EqualsAndHashCode(callSuper=false)
public class InPacketAuth extends Packet {

    private String token;

}
