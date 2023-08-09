package at.shorty.logflow.auth;

import at.shorty.logflow.hikari.HikariConnectionPool;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AuthHandler {

    private final String localAuthToken;
    private final HikariConnectionPool connectionPool;

    public boolean authenticate(String token) {
        return localAuthToken.equals(token);
    }

}
