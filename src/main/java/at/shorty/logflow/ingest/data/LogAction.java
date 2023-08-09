package at.shorty.logflow.ingest.data;

import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;

public class LogAction {

    private final HikariConnectionPool connectionPool;
    private Connection connection;

    public LogAction(HikariConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
        this.connection = connectionPool.getConnection();
    }

    public void log(InPacketLog inPacketLog) throws SQLException {
        if (connection == null || !connection.isValid(1)) {
            connection = connectionPool.getConnection();
        }
        try (var statement = connection.prepareStatement("INSERT INTO logs (time_stamp, source, source_ip, context, tags, metadata, level, content) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setTimestamp(1, new Timestamp(inPacketLog.getTimestamp().getTime()));
            statement.setString(2, inPacketLog.getSource());
            statement.setString(3, inPacketLog.getSourceIp());
            statement.setString(4, inPacketLog.getContext());
            statement.setString(5, String.join(",", inPacketLog.getTags()));
            statement.setString(6, inPacketLog.getMetadata());
            statement.setString(7, inPacketLog.getLevel().name());
            statement.setString(8, inPacketLog.getContent());
            statement.execute();
        }
    }
}
