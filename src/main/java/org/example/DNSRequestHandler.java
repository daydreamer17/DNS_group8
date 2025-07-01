package org.example;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

class DNSRequestHandler implements Runnable {
    private DatagramSocket serverSocket;
    private Map<String, String> dnsMap;
    private InetAddress dnsServerAddress;
    private byte[] requestData;
    private InetAddress clientAddress;
    private int clientPort;
    private int debugLevel;

    public DNSRequestHandler(DatagramSocket serverSocket, Map<String, String> dnsMap, InetAddress dnsServerAddress,
                             byte[] requestData, InetAddress clientAddress, int clientPort, int debugLevel) {
        this.serverSocket = serverSocket;
        this.dnsMap = dnsMap;
        this.dnsServerAddress = dnsServerAddress;
        this.requestData = requestData;
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        this.debugLevel = debugLevel;
    }

    @Override
    public void run() {
        try {
            handleRequest();
        } catch (Exception e) {
            System.err.println("Error handling DNS request: " + e.getMessage());
            e.printStackTrace();
        }
    }

   
   private void handleRequest() throws IOException {
       // Validate that the packet is long enough for a DNS header
       if (requestData.length < 12) {
           return; // Not a valid DNS query
       }
       // Parse header fields from the request
       int transactionID = ((requestData[0] & 0xFF) << 8) | (requestData[1] & 0xFF);
       // Check if this is a query (QR bit == 0 in flags)
       boolean isQuery = (requestData[2] & 0x80) == 0;
       if (!isQuery) {
           // If it's not a DNS query (maybe a response), ignore it.
           return;
       }
       // Number of questions (QDCOUNT)
       int qdCount = ((requestData[4] & 0xFF) << 8) | (requestData[5] & 0xFF);
       if (qdCount == 0) {
           // No question present in the query; nothing to do.
           return;
       }

       // Parse the Question section (assuming QDCOUNT == 1)
       int index = 12;
       StringBuilder domainNameBuilder = new StringBuilder();
       while (index < requestData.length) {
           int len = requestData[index] & 0xFF;
           if (len == 0) {
               // End of domain name
               index++;
               break;
           }
           if (index + len >= requestData.length) {
               // Malformed domain name (length byte beyond packet)
               return;
           }
           if (domainNameBuilder.length() > 0) {
               domainNameBuilder.append('.');
           }
           String label = new String(requestData, index + 1, len);
           domainNameBuilder.append(label);
           index += len + 1;
       }
       // Ensure there are enough bytes for QTYPE and QCLASS after the name.
       if (index + 4 > requestData.length) {
           return;
       }
       String domainName = domainNameBuilder.toString().toLowerCase();
       int queryType = ((requestData[index] & 0xFF) << 8) | (requestData[index + 1] & 0xFF);
       int queryClass = ((requestData[index + 2] & 0xFF) << 8) | (requestData[index + 3] & 0xFF);
       index += 4;

       if (debugLevel >= 1) {
           System.out.println("Received query from " + clientAddress.getHostAddress() + ":" + clientPort
                   + " for domain: " + domainName + " (Type " + queryType + ")");
       }

       // Determine how to handle the query based on local database
       String mappedIP = dnsMap.get(domainName);
       if (mappedIP != null && mappedIP.equals("0.0.0.0")) {
           // Case 1: Domain is blocked (0.0.0.0 in local DB) -> return NXDOMAIN
           if (debugLevel >= 1) {
               System.out.println(" -> Domain is blocked in local database. Returning NXDOMAIN.");
           }
           byte[] response = buildResponse(queryType, queryClass, transactionID, false, 3, mappedIP);
           DatagramPacket responsePacket = new DatagramPacket(response, response.length, clientAddress, clientPort);
           serverSocket.send(responsePacket);
       } else if (mappedIP != null && queryType == 1) {
           // Case 2: Domain found with a valid IP in local DB and query asks for A record.
           if (debugLevel >= 1) {
               System.out.println(" -> Domain found in local database. Returning IP: " + mappedIP);
           }
           byte[] response = buildResponse(queryType, queryClass, transactionID, true, 0, mappedIP);
           DatagramPacket responsePacket = new DatagramPacket(response, response.length, clientAddress, clientPort);
           serverSocket.send(responsePacket);
       } else {
           // Case 3: No local record (or not applicable for this query type) -> forward to external DNS server
           if (debugLevel >= 1) {
               System.out.println(" -> Domain not in local database or not an A query. Forwarding to DNS server "
                       + dnsServerAddress.getHostAddress());
           }
           byte[] dnsResponse = forwardQueryToRealDNS(requestData);
           if (dnsResponse == null) {
               // No response from DNS server (timeout or error) -> return SERVFAIL to client
               System.err.println("【SERVFAIL】There is no response/error from the upstream DNS, and SERVFAIL has been returned " + clientAddress + ":" + clientPort);
               if (debugLevel >= 1) {
                   System.out.println(" -> No response from upstream DNS server; returning SERVFAIL.");
               }
               byte[] servfailResponse = buildResponse(queryType, queryClass, transactionID, false, 2, null);
               DatagramPacket responsePacket = new DatagramPacket(servfailResponse, servfailResponse.length, clientAddress, clientPort);
               serverSocket.send(responsePacket);
           } else {
               // Got a reply from real DNS server -> forward it directly to the client
               if (debugLevel >= 2) {
                   int anCount = ((dnsResponse[6] & 0xFF) << 8) | (dnsResponse[7] & 0xFF);
                   int rcode = dnsResponse[3] & 0x0F;
                   System.out.println(" <- Response from DNS server: Answers=" + anCount + ", RCODE=" + rcode);
               }
               DatagramPacket responsePacket = new DatagramPacket(dnsResponse, dnsResponse.length, clientAddress, clientPort);
               serverSocket.send(responsePacket);
           }
       }
   }

