package at.shorty.logflow.ingest.packet.impl;

import at.shorty.logflow.ingest.packet.Packet;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class OutPacketAuthResponse extends Packet {

        private boolean success;

}
