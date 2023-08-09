package at.shorty.logflow;

import at.shorty.logflow.auth.AuthHandler;
import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.ingest.IngestHandler;
import at.shorty.logflow.ingest.data.LogAction;
import at.shorty.logflow.ingest.packet.PacketHandler;
import at.shorty.logflow.ingest.packet.WrappedPacket;
import at.shorty.logflow.ingest.packet.impl.OutPacketAuthResponse;
import at.shorty.logflow.ingest.source.IngestSource;
import at.shorty.logflow.util.LogflowArgsParser;
import io.javalin.Javalin;
import io.javalin.community.ssl.SSLPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;

@Slf4j
public class Logflow {

    public void init(String[] args) {
        CommandLine commandLine = LogflowArgsParser.parse(args);
        var testHikariConnection = commandLine.hasOption("testHikariConnection");
        var noWebServer = commandLine.hasOption("noWebServer");
        var noSocketServer = commandLine.hasOption("noSocketServer");
        var webUseSSL = commandLine.hasOption("webUseSSL");
        var socketUseSSL = commandLine.hasOption("socketUseSSL");
        log.info("Starting Logflow...");

        var localAuthToken = System.getenv("LOGFLOW_LOCAL_AUTH_TOKEN");
        var jdbcUrl = System.getenv("LOGFLOW_HIKARI_JDBC_URL");
        var username = System.getenv("LOGFLOW_HIKARI_USERNAME");
        var password = System.getenv("LOGFLOW_HIKARI_PASSWORD");
        if (localAuthToken == null) {
            localAuthToken = "";
            log.warn("No local auth token provided (env: LOGFLOW_LOCAL_AUTH_TOKEN)");
        }
        if (jdbcUrl == null || username == null || password == null) {
            throw new RuntimeException("Failed to start Logflow: Missing environment variables, required: LOGFLOW_HIKARI_JDBC_URL, LOGFLOW_HIKARI_USERNAME, LOGFLOW_HIKARI_PASSWORD");
        }
        log.info("Initializing Hikari pool...");
        var connectionPool = new HikariConnectionPool(jdbcUrl, username, password);
        log.info("Hikari pool initialized");
        if (testHikariConnection) {
            log.info("Testing Hikari connection... (as requested by command line argument)");
            try (var connection = connectionPool.getConnection()) {
                if (connection == null) {
                    throw new RuntimeException("Failed to get connection from pool");
                }
            } catch (SQLException exception) {
                throw new RuntimeException("Failed to get connection from pool", exception);
            }
            log.info("Hikari connection test successful");
        }

        var authHandler = new AuthHandler(localAuthToken, connectionPool);
        var logAction = new LogAction(connectionPool);
        var packetHandler = new PacketHandler();

        var sslKeystorePath = System.getProperty("javax.net.ssl.keyStore");
        var sslKeystorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
        var sslPemCert = System.getProperty("at.shorty.logflow.ssl.pem.cert");
        var sslPemPrivateKey = System.getProperty("at.shorty.logflow.ssl.pem.privateKey");
        boolean providedSsl = sslKeystorePath != null && sslKeystorePassword != null || sslPemCert != null && sslPemPrivateKey != null;
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
            Javalin.create(javalinConfig -> {
                        if (sslPlugin != null) {
                            javalinConfig.plugins.register(sslPlugin);
                        }
                    })
                    .ws("/ws", ws -> {
                        ws.onConnect(ctx -> {
                            var token = ctx.header("Authorization");
                            if (token == null) {
                                log.warn("Failed to authenticate websocket connection from {} (no auth token provided)", ctx.host());
                                ctx.session.close();
                                return;
                            }
                            if (!authHandler.authenticate(token)) {
                                OutPacketAuthResponse outPacketAuthResponse = new OutPacketAuthResponse();
                                outPacketAuthResponse.setSuccess(false);
                                WrappedPacket wrappedPacket = packetHandler.handlePacket(outPacketAuthResponse);
                                String json = packetHandler.getObjectMapper().writeValueAsString(wrappedPacket);
                                ctx.send(json);
                                ctx.session.close();
                                log.warn("Failed to authenticate websocket connection from {} (invalid auth token)", ctx.host());
                                return;
                            }
                            InetAddress address = InetAddress.getByName(ctx.host());
                            PipedOutputStream pipedOutputStream = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);
                            OutputStream outputStream = new OutputStream() {
                                @Override
                                public void write(int b) {
                                    ctx.send(b);
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
                            IngestSource ingestSource = new IngestSource(address, pipedInputStream, outputStream, (v) -> {
                                try {
                                    pipedInputStream.close();
                                    pipedOutputStream.close();
                                    ctx.closeSession();
                                } catch (IOException e) {
                                    log.warn("Failed to close piped streams and wsContext", e);
                                }
                                return null;
                            }, IngestSource.Type.WEBSOCKET);
                            new IngestHandler(ingestSource, packetHandler, authHandler, logAction, true);
                        });
                    })
                    .start(Integer.parseInt(javalinPort));
        }

        if (!noSocketServer) {
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
                            Socket socket = sslServerSocket.accept();
                            new IngestHandler(IngestSource.from(socket), packetHandler, authHandler, logAction, false);
                        }
                    } catch (IOException e) {
                        log.error("Failed to start SSL socket server", e);
                    }
                } else {
                    try (var serverSocket = new ServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("Socket server started");
                        while (true) {
                            Socket socket = serverSocket.accept();
                            new IngestHandler(IngestSource.from(socket), packetHandler, authHandler, logAction, false);
                        }
                    } catch (IOException e) {
                        log.error("Failed to start socket server", e);
                    }
                }
            }, "Logflow Socket Server").start();
        }
        log.info("Logflow started");
    }

}