   /**
    * Constructs a DNS response message given the query details and response parameters.
    * @param queryType The DNS query type (e.g. 1 for A).
    * @param queryClass The DNS query class (e.g. 1 for IN).
    * @param transactionID The transaction ID from the query (to echo back in response).
    * @param haveAnswer True if we include an answer (for local resolution), false if no answers.
    * @param responseCode The DNS response code (0=NoError, 2=ServFail, 3=NXDomain, etc.).
    * @param ipAddress The IP address to use in the answer (if haveAnswer is true and responseCode is 0).
    * @return A byte array representing the complete DNS response packet.
    */
   private byte[] buildResponse(int queryType, int queryClass, int transactionID, boolean haveAnswer, int responseCode, String ipAddress) {
       try {
           ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
           DataOutputStream dataOut = new DataOutputStream(byteStream);

           // Write DNS Header
           dataOut.writeShort(transactionID); // Transaction ID
           // Flags (16 bits)
           boolean rd = (requestData[2] & 0x01) != 0; // Recursion Desired from query
           int flags1 = 0;
           flags1 |= 0x80; // QR = 1 (response)
           if (responseCode == 0 || responseCode == 3) {
               flags1 |= 0x04; // AA = 1 for local answers and NXDOMAIN (authoritative response)
           }
           if (rd) {
               flags1 |= 0x01; // RD (copied from request)
           }
           dataOut.writeByte(flags1);
           int flags2 = 0;
           flags2 |= 0x80;            // RA = 1 (recursion is available)
           flags2 |= (responseCode & 0x0F); // RCODE
           dataOut.writeByte(flags2);

           dataOut.writeShort(1);    // QDCOUNT = 1 (one question)
           if (haveAnswer && responseCode == 0) {
               dataOut.writeShort(1); // ANCOUNT = 1 (one answer record)
           } else {
               dataOut.writeShort(0); // ANCOUNT = 0
           }
           dataOut.writeShort(0);    // NSCOUNT = 0 (no authority records)
           dataOut.writeShort(0);    // ARCOUNT = 0 (no additional records)

           // Question Section: copy directly from the request (name, type, class)
           // Find the length of the question section in the request data.
           int qStart = 12;
           while (qStart < requestData.length && (requestData[qStart] & 0xFF) != 0) {
               qStart += (requestData[qStart] & 0xFF) + 1;
           }
           if (qStart >= requestData.length) {
               // Malformed question; return current bytes (should not happen if input is valid).
               return byteStream.toByteArray();
           }
           // qStart now points at the 0 terminator of the QNAME.
           // Question section length = (qStart - 12 + 1) + 4 (for QTYPE and QCLASS)
           int questionLength = (qStart - 12 + 1) + 4;
           dataOut.write(requestData, 12, questionLength);

           // Answer Section (only if we have a local answer to add)
           if (haveAnswer && responseCode == 0 && ipAddress != null) {
               // Name: pointer to the query name at offset 0x0C
               dataOut.writeShort(0xC00C);
               // Write the resource record fields: Type, Class, TTL, RDLENGTH, RDATA
               dataOut.writeShort(queryType);   // TYPE (e.g. 1 for A)
               dataOut.writeShort(queryClass);  // CLASS (e.g. 1 for IN)
               dataOut.writeInt(3600);          // TTL (in seconds)
               byte[] addrBytes = InetAddress.getByName(ipAddress).getAddress();
               dataOut.writeShort(addrBytes.length); // RDLENGTH (should be 4 for IPv4)
               dataOut.write(addrBytes);             // RDATA (the IP address bytes)
           }

           dataOut.flush();
           return byteStream.toByteArray();
       } catch (IOException e) {
           // If an error occurs during message construction, print error and return empty array.
           if (debugLevel >= 1) {
               System.err.println("Error building DNS response: " + e.getMessage());
           }
           return new byte[0];
       }
   }

   /**
    * Forwards the DNS query to the real DNS server via UDP and waits for the response.
    * @param query The DNS query packet bytes from the client.
    * @return The DNS response packet bytes from the real DNS server, or null if no response was received.
    */
   private byte[] forwardQueryToRealDNS(byte[] query) {
       try (DatagramSocket dnsSocket = new DatagramSocket(null)){
           dnsSocket.bind(new InetSocketAddress("0.0.0.0", 0));
           if (debugLevel >= 1) {
               System.out.println("The local port of the upstream socket:" + dnsSocket.getLocalPort());
           }
           dnsSocket.setSoTimeout(5000); // Timeout after 5 seconds if no response
           DatagramPacket dnsReqPacket = new DatagramPacket(query, query.length, dnsServerAddress, 53);
           dnsSocket.send(dnsReqPacket);

           byte[] buffer = new byte[512];
           DatagramPacket dnsRespPacket = new DatagramPacket(buffer, buffer.length);

           dnsSocket.receive(dnsRespPacket);

           // 日志输出上游响应
           if (debugLevel >= 1) {
               int rcode = buffer[3] & 0x0F;
               System.out.println("THE UPSTREAM DNS RESPONDS TO RCODE: " + rcode + (rcode == 3 ? " (NXDOMAIN)" : (rcode == 0 ? " (NOERROR)" : "")));
           }
           return Arrays.copyOfRange(dnsRespPacket.getData(), dnsRespPacket.getOffset(),
                   dnsRespPacket.getOffset() + dnsRespPacket.getLength());
       } catch (IOException e) {
           System.err.println("Error forwarding query to DNS server: " + e.getMessage());
           e.printStackTrace();
           return null;
       }
   }
}
