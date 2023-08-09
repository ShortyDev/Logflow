package at.shorty.logflow;

import at.shorty.logflow.ingest.packet.WrappedPacket;
import at.shorty.logflow.ingest.packet.impl.InPacketAuth;
import at.shorty.logflow.ingest.packet.impl.InPacketLog;
import at.shorty.logflow.log.Level;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Socket;
import java.util.Base64;
import java.util.Date;
import java.util.Random;

public class Logtest {

    public static void main(String[] args) throws InterruptedException {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Socket socket = new Socket("localhost", 7200);
            InPacketAuth inPacketAuth = new InPacketAuth();
            inPacketAuth.setToken("");
            WrappedPacket wrappedPacket = new WrappedPacket();
            wrappedPacket.setId(0);
            wrappedPacket.setPacket(objectMapper.writeValueAsString(inPacketAuth));
            socket.getOutputStream().write((objectMapper.writeValueAsString(wrappedPacket) + "\n").getBytes());
            while (true) {
                InPacketLog inPacketLog = new InPacketLog();
                String content = "Added a new user with id " + new Random().nextInt(100);
                String base64Encoded = Base64.getEncoder().encodeToString(content.getBytes());
                System.out.println(base64Encoded);
                inPacketLog.setContent(base64Encoded);
                inPacketLog.setSource("test-source");
                inPacketLog.setTimestamp(new Date());
                inPacketLog.setLevel(Level.INFO);
                inPacketLog.setContext("discord");
                inPacketLog.setTags(new String[]{"admin", "user", "create"});
                inPacketLog.setSourceIp("123");
                wrappedPacket = new WrappedPacket();
                wrappedPacket.setId(1);
                wrappedPacket.setPacket(objectMapper.writeValueAsString(inPacketLog));
                socket.getOutputStream().write((objectMapper.writeValueAsString(wrappedPacket) + "\n").getBytes());
                System.out.println(objectMapper.writeValueAsString(wrappedPacket));
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}
