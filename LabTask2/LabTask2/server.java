import java.io.*;
import java.net.*;
import java.util.*;

public class server {

    private static final int  PORT          = 3923;
    private static final int  INITIAL_RWND  = 2000;   
    private static final int  CHUNK_SIZE    = 1024;        // bytes per packet
    private static final double LOSS_PROB   = 0.10;        // 10 % simulated loss

    public static void main(String[] args) {
        System.out.println("[Server] starting on port " + PORT);
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
            System.err.println("[Server] fatal: " + e.getMessage());
        }
    }

    /* ---------- per-client worker ----------------------------------------- */

    private static final class ClientHandler implements Runnable {

        private final Socket socket;
        private final int    id;

        ClientHandler(Socket socket, int id) {
            this.socket = socket;
            this.id     = id;
        }

        @Override public void run() {

            try (DataInputStream  in  = new DataInputStream(
                                           new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(
                                           new BufferedOutputStream(socket.getOutputStream()))) {

                /* 0. Simulate a fixed receive window (optional, for completeness) */
                socket.setReceiveBufferSize(INITIAL_RWND);

                /* 1. Handshake — ask for a file name */
                out.writeUTF("Server ready — send file name");
                out.flush();
                String fileName = in.readUTF();
                System.out.printf("[C%d] requested file \"%s\"%n", id, fileName);

                /* 2. Prepare output file */
                File clientDir = new File("uploads");
                if (!clientDir.exists()) clientDir.mkdirs();
                File dest = new File(clientDir, fileName);
                try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {

                    /* 3. RDT variables */
                    int expectedSeq = 1;                         // next in-order packet wanted
                    Map<Integer, byte[]> buffer = new TreeMap<>(); // out-of-order store

                    /* 4. Receive packet stream */
                    receiving:
                    while (true) {

                        int seq = in.readInt();                  // may throw EOFException
                        if (seq == -1) {                         // −1 means “EOF”
                            break;                               //   (client has no more data)
                        }
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);

                        /* 4a. Simulate loss */
                        if (Math.random() < LOSS_PROB) {
                            System.out.printf("[C%d]  ~~ dropped pkt %d%n", id, seq);
                            continue;                            // no ACK
                        }

                        /* 4b. Normal receive path */
                        if (seq == expectedSeq) {
                            // write this packet
                            raf.seek((long) (seq - 1) * CHUNK_SIZE);
                            raf.write(data);
                            expectedSeq++;

                            // flush any buffered “next” packets
                            while (buffer.containsKey(expectedSeq)) {
                                byte[] nxt = buffer.remove(expectedSeq);
                                raf.seek((long) (expectedSeq - 1) * CHUNK_SIZE);
                                raf.write(nxt);
                                expectedSeq++;
                            }
                        } else if (seq > expectedSeq) {
                            // out-of-order; buffer it
                            buffer.put(seq, data);
                        } /* else seq < expectedSeq → duplicate; ignore */

                        /* 4c. Cumulative ACK */
                        int ackToSend = expectedSeq - 1;         // highest contiguous pkt seen
                        out.writeInt(ackToSend);
                        out.flush();

                        System.out.printf("[C%d]  recv pkt %-4d → ACK %d%n",
                                           id, seq, ackToSend);
                    } // end while(receiving)

                    System.out.printf("[C%d] file \"%s\" received OK (%d bytes)%n",
                                      id, fileName, raf.length());

                } // RandomAccessFile autoclose

                /* 5. End-of-session clean-up */
                out.writeInt(0);              // final ACK or “session done” marker
                out.flush();

            } catch (EOFException eof) {
                System.out.printf("[C%d] disconnected unexpectedly%n", id);
            } catch (IOException ioe) {
                System.err.printf("[C%d] I/O error: %s%n", id, ioe.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.printf("[C%d] handler terminated%n", id);
            }
        }
    }
}