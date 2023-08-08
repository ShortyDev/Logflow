package at.shorty.logflow.bootstrap;

import at.shorty.logflow.Logflow;

public class LogflowBootstrap {

    public static void main(String[] args) {
        Logflow logflow = new Logflow();
        logflow.init(args);
    }

}
