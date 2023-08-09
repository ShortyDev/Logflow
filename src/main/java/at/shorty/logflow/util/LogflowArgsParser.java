package at.shorty.logflow.util;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

public class LogflowArgsParser {

    public static CommandLine parse(String[] args) {
        var parser = new DefaultParser();

        var options = new Options();
        options.addOption("noWebServer", false, "Do not start web server");
        options.addOption("noWsIngest", false, "Do not use web sockets for ingest");
        options.addOption("noHTTPIngest", false, "Do not use HTTP for ingest");
        options.addOption("noSocketIngest", false, "Do not start socket server for ingest");
        options.addOption("webUseSSL", false, "Use SSL for web server");
        options.addOption("socketUseSSL", false, "Use SSL for socket server");

        try {
            return parser.parse(options, args);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to parse arguments", exception);
        }
    }

}