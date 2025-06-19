import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class server {
    private static final int PORT = 3923;
    private static final int INITIAL_RWND = 10 * 1024; // Example initial receive window size (bytes)

    public static void main(String[] args) {
        System.out.println("Server starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            int clientCount = 0;
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientCount++;
                System.out.println("Client " + clientCount + " connected: " + clientSocket.getInetAddress().getHostAddress());
                ClientHandler handler = new ClientHandler(clientSocket, clientCount);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("Server: Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final int clientNumber;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;
        private int currentRwnd;

        // Packet data structure for bufferAing
        static class PacketData {
            byte[] content;
            int length;
            PacketData(byte[] content, int length) {
                this.content = content;
                this.length = length;
            }
        }

        public ClientHandler(Socket socket, int clientNumber) {
            this.socket = socket;
            this.clientNumber = clientNumber;
            this.currentRwnd = INITIAL_RWND; // Each client handler has its own rwnd state
        }

        @Override
        public void run() {
            try {
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());

                while (true) {
                    dataOut.writeUTF("Server: Please send the file name (or 'quit' to exit): ");
                    dataOut.flush();
                    String fileName = dataIn.readUTF();

                    if ("quit".equalsIgnoreCase(fileName)) {
                        System.out.println("Client " + clientNumber + " requested quit.");
                        break;
                    }
                    System.out.println("Client " + clientNumber + " requested file: " + fileName);

                    File uploadsDir = new File("uploads_client" + clientNumber);
                    if (!uploadsDir.exists() && !uploadsDir.mkdirs()) {
                        System.err.println("Server: Failed to create directory: " + uploadsDir.getAbsolutePath());
                        dataOut.writeUTF("Server: Error creating directory on server.");
                        dataOut.flush();
                        continue;
                    }


                    //add code from here
                    
                } // End of client session loop (for multiple files)
            } catch (EOFException e) {
                System.out.println("Client " + clientNumber + " disconnected.");
            } catch (IOException e) {
                System.err.println("Server: I/O error with Client " + clientNumber + ": " + e.getMessage());
            } finally {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Server: Error closing socket for Client " + clientNumber + ": " + e.getMessage());
                }
                System.out.println("Client " + clientNumber + " handler finished.");
            }
        }
    }
}