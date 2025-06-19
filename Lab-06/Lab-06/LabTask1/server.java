import java.net.*;
import java.util.*;
import java.io.*;

public class server {
    private static final int PORT = 3923;
    private static final ArrayList<ClientHandler> clients = new ArrayList<>();
    private static int clientCounter = 0;
    public static int rwnd = 2000;

    public static void main(String[] args) {
        try {
        ServerSocket handshakingSocket = new ServerSocket(PORT);
        System.out.println("Server started on port " + PORT);

        while(true) {
            Socket communicationSocket = handshakingSocket.accept();
            clientCounter++;
            int clientNumber = clientCounter;

            System.out.println("Client " + clientNumber + " connected from " + communicationSocket.getInetAddress().getHostAddress());
            BufferedReader input = new BufferedReader(new InputStreamReader(communicationSocket.getInputStream()));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(communicationSocket.getOutputStream()));
            DataOutputStream dataOut = new DataOutputStream(communicationSocket.getOutputStream());
            DataInputStream dataIn = new DataInputStream(communicationSocket.getInputStream());

            ClientHandler clientHandler = new ClientHandler(input, output, dataOut, dataIn, clientNumber, communicationSocket);
            clients.add(clientHandler);

            Thread clientThread = new Thread(clientHandler);
            clientThread.start();
        }
        }
        catch (IOException e) {
            System.out.println("Error creating server socket: " + e.getMessage());
            return;
        }
    }

    private static class ClientHandler implements Runnable {
        private BufferedReader input;
        private BufferedWriter output;
        private DataOutputStream dataOut;
        private DataInputStream dataIn;
        private Socket socket;
        private int clientNumber;

        public ClientHandler(BufferedReader input, BufferedWriter output, DataOutputStream dataOut, DataInputStream dataIn, int clientNumber, Socket socket) {
            this.input = input;
            this.output = output;
            this.dataOut = dataOut;
            this.dataIn = dataIn;
            this.clientNumber = clientNumber;
            this.socket = socket;
        }
        
        @Override
        public void run() {
            try {
                dataOut.writeUTF("Please send the file name:");
                String fileName = dataIn.readUTF();
                File file = new File(fileName);
                
                int bytesReceived = 0;
                int lastAck = 0;
                int expectedSeqNum = 0;
                
                while (true) {
                    // Send the rwnd value to the client
                    dataOut.writeInt(rwnd);
                    dataOut.flush();

                    int seqNum = dataIn.readInt();
                    int length = dataIn.readInt();
                    if (length == -1) {
                        break;
                    }
                    
                    byte[] data = new byte[length];
                    dataIn.readFully(data, 0, length);
                    
                    if (seqNum == expectedSeqNum) {
                        bytesReceived += length;
                        expectedSeqNum++;
                        
                        System.out.println("Received packet with seqNum: " + seqNum + ", length: " + length + ", total bytes: " + bytesReceived);
                        
                        int availableWindow = rwnd - bytesReceived;
                        
                        // Send ACK with updated window size
                        dataOut.writeInt(seqNum); // ACK number
                        dataOut.writeInt(availableWindow); // Window size
                        dataOut.flush();
                        
                        lastAck = seqNum;
                        
                        if (availableWindow < rwnd / 2) {
                            bytesReceived = 0;
                            System.out.println("Processing buffered data, window reset to " + rwnd);
                        }
                    } else {
                        dataOut.writeInt(lastAck);
                        dataOut.writeInt(rwnd - bytesReceived);
                        dataOut.flush();
                        System.out.println("Received out-of-order packet. Expected: " + expectedSeqNum + ", Got: " + seqNum);
                    }
                }
                
                socket.close();
                System.out.println("Client " + clientNumber + " disconnected.");
                
                synchronized (clients) {
                    clients.remove(this);
                }
                
            } catch (IOException e) {
                System.out.println("Error handling client " + clientNumber + ": " + e.getMessage());
                try {
                    socket.close();
                } catch (IOException ex) {
                    System.out.println("Error closing socket: " + ex.getMessage());
                }
                synchronized (clients) {
                    clients.remove(this);
                }
            }
        }
    } 
}