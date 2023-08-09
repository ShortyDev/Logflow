package at.shorty.logflow.ingest.data;

import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LogAction {

    private final HikariConnectionPool connectionPool;

    public void log(InPacketLog inPacketLog) {
    }
}
