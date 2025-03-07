package PeerToPeer;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import PeerToPeer.*;

public class Peer extends Node {
    private DatagramSocket socket;
    private ExecutorService executorService;
    private List<Node> peers;
    private Map<String, Long> lastHeartbeatTimes;
    private volatile String lastPeerUpdate = "Waiting for peer updates...";
    private static final int TIMEOUT = 30000; // 30s before marking a peer inactive
    private static final int BUFFER_SIZE = 1024;
    private static final String CONFIG_FILE = "peer_config.properties"; // Default config file

    public Peer() {
        try {
            System.out.println("Peer is starting...");

            // Load configuration directly in peer
            List<Node> nodes = readConfig();
            if (nodes.isEmpty()) {
                System.err.println("ERROR: No nodes found in configuration.");
                System.exit(1);
            }

            // Set this node's attributes (first entry in the config is self)
            setIpAddress(InetAddress.getLocalHost().getHostAddress());
            setNodeId(getIpAddress()); // Node ID is its IP address
            setPort(nodes.get(0).getPort());
            setHomeDirectory(nodes.get(0).getHomeDirectory());

            // Exclude self from peer list
            peers = new ArrayList<>(nodes);
            peers.remove(0);

            // Initialize socket & structures
            //socket = new DatagramSocket(getPort(), InetAddress.getByName(getIpAddress()));
            socket = new DatagramSocket(getPort());
            executorService = Executors.newCachedThreadPool();
            lastHeartbeatTimes = new ConcurrentHashMap<>();

            System.out.println("Peer " + getIpAddress() + " running at " + getIpAddress() + ":" + getPort());

            // Start threads
            executorService.execute(this::sendHeartbeats);
            executorService.execute(this::listenForPeers);
            executorService.execute(this::printPeerStatus);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Reads the configuration file directly inside P2PNode.
     */
    private List<Node> readConfig() {
        List<Node> nodes = new ArrayList<>();
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) {
                System.err.println("ERROR: Configuration file not found: " + CONFIG_FILE);
                System.exit(1);
            }
    
            System.out.println("Reading P2P configuration file: " + CONFIG_FILE);
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            String localIp = InetAddress.getLocalHost().getHostAddress(); // Get this machine's IP
    
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue; // Skip empty lines and comments
    
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    System.err.println("Invalid config line: " + line);
                    continue;
                }
    
                String ipAddress = parts[0].trim();
                int port = Integer.parseInt(parts[1].trim());
                String homeDir = parts[2].trim();
    
                Node nodeInfo = new Node(ipAddress, ipAddress, port, homeDir);
                nodes.add(nodeInfo);
    
                // If this IP matches local IP, set as self
                if (ipAddress.equals(localIp)) {
                    setIpAddress(ipAddress);
                    setNodeId(ipAddress);
                    setPort(port);
                    setHomeDirectory(homeDir);
                }
            }
    
            reader.close();
        } catch (IOException e) {
            System.err.println("ERROR: Could not read the configuration file.");
            e.printStackTrace();
            System.exit(1);
        }
        return nodes;
    }

    /**
     * **Thread 1**: Sends periodic heartbeat messages with file listings.
     */
    private void sendHeartbeats() {
        Random random = new Random();
        while (true) {
            try {
                int delay = random.nextInt(30000); // Random delay (0-30s)
                Thread.sleep(delay);
    
                if (peers.isEmpty()) {
                    System.out.println("[Node " + getIpAddress() + "] No peers to send heartbeats.");
                    continue; // Skip sending if no peers
                }
    
                String fileListing = getFileListing();
                Protocol heartbeat = new Protocol(1, true, getNodeId(), System.currentTimeMillis(), 0, fileListing);
                byte[] data = heartbeat.serialize();
    
                for (Node peer : peers) {
                    DatagramPacket packet = new DatagramPacket(data, data.length,
                            InetAddress.getByName(peer.getIpAddress()), peer.getPort());
                    socket.send(packet);
                    System.out.println("[Peer " + getIpAddress() + "] Sent heartbeat with file listing.");
                }
    
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * **Thread 2**: Listens for heartbeats from other P2P nodes.
     */
    private void listenForPeers() {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                Protocol receivedProtocol = Protocol.deserialize(packet.getData());
                String senderIp = packet.getAddress().getHostAddress();
                lastHeartbeatTimes.put(senderIp, System.currentTimeMillis());

                lastPeerUpdate = receivedProtocol.getPayload(); // Store received file list

                System.out.println("[Peer " + getIpAddress() + "] Received heartbeat from node (" + senderIp + ")");
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * **Thread 3**: Prints the latest peer update every 30 seconds.
     */
    private void printPeerStatus() {
        while (true) {
            try {
                Thread.sleep(30000); // Print every 30 seconds
                System.out.println("\n[Peer " + getIpAddress() + "] Received Update from Peers");
System.out.println("--------------------------------------------------");
System.out.println(lastPeerUpdate);
System.out.println("--------------------------------------------------\n");
    
                long currentTime = System.currentTimeMillis();
                List<String> activePeers = new ArrayList<>();
                List<String> inactivePeers = new ArrayList<>();
    
                for (Node peer : peers) {
                    String peerIp = peer.getIpAddress();
                    long lastSeen = lastHeartbeatTimes.getOrDefault(peerIp, 0L);
    
                    if (currentTime - lastSeen > TIMEOUT) {
                        inactivePeers.add("Peer (" + peerIp + ") is inactive");
                    } else {
                        activePeers.add("Peer (" + peerIp + ") is active");
                    }
                }
    
                // Print active peers first
                for (String active : activePeers) {
                    System.out.println(active);
                }
    
                // Print inactive peers second
                for (String inactive : inactivePeers) {
                    System.out.println(inactive);
                }
    
                System.out.println("Latest Peer Update:");
                System.out.println(lastPeerUpdate);
                System.out.println("--------------------------------------------------\n");
    
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    

    /**
     * Retrieves the list of files in the node's home directory.
     */
    private String getFileListing() {
        File folder = new File(getHomeDirectory());

        if (!folder.exists()) {
            System.out.println("Home directory not found. Creating: " + getHomeDirectory());
            boolean created = folder.mkdirs();
            if (!created) {
                return "ERROR: Could not create home directory!";
            }
        }

        StringBuilder fileList = new StringBuilder();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                fileList.append(file.getName()).append(",");
            }
        }
        return fileList.length() > 0 ? fileList.toString() : "No files available.";
    }

    /**
     * **Main method**: Reads config file automatically.
     */
    public static void main(String[] args) {
        new Peer();
    }
}



