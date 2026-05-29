package org.mark.project.tools.proxy;

public class ProxyConfig {

    public final int port;
    public final String username;
    public final String password;

    private static final String DEFAULT_PORT = "8888";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "123456";

    public ProxyConfig(int port, String username, String password) {
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public static ProxyConfig parse(String[] args) {
        int port = Integer.parseInt(DEFAULT_PORT);
        String username = DEFAULT_USERNAME;
        String password = DEFAULT_PASSWORD;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("-h".equals(arg) || "--help".equals(arg)) {
                printUsage();
                System.exit(0);
            } else if ("--port".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --port requires a value");
                    System.exit(1);
                }
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Error: invalid port value: " + args[i]);
                    System.exit(1);
                }
            } else if ("--username".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --username requires a value");
                    System.exit(1);
                }
                username = args[++i];
            } else if ("--password".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --password requires a value");
                    System.exit(1);
                }
                password = args[++i];
            } else {
                System.err.println("Error: unknown option: " + arg);
                printUsage();
                System.exit(1);
            }
        }

        return new ProxyConfig(port, username, password);
    }

    public static void printUsage() {
        System.out.println("Usage: proxy [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port <port>        Proxy listening port (default: " + DEFAULT_PORT + ")");
        System.out.println("  --username <user>    Authentication username (default: " + DEFAULT_USERNAME + ")");
        System.out.println("  --password <pass>    Authentication password (default: " + DEFAULT_PASSWORD + ")");
        System.out.println("  -h, --help           Show this help message");
    }
}
