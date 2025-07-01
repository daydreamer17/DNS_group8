package org.example;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * DNSRelay is a UDP-based DNS relay server that can answer queries from a local database
 * or forward them to a real DNS server.
 */
public class DNSRelay {
    public static void main(String[] args) {
        // Default configurations
        String dnsServerIp = "202.106.0.20";   // default real DNS server IP to forward queries
        String dataFileName = "dnsrelay.txt";  // default local DNS database file
        int debugLevel = 0;                    // 0: no debug, 1: debug (-d), 2: verbose debug (-dd)
        int port = 53;                         // UDP port to listen on (53 is standard DNS port)

        // Parse command line arguments for debug flags, DNS server IP, and data file name.
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("-d")) {
                debugLevel = Math.max(debugLevel, 1);
                // 如果下一个参数不是开头为-的，认为是文件名
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    dataFileName = args[++i];
                }
            } else if (arg.equalsIgnoreCase("-dd")) {
                debugLevel = 2;
            } else if (isIpAddress(arg)) {
                dnsServerIp = arg;
            } else {
                dataFileName = arg;
            }
        }

        if (debugLevel > 0) {
            System.out.println("Debug mode level " + debugLevel + " enabled.");
        }

        // Load local DNS records from the data file into a HashMap.
        Map<String, String> dnsMap = new HashMap<>();
        InputStream is = null;
        try {
            File file = new File(dataFileName);
            if (!file.exists()) {
                // 如果当前目录找不到，尝试src/main/resources/下找
                file = new File("src/main/resources/" + dataFileName);
            }
            if (file.exists()) {
                is = new FileInputStream(file);
                if (debugLevel > 0) {
                    System.out.println("Loaded data file from file system: " + file.getAbsolutePath());
                }
            } else {
                // 如果文件系统找不到，再尝试用资源加载
                is = DNSRelay.class.getClassLoader().getResourceAsStream(dataFileName);
                if (is != null && debugLevel > 0) {
                    System.out.println("Loaded data file from resources: " + dataFileName);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading data file: " + dataFileName);
            e.printStackTrace();
            return;
        }
        if (is == null) {
            System.err.println("Failed to load data file: " + dataFileName);
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Skip empty lines or comments in the data file.
                }
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String ip = parts[0];
                    String domain = parts[1].toLowerCase(); // store domain in lowercase for case-insensitive matching
                    dnsMap.put(domain, ip);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read data from: " + dataFileName);
            e.printStackTrace();
            return;
        }

        if (debugLevel > 0) {
            System.out.println("Loaded " + dnsMap.size() + " entries from " + dataFileName);
        }

        // Create a UDP socket for listening on the specified port.
        DatagramSocket serverSocket;
        try {
            serverSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            System.err.println("Error: Could not bind UDP socket on port " + port + ": " + e.getMessage());
            return;
        }
        if (debugLevel > 0) {
            System.out.println("DNS Relay server started on port " + port + ", forwarding queries to DNS server " + dnsServerIp);
        }

        // Convert the DNS server IP string to an InetAddress for packet forwarding.
        InetAddress dnsServerAddress;
        try {
            dnsServerAddress = InetAddress.getByName(dnsServerIp);
        } catch (UnknownHostException e) {
            System.err.println("Invalid DNS server IP address: " + dnsServerIp);
            serverSocket.close();
            return;
        }

        // Use a thread pool to handle multiple client queries concurrently.
        ExecutorService threadPool = Executors.newCachedThreadPool();

        // Buffer for incoming DNS query data.
        byte[] recvBuf = new byte[512]; // 512 bytes is typical for DNS UDP packet (without EDNS)

        // Main loop: listen for client DNS queries and dispatch them for processing.
        while (true) {
            DatagramPacket requestPacket = new DatagramPacket(recvBuf, recvBuf.length);
            try {
                serverSocket.receive(requestPacket);
            } catch (IOException e) {
                System.err.println("IO error while receiving packet: " + e.getMessage());
                continue;
            }
            // Extract the request data and client information.
            byte[] requestData = Arrays.copyOfRange(requestPacket.getData(), requestPacket.getOffset(),
                    requestPacket.getOffset() + requestPacket.getLength());
            InetAddress clientAddress = requestPacket.getAddress();
            int clientPort = requestPacket.getPort();
            // Create a handler to process this query and submit it to the thread pool.
            DNSRequestHandler handler = new DNSRequestHandler(serverSocket, dnsMap, dnsServerAddress,
                    requestData, clientAddress, clientPort, debugLevel);
            threadPool.execute(handler);
        }
    }

    /**
     * Checks if a given string is a valid IPv4 address.
     * @param s The string to check.
     * @return true if s is in the form "x.x.x.x" with each x between 0 and 255, else false.
     */
    private static boolean isIpAddress(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
