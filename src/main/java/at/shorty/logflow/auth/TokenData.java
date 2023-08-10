package at.shorty.logflow.auth;

import java.util.UUID;

public record TokenData(UUID uuid, String token, String[] readContexts, String[] pushContexts) {

    public boolean isAllowedToRead(String context) {
        for (var readContext : readContexts) {
            if (readContext.equals(context)) {
                return true;
            }
        }
        return readContexts.length == 1 && readContexts[0].equals("*");
    }

    public boolean isAllowedToPush(String context) {
        for (var pushContext : pushContexts) {
            if (pushContext.equals(context)) {
                return true;
            }
        }
        return pushContexts.length == 1 && pushContexts[0].equals("*");
    }
}