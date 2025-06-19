import java.io.*;
import java.net.*;
import java.util.*;

public class Client {
    private static final String ip_address = "";
    private static final int portNumb = 3923;
      // EWMA parameters
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    private static final int INITIAL_TIMEOUT = 1000; // ms
    private static final int MIN_TIMEOUT = 100; // Minimum timeout value
    
    // Packet class for buffering
    static class Packet {
        int seqNum;
        byte[] data;
        int length;
        long sendTimeNanos; // Changed to store nanoseconds
        
        Packet(int seqNum, byte[] data, int length) {
            this.seqNum = seqNum;
            this.data = data;
            this.length = length;
        }
    }    public static void main(String[] args) {
        try {
            Socket socket = new Socket(ip_address, portNumb);
            System.out.println("Connected to server!");

            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            Scanner scanner = new Scanner(System.in);
            
            while (true) {
                String serverPrompt = dataIn.readUTF();
                System.out.println("Server: " + serverPrompt);
                
                String fileName = scanner.nextLine();
                
                dataOut.writeUTF(fileName);
                dataOut.flush();
                
                if ("quit".equalsIgnoreCase(fileName)) {
                    break;
                }

                File file = new File(fileName);
                if (!file.exists()) {
                    System.out.println("File not found: " + fileName);
                    continue;
                }                // Initialize transfer variables
                FileInputStream fileIn = new FileInputStream(file);
                int sequenceNumber = 1;
                int duplicateAckCount = 0;
                int lastAck = 0;
                double estimatedRTT = 0; // Initial RTT in ms
                double devRTT = 0;       // Initial DevRTT in ms
                double timeoutInterval = INITIAL_TIMEOUT; // Initial timeout in ms
                Map<Integer, Long> sendTimesNanos = new HashMap<>(); // Store send times in nanoseconds
                Map<Integer, Packet> buffer = new HashMap<>(); // Buffer for potential retransmission
                
                // Send packets one-by-one and handle ACKs
                while (true) {
                    // Get window size from server
                    int rwnd = dataIn.readInt();
                    
                    // Read data for this packet
                    byte[] data = new byte[rwnd];
                    int bytesRead = fileIn.read(data);
                    
                    if (bytesRead == -1) {
                        // End of file - send termination signal
                        dataOut.writeInt(sequenceNumber); // Send current sequence number as part of termination
                        dataOut.writeInt(-1); // Signal end of file
                        dataOut.flush();
                        break;
                    }
                    
                    // Create and send packet
                    Packet pkt = new Packet(sequenceNumber, data, bytesRead);
                    dataOut.writeInt(pkt.seqNum);
                    dataOut.writeInt(pkt.length);
                    dataOut.write(pkt.data, 0, pkt.length);
                    dataOut.flush();
                    
                    // Record send time for SampleRTT measurement
                    pkt.sendTimeNanos = System.nanoTime(); // Use nanoTime
                    sendTimesNanos.put(pkt.seqNum, pkt.sendTimeNanos);
                    buffer.put(pkt.seqNum, pkt); // Save in buffer for potential retransmission
                    
                    System.out.println("Sending Packet " + pkt.seqNum + " with Seq# " + pkt.seqNum);
                    
                    boolean acked = false;
                    while (!acked) {
                        int currentTimeout = (int) timeoutInterval; // Convert to milliseconds
                        // Set socket timeout for retransmission
                        // Ensure timeout is at least MIN_TIMEOUT, also make sure it's not negative or zero if timeoutInterval is very small.
                        // int currentTimeout = Math.max((int)timeoutInterval, MIN_TIMEOUT);
                        // if (currentTimeout <= 0) {
                        //     currentTimeout = MIN_TIMEOUT; // Fallback if calculation results in non-positive
                        // }
                        socket.setSoTimeout(currentTimeout);
                        
                        try {
                            // Wait for ACK
                            int ack = dataIn.readInt();
                            int urwnd = dataIn.readInt(); // Read updated window size
                            
                            if (ack == sequenceNumber) {
                                // ACK matches expected one - update timers and sequence progress
                                Long sendTimeNano = sendTimesNanos.get(ack);
                                if (sendTimeNano == null) { 
                                    System.out.println("Error: Send time not found for ACK " + ack + ". Ignoring this ACK.");
                                    // This might happen if an ACK arrives for a packet that was already processed and removed
                                    // or due to some other logic error. For now, we'll just log and continue.
                                    // If this ACK was crucial, the timeout mechanism should eventually handle retransmission.
                                    continue; 
                                }
                                long sampleRTTNanos = System.nanoTime() - sendTimeNano;
                                double sampleRTTMillis = sampleRTTNanos / 1_000_000.0; // Convert to milliseconds
                                
                                // Measure SampleRTT and update timeout using EWMA
                                if (estimatedRTT == 0 && devRTT == 0) { // First measurement
                                    estimatedRTT = sampleRTTMillis;
                                    devRTT = sampleRTTMillis / 2.0;
                                } else {
                                    estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTTMillis;
                                    devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTTMillis - estimatedRTT);
                                }
                                timeoutInterval = estimatedRTT + 4 * devRTT;
                                
                                // Enforce minimum timeout to prevent near-zero values
                                // if (timeoutInterval < MIN_TIMEOUT) {
                                //     timeoutInterval = MIN_TIMEOUT;
                                // }
                                
                                System.out.printf("ACK %d received. RTT = %.2fms. EstimatedRTT = %.2fms, DevRTT = %.2fms, Timeout = %.0fms\\n", 
                                    ack, sampleRTTMillis, estimatedRTT, devRTT, timeoutInterval);
                                
                                sequenceNumber++;
                                acked = true;
                                duplicateAckCount = 0;
                                lastAck = ack;
                                buffer.remove(ack); // Remove from buffer
                                sendTimesNanos.remove(ack); // Clean up send times map
                                
                            } else if (ack == lastAck) {
                                // Duplicate ACK received
                                duplicateAckCount++;
                                System.out.println("Received Duplicate ACK for Seq " + ack);
                                
                                if (duplicateAckCount == 3) {
                                    // Fast Retransmit after 3 duplicate ACKs
                                    System.out.println("Fast Retransmit Triggered for Packet " + sequenceNumber);
                                    Packet resendPkt = buffer.get(sequenceNumber);
                                    if (resendPkt != null) {
                                        dataOut.writeInt(resendPkt.seqNum);
                                        dataOut.writeInt(resendPkt.length);
                                        dataOut.write(resendPkt.data, 0, resendPkt.length);
                                        dataOut.flush();
                                        resendPkt.sendTimeNanos = System.nanoTime(); // Update send time
                                        sendTimesNanos.put(resendPkt.seqNum, resendPkt.sendTimeNanos);
                                        System.out.println("Resending Packet " + resendPkt.seqNum);
                                    }
                                    duplicateAckCount = 0; // Reset after fast retransmit
                                }
                            } else {
                                // Received an ACK that is not the expected sequence number and not a duplicate of the last ACK.
                                // This could be an old ACK for a packet that has already been ACKed and processed.
                                // Or it could be an ACK for a future packet if packets were reordered (though server sends cumulative ACKs).
                                // System.out.println("Received out-of-order or old ACK: " + ack + ", expecting: " + sequenceNumber + ", lastAck: " + lastAck);
                                if (ack < sequenceNumber) {
                                    // System.out.println("It's an old ACK for " + ack + ". Current sequenceNumber is " + sequenceNumber);
                                    // If it's an ACK for something still in the buffer (e.g., a very late ACK for a packet that timed out and was resent)
                                    // We can remove it from buffer to prevent unnecessary retransmissions if it's a valid ACK for a buffered packet.
                                    if (buffer.containsKey(ack)) {
                                        // System.out.println("Late ACK " + ack + " received for a packet in buffer. Removing.");
                                        // buffer.remove(ack); // Be cautious with this, as it might interfere with fast retransmit if not handled carefully
                                        // sendTimesNanos.remove(ack);
                                    }
                                }
                            }
                            
                        } catch (SocketTimeoutException e) {
                            // Timeout - retransmit the current packet
                            System.out.println("Timeout! Retransmitting Packet " + sequenceNumber + " (Timeout was: " + currentTimeout + "ms)");
                            Packet resendPkt = buffer.get(sequenceNumber);
                            if (resendPkt != null) {
                                dataOut.writeInt(resendPkt.seqNum);
                                dataOut.writeInt(resendPkt.length);
                                dataOut.write(resendPkt.data, 0, resendPkt.length);
                                dataOut.flush();
                                resendPkt.sendTimeNanos = System.nanoTime(); // Update send time
                                sendTimesNanos.put(resendPkt.seqNum, resendPkt.sendTimeNanos);
                            }
                            // Optional: Implement exponential backoff for timeoutInterval here
                            // timeoutInterval = Math.min(timeoutInterval * 2, MAX_TIMEOUT_VALUE_IF_DEFINED);
                        }
                    }
                }                
                
                fileIn.close();
                
                String serverResponse = dataIn.readUTF();
                System.out.println("All packets delivered successfully!");
                System.out.println("Server: " + serverResponse);
                System.out.println("File transfer completed for: " + fileName);
                System.out.println("----------------------------------------");
            }
            
            // Close connection and exit
            socket.close();
            System.out.println("Connection closed.");

        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

}