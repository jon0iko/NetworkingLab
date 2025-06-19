import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
                while (true) {
                    dataOut.writeUTF("Please send the file name (or 'quit' to exit):");
                    String fileName = dataIn.readUTF();
                    
                    if ("quit".equalsIgnoreCase(fileName)) {
                        break;
                    }
                    
                    // Create uploads directory if it doesn't exist
                    File uploadsDir = new File("uploads");
                    if (!uploadsDir.exists()) {
                        uploadsDir.mkdirs();
                    }
                    
                    // Generate timestamp for the uploaded file
                    LocalDateTime now = LocalDateTime.now();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
                    String timestamp = now.format(formatter);
                    
                    // Extract file extension from original filename
                    String fileExtension = "";
                    int lastDotIndex = fileName.lastIndexOf('.');
                    if (lastDotIndex > 0) {
                        fileExtension = fileName.substring(lastDotIndex);
                    }
                    
                    // Create new filename with timestamp
                    String newFileName = "uploaded-" + timestamp + fileExtension;
                    File outputFile = new File(uploadsDir, newFileName);
                    FileOutputStream fileOut = new FileOutputStream(outputFile);
                    
                    System.out.println("Client " + clientNumber + " uploading: " + fileName + " -> " + newFileName);
                    
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
                            // Write data to file
                            fileOut.write(data, 0, length);
                            fileOut.flush();
                            
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
                    
                    fileOut.close();
                    System.out.println("File " + newFileName + " upload completed successfully!");
                    
                    // Send completion acknowledgment to client
                    dataOut.writeUTF("File uploaded successfully as: " + newFileName);
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