package at.shorty.logflow;

import at.shorty.logflow.hikari.HikariConnectionPool;
import at.shorty.logflow.util.LogflowArgsParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;

import static spark.Spark.*;

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

        var jdbcUrl = System.getenv("LOGFLOW_HIKARI_JDBC_URL");
        var username = System.getenv("LOGFLOW_HIKARI_USERNAME");
        var password = System.getenv("LOGFLOW_HIKARI_PASSWORD");
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

        var sslKeystorePath = System.getProperty("javax.net.ssl.keyStore");
        var sslKeystorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
        if (!noWebServer) {
            log.info("Initializing web server...");
            var isSSL = false;
            if (sslKeystorePath == null || sslKeystorePassword == null || !webUseSSL) {
                var errorMessage = !webUseSSL ? "Make sure to start Logflow with -webUseSSL" : "Make sure to provide javax.net.ssl.keyStore and javax.net.ssl.keyStorePassword system properties";
                log.warn("Not using SSL for web server ({})", errorMessage);
            } else {
                log.info("Using SSL for web server");
                isSSL = true;
                secure(sslKeystorePath, sslKeystorePassword, null, null);
            }
            var sparkPort = System.getenv("LOGFLOW_SPARK_PORT");
            if (sparkPort == null) {
                sparkPort = isSSL ? "2096" : "2086";
            }
            log.info("Starting web server on port " + sparkPort + (isSSL ? " (SSL)" : "") + "...");
            port(Integer.parseInt(sparkPort));
            // TODO: Implement web server
            get("/", (req, res) -> "Hello World!");
        }

        if (!noSocketServer) {
            new Thread(() -> {
                log.info("Initializing socket server...");
                var isSSL = false;
                if (sslKeystorePath == null || sslKeystorePassword == null || !socketUseSSL) {
                    var errorMessage = !socketUseSSL ? "Make sure to start Logflow with -socketUseSSL" : "Make sure to provide javax.net.ssl.keyStore and javax.net.ssl.keyStorePassword system properties";
                    log.warn("Not using SSL for socket server ({})", errorMessage);
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
                class LogflowSocketHandler {
                    public void init(Socket socket) {
                        new Thread(() -> {
                            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                                String line;
                                while ((line = bufferedReader.readLine()) != null) {
                                    log.info("Socket message: " + line);
                                }
                            } catch (IOException e) {
                                log.warn("Unexpected exception while reading socket", e);
                            }
                        }).start();
                    }
                }
                if (finalIsSSL) {
                    var sslServerSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                    try (var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("SSL socket server started");
                        while (true) {
                            new LogflowSocketHandler().init(sslServerSocket.accept());
                        }
                    } catch (IOException e) {
                        log.error("Failed to start SSL socket server", e);
                    }
                } else {
                    try (var serverSocket = new ServerSocket(Integer.parseInt(finalSocketPort))) {
                        log.info("Socket server started");
                        while (true) {
                            new LogflowSocketHandler().init(serverSocket.accept());
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
