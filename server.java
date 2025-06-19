import java.io.*;
import java.net.*;
import java.util.*;

public class server {

    private static final int PORT = 3923;
    private static final int CHUNK_SIZE = 1024; // Must match client's CHUNK_SIZE
    private static final double LOSS_PROB = 0.10; // 10% simulated packet loss

    public static void main(String[] args) {
        System.out.println("[Server] Starting on port " + PORT);
        try (ServerSocket ss = new ServerSocket(PORT)) {
            int clientId = 0;
            while (true) {
                Socket s = ss.accept();
                clientId++;
                System.out.printf("[Server] Client %d connected (%s)%n",
                        clientId, s.getInetAddress().getHostAddress());
                new Thread(new ClientHandler(s, clientId)).start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        }
    }

    private static final class ClientHandler implements Runnable {

        private final Socket socket;
        private final int id;
        private final Random random = new Random();

        ClientHandler(Socket socket, int id) {
            this.socket = socket;
            this.id = id;
        }

        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                // 1. Handshake: Ask for a file name
                out.writeUTF("Server ready. Please send file name.");
                out.flush();
                String fileName = in.readUTF();
                System.out.printf("[C%d] Client requested to send file \"%s\"%n", id, fileName);

                // 2. Prepare to receive the file
                File clientDir = new File("server_uploads");
                if (!clientDir.exists()) clientDir.mkdirs();
                File destFile = new File(clientDir, "received_C" + id + "_" + fileName);
                try (FileOutputStream fos = new FileOutputStream(destFile)) {

                    // 3. RDT variables
                    int expectedSeq = 1;
                    Map<Integer, byte[]> outOfOrderBuffer = new TreeMap<>();

                    // 4. Receive packet stream
                    while (true) {
                        int seq;
                        try {
                            seq = in.readInt(); // May throw EOFException
                        } catch (EOFException e) {
                            System.out.printf("[C%d] Client closed connection cleanly.%n", id);
                            break;
                        }

                        if (seq == -1) { // -1 is the "EOF" sentinel from the client
                            System.out.printf("[C%d] Received EOF sentinel from client.%n", id);
                            break;
                        }
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);

                        // 4a. Simulate packet loss
                        if (random.nextDouble() < LOSS_PROB) {
                            System.out.printf("[C%d]  ~~ Dropped packet %d (simulated loss) ~~%n", id, seq);
                            continue; // Don't send an ACK for the dropped packet
                        }

                        System.out.printf("[C%d]  Received packet %d%n", id, seq);

                        // 4b. Handle received packet
                        if (seq == expectedSeq) {
                            // In-order packet
                            fos.write(data);
                            expectedSeq++;

                            // Check buffer for any subsequent packets
                            while (outOfOrderBuffer.containsKey(expectedSeq)) {
                                byte[] bufferedData = outOfOrderBuffer.remove(expectedSeq);
                                fos.write(bufferedData);
                                System.out.printf("[C%d]  Wrote buffered packet %d from memory%n", id, expectedSeq);
                                expectedSeq++;
                            }
                        } else if (seq > expectedSeq) {
                            // Out-of-order packet, buffer it if not already present
                            if (!outOfOrderBuffer.containsKey(seq)) {
                                outOfOrderBuffer.put(seq, data);
                                System.out.printf("[C%d]  Buffered out-of-order packet %d%n", id, seq);
                            }
                        }
                        // If seq < expectedSeq, it's a duplicate of an already processed packet, so we ignore it.

                        // 4c. Send cumulative ACK for the highest in-order packet received
                        int ackToSend = expectedSeq - 1;
                        out.writeInt(ackToSend);
                        out.flush();
                        System.out.printf("[C%d]  -> Sent ACK for %d%n", id, ackToSend);
                    }

                    System.out.printf("[C%d] File transfer for \"%s\" complete. Saved to %s%n",
                            id, fileName, destFile.getPath());

                } // FileOutputStream is auto-closed

                // 5. Final confirmation and cleanup
                out.writeInt(-1); // Acknowledge end of session
                out.flush();

            } catch (IOException ioe) {
                System.err.printf("[C%d] I/O error: %s%n", id, ioe.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                System.out.printf("[C%d] Handler terminated.%n", id);
            }
        }
    }
}