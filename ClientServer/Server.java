package ClientServer;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import ClientServer.*;

public class Server extends Node {
    private DatagramSocket socket;
    private ExecutorService executorService;
    private static final int TIMEOUT = 30000; // 30s before considering a client inactive
    private static final int BUFFER_SIZE = 1024;

    private ConcurrentHashMap<String, Protocol> clientData; // Active client data (heartbeat + files)
    private ConcurrentHashMap<String, InetSocketAddress> clientAddresses; // Client IPs & Ports

    public Server(String configFile) {
        try {
            System.out.println("Server is starting...");

            // Read config file manually 
            Properties config = new Properties();
            try (FileInputStream input = new FileInputStream(configFile)) {
                config.load(input);
            } catch (FileNotFoundException e) {
                System.err.println("ERROR: server_config.properties not found in expected location: " + configFile);
                System.exit(1);
            } catch (IOException e) {
                System.err.println("ERROR: Could not read server_config.properties.");
                e.printStackTrace();
                System.exit(1);
            }

            // Set Node attributes
            setIpAddress(config.getProperty("server_ip", "127.0.0.1"));
            setPort(Integer.parseInt(config.getProperty("server_port", "5000")));

            // Bind socket to server IP and port
            InetAddress serverAddress = InetAddress.getByName(getIpAddress());
            socket = new DatagramSocket(getPort(), serverAddress);

            clientData = new ConcurrentHashMap<>();
            clientAddresses = new ConcurrentHashMap<>();

            executorService = Executors.newCachedThreadPool();

            System.out.println("Server is listening on " + getIpAddress() + ":" + getPort());

            // Start server threads
            executorService.execute(this::listenForClients);
            executorService.execute(this::monitorClients);
            executorService.execute(this::broadcastUpdates);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Thread 1: Listens for incoming heartbeats from clients.
     */
    private void listenForClients() {
        try {
            while (true) {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Extract client IP and port
                InetAddress clientIP = packet.getAddress();
                int clientPort = packet.getPort();
                String nodeId = clientIP.getHostAddress(); // Use actual IP as nodeId

                // Deserialize received data
                Protocol receivedMessage = Protocol.deserialize(packet.getData());

                // Store client's latest data (file list + timestamp)
                clientData.put(nodeId, receivedMessage);
                clientAddresses.put(nodeId, new InetSocketAddress(clientIP, clientPort));

                System.out.println("[Server] Received heartbeat from node (" + nodeId + ")");
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Thread 2: Monitors clients and marks inactive ones.
     */
    private void monitorClients() {
        while (true) {
            try {
                Thread.sleep(30000); // Check every 30 seconds
                long currentTime = System.currentTimeMillis();
    
                // Track inactive clients
                List<String> inactiveNodes = new ArrayList<>();
    
                Iterator<Map.Entry<String, Protocol>> iterator = clientData.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Protocol> entry = iterator.next();
                    String nodeId = entry.getKey();
                    long lastUpdate = entry.getValue().getTimestamp();
    
                    // Check if client is inactive
                    if (currentTime - lastUpdate > TIMEOUT) {
                        if (clientData.containsKey(nodeId)) {
                            System.out.println("[Server] Node " + nodeId + " is now inactive.");
                        }
                        inactiveNodes.add(nodeId);
                        iterator.remove(); // Removes from clientData
                        clientAddresses.remove(nodeId);
                    }
                }
    
                // Print inactive nodes if any were found
                if (!inactiveNodes.isEmpty()) {
                    System.out.println("[Server] Inactive clients: " + String.join(", ", inactiveNodes));
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Thread 3: Broadcasts availability & file listings to all clients.
     */
    private void broadcastUpdates() {
        while (true) {
            try {
                Thread.sleep(30000); // Send updates every 30 seconds
    
                if (clientData.isEmpty() && clientAddresses.isEmpty()) {
                    System.out.println("[Server] No clients to broadcast updates.");
                    continue;
                }
    
                List<String> inactiveNodes = new ArrayList<>();
                long currentTime = System.currentTimeMillis();
    
                // Identify inactive clients
                Iterator<Map.Entry<String, Protocol>> iterator = clientData.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Protocol> entry = iterator.next();
                    String nodeId = entry.getKey();
                    long lastUpdate = entry.getValue().getTimestamp();
    
                    if (currentTime - lastUpdate > TIMEOUT) {
                        System.out.println("[Server] Node " + nodeId + " is now inactive.");
                        inactiveNodes.add(nodeId);
                        iterator.remove(); // Remove inactive client
                        clientAddresses.remove(nodeId);
                    }
                }
    
                // Build message containing both active and inactive nodes
                StringBuilder updatePayload = new StringBuilder();
    
                // Add active nodes
                for (Map.Entry<String, Protocol> entry : clientData.entrySet()) {
                    String nodeId = entry.getKey();
                    String fileList = entry.getValue().getPayload();
                    updatePayload.append("Node (").append(nodeId).append("): active, Files: [")
                            .append(fileList).append("]\n");
                }
    
                // Add inactive nodes
                for (String nodeId : inactiveNodes) {
                    updatePayload.append("Node (").append(nodeId).append("): inactive\n");
                }
    
                // Create a single update packet
                Protocol combinedUpdate = new Protocol(1, false, "server", System.currentTimeMillis(), 0, updatePayload.toString());
                byte[] data = combinedUpdate.serialize();
    
                // Send ONE packet to each active client
                for (InetSocketAddress clientAddress : clientAddresses.values()) {
                    DatagramPacket packet = new DatagramPacket(data, data.length,
                            clientAddress.getAddress(), clientAddress.getPort());
                    socket.send(packet);
                }
    
                System.out.println("[Server] Sent updated network status.");
    
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar Server.jar <config_file>");
            System.exit(1);
        }
        new Server(args[0]); //args[0]
    }
}