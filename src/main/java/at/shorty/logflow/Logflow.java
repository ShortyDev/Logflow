package at.shorty.logflow;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.IngestHandler;
import at.shorty.logflow.ingest.data.LogAction;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.source.IngestSource;
import at.shorty.logflow.util.LogflowArgsParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.Javalin;
import io.javalin.community.ssl.SSLPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public class Logflow {

    public void init(String[] args) {
        CommandLine commandLine = LogflowArgsParser.parse(args);
        var noWebServer = commandLine.hasOption("noWebServer");
        var noWsIngest = commandLine.hasOption("noWsIngest");
        var noHTTPIngest = commandLine.hasOption("noHTTPIngest");
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

        var authHandler = new AuthHandler(localAuthToken, connectionPool);
        var packetHandler = new PacketHandler();

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
            var javalinPort = System.getenv("LOGFLOW_WEB_PORT");
            if (javalinPort == null) {
                javalinPort = isSSL ? "2096" : "2086";
            }
            log.info("Starting web server on port " + javalinPort + (isSSL ? " (SSL)" : "") + "...");
            startWebServer(sslPlugin, authHandler, packetHandler, connectionPool, Integer.parseInt(javalinPort), noWsIngest, noHTTPIngest);
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
                var logAction = new LogAction(connectionPool);
                if (finalIsSSL) {
                    var sslServerSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                    try (var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("SSL socket server started");
                        while (true) {
                            var socket = sslServerSocket.accept();
                            new IngestHandler(IngestSource.from(socket), packetHandler, authHandler, logAction, false);
                        }
                    } catch (IOException e) {
                        log.error("Failed to start SSL socket server", e);
                    }
                } else {
                    try (var serverSocket = new ServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("Socket server started");
                        while (true) {
                            var socket = serverSocket.accept();
                            new IngestHandler(IngestSource.from(socket), packetHandler, authHandler, logAction, false);
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

    private void startWebServer(SSLPlugin sslPlugin, AuthHandler authHandler, PacketHandler packetHandler, HikariConnectionPool connectionPool, int javalinPort, boolean noWsIngest, boolean noHttpIngest) {
        Javalin app = Javalin.create(javalinConfig -> {
                    if (sslPlugin != null) {
                        javalinConfig.plugins.register(sslPlugin);
                    }
                })
                .start(javalinPort);
        if (!noWsIngest) {
            app.ws("/ws", ws -> ws.onConnect(ctx -> {
                var token = ctx.header("Authorization");
                if (token == null) {
                    log.warn("Failed to authenticate websocket connection from {} (no auth token provided)", ctx.host());
                    ctx.session.close();
                    return;
                }
                if (!authHandler.authenticate(token)) {
                    var outPacketAuthResponse = new OutPacketAuthResponse();
                    outPacketAuthResponse.setSuccess(false);
                    var json = packetHandler.getObjectMapper().writeValueAsString(outPacketAuthResponse);
                    ctx.send(json);
                    ctx.session.close();
                    log.warn("Failed to authenticate websocket connection from {} (invalid auth token)", ctx.host());
                    return;
                }
                var address = InetAddress.getByName(ctx.host());
                var pipedOutputStream = new PipedOutputStream();
                var pipedInputStream = new PipedInputStream(pipedOutputStream);
                var outputStream = new OutputStream() {
                    @Override
                    public void write(int b) {
                        ctx.send(b);
                    }

                    @Override
                    public void write(byte @NotNull [] b) {
                        ctx.send(new String(b));
                    }
                };
                ws.onMessage(msgCtx -> {
                    try {
                        pipedOutputStream.write((msgCtx.message() + "\n").getBytes());
                    } catch (IOException e) {
                        log.warn("Failed to write to piped output stream", e);
                    }
                });
                log.info("Successfully authenticated websocket connection from {}", ctx.host());
                var ingestSource = new IngestSource(address, pipedInputStream, outputStream, (v) -> {
                    try {
                        pipedInputStream.close();
                        pipedOutputStream.close();
                        ctx.closeSession();
                    } catch (IOException e) {
                        log.warn("Failed to close piped streams and wsContext", e);
                    }
                    return null;
                }, IngestSource.Type.WEBSOCKET);
                var logAction = new LogAction(connectionPool);
                new IngestHandler(ingestSource, packetHandler, authHandler, logAction, true);
            }));
        }
        if (!noHttpIngest) {
            app.post("/log", handler -> {
                String authToken = handler.req().getHeader("Authorization");
                if (authToken == null) {
                    log.warn("Failed to authenticate HTTP request from {} (no auth token provided)", handler.req().getRemoteAddr());
                    handler.status(401);
                    return;
                }
                if (!authHandler.authenticate(authToken)) {
                    log.warn("Failed to authenticate HTTP request from {} (invalid auth token)", handler.req().getRemoteAddr());
                    handler.status(401);
                    return;
                }
                var logAction = new LogAction(connectionPool);
                try {
                    var inPacketLog = packetHandler.getObjectMapper().readValue(handler.body(), InPacketLog.class);
                    inPacketLog.setSourceIp(handler.ip());
                    if (inPacketLog.getContent() != null) {
                        inPacketLog.setContent(new String(Base64.getDecoder().decode(inPacketLog.getContent())));
                    }
                    if (inPacketLog.getTags() == null) {
                        inPacketLog.setTags(new String[0]);
                    }
                    var outPacketLogResponse = IngestHandler.createOutPacketLogResponse(inPacketLog);
                    var json = packetHandler.getObjectMapper().writeValueAsString(outPacketLogResponse);
                    handler.result(json);
                    if (!outPacketLogResponse.isSuccess()) {
                        log.warn("Failed to log from {} -> Reason: {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), outPacketLogResponse.getMessage());
                        return;
                    }
                    logAction.log(inPacketLog);
                    log.debug("Received log from {} -> {}", inPacketLog.getSource() + "@" + inPacketLog.getSourceIp(), inPacketLog.getContent());
                } catch (JsonProcessingException e) {
                    log.warn("Invalid packet received ({}) -> {}", handler.ip(), handler.body());
                } catch (SQLException e) {
                    log.warn("Failed to save log from {} -> {}", handler.ip(), e.getMessage());
                }
            });
        }
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
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        log.info("Database setup complete");
    }

}
