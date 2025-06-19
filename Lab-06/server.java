import java.io.*;
import java.net.*;
import java.util.*;

public class server {

    private static final int PORT = 12345;
    private static final double PACKET_DROP_PROB = 0.05; // 5% chance to drop a packet

    public static void main(String[] args) {
        System.out.println("[Server] Starting on port " + PORT);
        try (ServerSocket ss = new ServerSocket(PORT)) {
            while (true) {
                Socket s = ss.accept();
                System.out.printf("\n[Server] Client connected (%s)%n", s.getInetAddress().getHostAddress());
                new Thread(new ClientHandler(s)).start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        }
    }

    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private final Random random = new Random();

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 FileOutputStream fos = new FileOutputStream("received_Board.jpeg")) {

                int expectedSeq = 1;
                Map<Integer, byte[]> outOfOrderBuffer = new TreeMap<>();

                while (true) {
                    int seq;
                    try {
                        seq = in.readInt();
                        if (seq == -1) { // EOF signal from client
                            System.out.println("[Server] Client signaled end of transfer.");
                            break;
                        }
                    } catch (EOFException e) {
                        System.out.println("[Server] Client closed connection.");
                        break;
                    }

                    int len = in.readInt();
                    byte[] data = new byte[len];
                    in.readFully(data);

                    // Simulate packet drop
                    if (random.nextDouble() < PACKET_DROP_PROB) {
                        System.out.printf("[Server] Dropped incoming packet %d (simulated)%n", seq);
                        continue; // Just ignore the packet
                    }

                    System.out.printf("[Server] Received packet %d%n", seq);

                    if (seq == expectedSeq) {
                        fos.write(data);
                        expectedSeq++;
                        // Process any buffered packets that are now in order
                        while (outOfOrderBuffer.containsKey(expectedSeq)) {
                            byte[] bufferedData = outOfOrderBuffer.remove(expectedSeq);
                            fos.write(bufferedData);
                            System.out.printf("[Server] Wrote buffered packet %d from memory%n", expectedSeq);
                            expectedSeq++;
                        }
                    } else if (seq > expectedSeq) {
                        // Buffer out-of-order packets
                        if (!outOfOrderBuffer.containsKey(seq)) {
                            outOfOrderBuffer.put(seq, data);
                            System.out.printf("[Server] Buffered out-of-order packet %d%n", seq);
                        }
                    }
                    
                    // Always send a cumulative ACK for the last correctly received in-order packet
                    int ackToSend = expectedSeq - 1;
                    out.writeInt(ackToSend);
                    out.flush();
                    System.out.printf("[Server] -> Sent ACK for pkt%d%n", ackToSend);
                }
                System.out.println("[Server] File received successfully and saved as 'received_Board.jpeg'");

            } catch (IOException ioe) {
                System.err.printf("[Server] I/O error: %s%n", ioe.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                System.out.println("[Server] Handler terminated.");
            }
        }
    }
}