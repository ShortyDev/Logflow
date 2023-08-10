package at.shorty.logflow;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.IngestHandler;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.source.IngestSource;
import at.shorty.logflow.util.LogflowArgsParser;
import io.javalin.Javalin;
import io.javalin.community.ssl.SSLPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public class Logflow {

    public void init(String[] args) {
        CommandLine commandLine = LogflowArgsParser.parse(args);
        var noWebServer = commandLine.hasOption("noWebServer");
        var noWsIngest = commandLine.hasOption("noWsIngest");
        var noHttpIngest = commandLine.hasOption("noHTTPIngest");
        var noSocketIngest = commandLine.hasOption("noSocketIngest");
        var webUseSSL = commandLine.hasOption("webUseSSL");
        var socketUseSSL = commandLine.hasOption("socketUseSSL");
        log.info("Starting Logflow...");

        var localAuthToken = System.getenv("LOGFLOW_LOCAL_AUTH_TOKEN");
        var jdbcUrl = System.getenv("LOGFLOW_HIKARI_JDBC_URL");
        var username = System.getenv("LOGFLOW_HIKARI_USERNAME");
        var password = System.getenv("LOGFLOW_HIKARI_PASSWORD");
        Optional<String> poolSize = Optional.ofNullable(System.getenv("LOGFLOW_HIKARI_POOL_SIZE"));
        var poolSizeInt = poolSize.map(Integer::parseInt).orElse(10);
        if (localAuthToken == null) {
            localAuthToken = UUID.randomUUID().toString();
            log.warn("No local auth token provided, using random token: {}", localAuthToken);
        }
        if (jdbcUrl == null || username == null || password == null) {
            throw new RuntimeException("Failed to start Logflow: Missing environment variables, required: LOGFLOW_HIKARI_JDBC_URL, LOGFLOW_HIKARI_USERNAME, LOGFLOW_HIKARI_PASSWORD");
        }
        log.info("Initializing Hikari pool...");
        var connectionPool = new HikariConnectionPool(jdbcUrl, username, password, poolSizeInt);
        log.info("Hikari pool initialized");

        var packetHandler = new PacketHandler();
        var authHandler = new AuthHandler(localAuthToken, connectionPool);
        var ingestHandler = new IngestHandler(packetHandler, authHandler, connectionPool);

        var sslKeystorePath = System.getProperty("javax.net.ssl.keyStore");
        var sslKeystorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
        var sslPemCert = System.getProperty("logflow.ssl.pem.cert");
        var sslPemPrivateKey = System.getProperty("logflow.ssl.pem.privateKey");
        var providedSsl = sslKeystorePath != null && sslKeystorePassword != null || sslPemCert != null && sslPemPrivateKey != null;

        if (!noWebServer) {
            log.info("Initializing web server...");
            var isSSL = false;
            SSLPlugin sslPlugin;
            if (!providedSsl || !webUseSSL) {
                sslPlugin = null;
                var message = !webUseSSL ? "Start Logflow with -webUseSSL to enable" : "Make sure to provide javax.net.ssl.keyStore and javax.net.ssl.keyStorePassword or at.shorty.logflow.ssl.pem.cert and at.shorty.logflow.ssl.pem.privateKey system properties";
                log.warn("Not using SSL for web server ({})", message);
            } else {
                log.info("Using SSL for web server");
                isSSL = true;
                sslPlugin = new SSLPlugin(conf -> {
                    if (sslPemCert == null || sslPemPrivateKey == null) {
                        conf.keystoreFromPath(sslKeystorePath, sslKeystorePassword);
                        log.info("Using keystore for SSL (Could not find PEM cert or private key)");
                    } else {
                        conf.pemFromPath(sslPemCert, sslPemPrivateKey);
                        log.info("Using PEM cert for SSL");
                    }
                    conf.securePort = 2096;
                    conf.insecure = false;
                });
            }
            var webPort = System.getenv("LOGFLOW_WEB_PORT");
            if (webPort == null) {
                webPort = isSSL ? "2096" : "2086";
            }
            log.info("Starting web server on port " + webPort + (isSSL ? " (SSL)" : "") + "...");
            Javalin app = Javalin.create(javalinConfig -> {
                        if (sslPlugin != null) {
                            javalinConfig.plugins.register(sslPlugin);
                        }
                    })
                    .start(Integer.parseInt(webPort));
            if (!noWsIngest) {
                app.ws("/ws", ws -> ws.onConnect(ctx -> ingestHandler.wsIngest(ws, ctx)));
            }
            if (!noHttpIngest) {
                app.post("/log", ingestHandler::httpIngest);
            }
        }

        if (!noSocketIngest) {
            new Thread(() -> {
                log.info("Initializing socket server...");
                var isSSL = false;
                if (sslKeystorePath == null || sslKeystorePassword == null || !socketUseSSL) {
                    var message = !socketUseSSL ? "Start Logflow with -socketUseSSL to enable" : "Make sure to provide javax.net.ssl.keyStore and javax.net.ssl.keyStorePassword system properties";
                    log.warn("Not using SSL for socket server ({})", message);
                } else {
                    log.info("Using SSL for socket server");
                    isSSL = true;
                }
                var socketPort = System.getenv("LOGFLOW_SOCKET_PORT");
                if (socketPort == null) {
                    socketPort = isSSL ? "7210" : "7200";
                }
                log.info("Starting socket server on port " + socketPort + (isSSL ? " (SSL)" : "") + "...");
                var finalIsSSL = isSSL;
                var finalSocketPort = socketPort;
                if (finalIsSSL) {
                    var sslServerSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                    try (var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("SSL socket server started");
                        while (true) {
                            var socket = sslServerSocket.accept();
                            ingestHandler.ingest(IngestSource.from(socket), false);
                        }
                    } catch (IOException e) {
                        log.error("Failed to start SSL socket server", e);
                    }
                } else {
                    try (var serverSocket = new ServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("Socket server started");
                        while (true) {
                            var socket = serverSocket.accept();
                            ingestHandler.ingest(IngestSource.from(socket), false);
                        }
                    } catch (IOException e) {
                        log.error("Failed to start socket server", e);
                    }
                }
            }, "Logflow Socket Server").start();
        }

        log.info("Adding shutdown hook...");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Logflow...");
            connectionPool.close();
            log.info("Logflow shutdown");
        }));
        log.info("Logflow started");
        setupDatabase(connectionPool);
    }

    private void setupDatabase(HikariConnectionPool connectionPool) {
        log.info("Setting up database...");
        try (var connection = connectionPool.getConnection()) {
            log.info("Creating tables...");
            try (var statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS logs (" +
                    "id INT NOT NULL AUTO_INCREMENT, " +
                    "time_stamp TIMESTAMP NOT NULL, " +
                    "source VARCHAR(255) NOT NULL, " +
                    "source_ip VARCHAR(46) NOT NULL, " +
                    "context VARCHAR(255) NOT NULL, " +
                    "tags VARCHAR(4096) NOT NULL, " +
                    "metadata VARCHAR(255), " +
                    "level VARCHAR(15) NOT NULL, " +
                    "content TEXT, " +
                    "PRIMARY KEY (id))")) {
                statement.execute();
            }
            try (var statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS users (" +
                    "id INT NOT NULL AUTO_INCREMENT, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "password VARCHAR(255) NOT NULL, " +
                    "permissions INT NOT NULL, " +
                    "read_contexts TEXT NOT NULL, " +
                    "push_contexts TEXT NOT NULL, " +
                    "deactivated BOOLEAN NOT NULL, " +
                    "PRIMARY KEY (id))")) {
                statement.execute();
            }
            try (var statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS tokens (" +
                    "id INT NOT NULL AUTO_INCREMENT, " +
                    "user_id INT NOT NULL, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "token VARCHAR(1024) NOT NULL, " +
                    "read_contexts TEXT NOT NULL, " +
                    "push_contexts TEXT NOT NULL, " +
                    "PRIMARY KEY (id))")) {
                statement.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        log.info("Database setup complete");
    }

}
