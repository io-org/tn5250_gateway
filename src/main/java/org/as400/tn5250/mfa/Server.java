package org.as400.tn5250.mfa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;

import tools.jackson.core.type.TypeReference;

public class Server {
    // private static final String CONFIG_JSON = """
    // {
    // "proxy_port": 2323,
    // "proxy_tls": false,
    // "as400_host": "pub400.com",
    // "as400_port": 992,
    // "as400_tls": true,
    // "sign_in_screen": [
    // { "value": "Your user name:", "row": 5, "col": 1, "name": null },
    // { "value": "Password (max. 128):", "row": 6, "col": 1, "name": null },
    // { "value": null, "row": 5, "col": 25, "name": "username" },
    // { "value": null, "row": 6, "col": 25, "name": "password" }
    // ]
    // }
    // """;

    public static final String VAL_REQUEST_TYPE_SETUP = "setup";

    private static final int CLEANER_THREAD_SLEEP_MILLIS = 1000;
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

    // Maps "connectionId:sessionId" -> that session's dedicated inbox queue
    private static final Map<String, Tn5250Connection> connectionsMap = new ConcurrentHashMap<>();

    // private static Map<String, Object> configMap;
    private final BlockingQueue<Map<String, Object>> outbox = new LinkedBlockingQueue<>();

    public void serve(boolean debug) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(Tn5250Connection.KEY_REQUEST_REQUEST_TYPE, VAL_REQUEST_TYPE_SETUP);
        System.err.println("Request setup data...");

        System.out.println(Utils.OM.writeValueAsString(request));
        System.out.flush();

        String json = Utils.readJsonObject(stdin);
        System.err.println(json);
        if (json == null) {
            throw new IOException("Verifier input closed");
        }

        Map<String, Object> configMap = Utils.OM.readValue(json, JSON_MAP);

        String screens_dir = (String) configMap.get("screens_dir"); // optional
        String access_log_file = (String) configMap.get("access_log_file"); // optional
        int proxy_port = (int) configMap.get("proxy_port");
        boolean proxy_tls = (boolean) configMap.get("proxy_tls");
        String as400_host = (String) configMap.get("as400_host");
        int as400_port = (int) configMap.get("as400_port");
        boolean as400_tls = (boolean) configMap.get("as400_tls");
        int max_verification_timeout_minutes = (int) configMap.get("max_verification_timeout_minutes");
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sign_in_screen = (List<Map<String, Object>>) configMap.get("sign_in_screen");
        AsyncLogger assessLogger = new AsyncLogger(access_log_file, Integer.MAX_VALUE);
        serve(
                proxy_port,
                proxy_tls,
                as400_host,
                as400_port,
                as400_tls,
                sign_in_screen,
                max_verification_timeout_minutes,
                screens_dir,
                assessLogger,
                debug);
    }

    public void serve(
            int proxy_port,
            boolean proxy_tls,
            String as400_host,
            int as400_port,
            boolean as400_tls,
            List<Map<String, Object>> sign_in_screen,
            int max_verification_timeout_minutes,
            String screens_dir,
            AsyncLogger accessLogger,
            boolean debug) {
        if (debug) {
            System.err.println("Proxy status:   " + "started");
            System.err.println("Listening port: " + proxy_port);
            System.err.println("AS/400:         " + as400_host + ":" + as400_port);
        }
        startVerifierIoThreads();

        ServerSocketFactory serverSocketFactory = proxy_tls
                ? SSLServerSocketFactory.getDefault()
                : ServerSocketFactory.getDefault();

        try (ServerSocket server = serverSocketFactory.createServerSocket(proxy_port)) {
            while (true) {
                Socket client = server.accept();
                System.err.println("Client connected: " + client.getRemoteSocketAddress());

                String connectionId = UUID.randomUUID().toString();
                // Pass GLOBAL_OUTBOX directly; sessions register/unregister their own inboxes
                // via helper methods
                Tn5250Connection connectionHandler = new Tn5250Connection(
                        connectionId,
                        client,
                        as400_host,
                        as400_port,
                        as400_tls,
                        sign_in_screen,
                        max_verification_timeout_minutes,
                        outbox,
                        screens_dir,
                        accessLogger,
                        debug);
                connectionsMap.put(connectionId, connectionHandler);

                Thread connectionThread = new Thread(connectionHandler::run, "TN5250-Connection");
                connectionThread.start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void startVerifierIoThreads() {
        Thread cleaner = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Iterator<Tn5250Connection> it = connectionsMap.values().iterator();

                    while (it.hasNext()) {
                        Tn5250Connection tn5250Connection = it.next();
                        if (tn5250Connection.tn5250.isClosed()) {
                            it.remove();
                        }
                    }
                    Thread.sleep(CLEANER_THREAD_SLEEP_MILLIS);
                }
            } catch (Exception e) {
                System.err.println("Verifier writer error: " + e.getMessage());
            }
        }, "Verifier-Global-Cleaner");
        cleaner.setDaemon(true);
        cleaner.start();

        Thread writer = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Map<String, Object> request = outbox.take();
                    System.out.println(Utils.OM.writeValueAsString(request));
                    System.out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("Verifier writer error: " + e.getMessage());
            }
        }, "Verifier-Global-Writer");
        writer.setDaemon(true);
        writer.start();

        Thread reader = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String json = Utils.readJsonObject(stdin);
                    if (json == null) {
                        throw new IOException("Verifier input closed");
                    }

                    Map<String, Object> response = Utils.OM.readValue(json, JSON_MAP);
                    System.err.println(response);

                    Object connnectionId = response.get(Tn5250Connection.KEY_REQUEST_CONNECTION_ID);
                    Object sessionId = response.get(Tn5250Connection.KEY_REQUEST_SESSION_ID);

                    if (connnectionId == null) {
                        System.err.println("Received response missing connection_id: " + json);
                        continue;
                    }
                    Tn5250Connection connectionHandler = connectionsMap.get(connnectionId);

                    BlockingQueue<Map<String, Object>> inbox = sessionId == null ? connectionHandler.inboxConnect : connectionHandler.inboxSignIn;

                    if (inbox != null) {
                        inbox.put(response);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("Verifier reader I/O error: " + e.getMessage());
            }
        }, "Verifier-Global-Reader");
        reader.setDaemon(true);
        reader.start();
    }

}
