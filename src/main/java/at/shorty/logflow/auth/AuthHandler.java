package at.shorty.logflow.auth;

import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.util.DataCache;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class AuthHandler {

    private final String localAuthToken;
    private final HikariConnectionPool connectionPool;
    @Getter
    private final DataCache<String, TokenData> tokenDataCache = new DataCache<>() {
        @Override
        public TokenData getData(String reference) {
            if (reference.equals(localAuthToken)) {
                return new TokenData(UUID.fromString(localAuthToken), localAuthToken, new String[]{"*"}, new String[]{"*"});
            }
            if (connectionPool == null)
                return null;
            try (var connection = connectionPool.getConnection()) {
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM tokens WHERE token = ?");
                statement.setString(1, reference);
                var resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    return new TokenData(UUID.fromString(resultSet.getString("uuid")), resultSet.getString("token"), resultSet.getString("read_contexts").split(","), resultSet.getString("push_contexts").split(","));
                }
            } catch (SQLException e) {
                log.error("Failed to get token data - connection is null", e);
            }
            return null;
        }
    };

    public boolean authenticate(String token) {
        if (token.length() > 1024) {
            return false;
        }
        TokenData tokenData = tokenDataCache.get(token, 1500);
        return localAuthToken.equals(token) || tokenData != null;
    }

}
