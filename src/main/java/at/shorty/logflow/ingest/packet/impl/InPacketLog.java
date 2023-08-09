package at.shorty.logflow.ingest.packet.impl;

import at.shorty.logflow.ingest.packet.Packet;
import at.shorty.logflow.ingest.packet.PacketInfo;
import at.shorty.logflow.log.Level;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@PacketInfo(id = 1)
@Data
@EqualsAndHashCode(callSuper=false)
public class InPacketLog extends Packet {

    private Date timestamp;
    private String source;
    private String sourceIp;
    private String context;
    private String[] tags;
    private String metadata;
    private Level level;
    private String content;

}
