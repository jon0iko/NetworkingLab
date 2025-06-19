import java.io.*;
import java.net.*;
import java.util.*;

public class server {

    private static final int  PORT = 3923;
    private static final int  INITIAL_RWND = 2048;   
    private static final int  CHUNK_SIZE = 1024;       
    private static final double p = 0.10;        // 10 % simulated loss

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

    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private final int id;
        ClientHandler(Socket socket, int id) {
            this.socket = socket;
            this.id = id;
        }

        @Override public void run() {
            try (DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                socket.setReceiveBufferSize(INITIAL_RWND);
                out.writeUTF("Connection established with server. Ready to receive file.");
                out.flush();
                String fileName = in.readUTF();
                System.out.printf("[C%d] requested file \"%s\"%n", id, fileName);

                File clientDir = new File("uploads");
                if (!clientDir.exists()) clientDir.mkdirs();
                File dest = new File(clientDir, fileName);
                try (RandomAccessFile filestream = new RandomAccessFile(dest, "rw")) {
                    int expectedSeq = 1;                         
                    Map<Integer, byte[]> buffer = new TreeMap<>();

                    while (true) {

                        int seq = in.readInt();                  
                        if (seq == -1) {                         
                            break;                           
                        }
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);

                        if (Math.random() < p) {
                            System.out.printf("Packet %d not received%n", seq);
                            continue;                         
                        }

                        if (seq == expectedSeq) {
                            filestream.seek((long) (seq - 1) * CHUNK_SIZE);
                            filestream.write(data);
                            expectedSeq++;

                            while (buffer.containsKey(expectedSeq)) {
                                byte[] nxt = buffer.remove(expectedSeq);
                                filestream.seek((long) (expectedSeq - 1) * CHUNK_SIZE);
                                filestream.write(nxt);
                                expectedSeq++;
                            }
                        } else if (seq > expectedSeq) {
                            buffer.put(seq, data);
                            System.out.printf("Received Packet %d. Sending duplicate ACK %d%n", seq, expectedSeq - 1);
                        } 

                        int ackToSend = expectedSeq - 1;         
                        out.writeInt(ackToSend);
                        out.flush();
                        System.out.printf("Received Packet %-4d. Sending ACK %d%n", seq, ackToSend);
                    } 
                    System.out.printf("File \"%s\" received. Total size: (%d bytes)%n", fileName, filestream.length());
                }
                out.writeInt(0);      
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