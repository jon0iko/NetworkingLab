import java.io.*;
import java.net.*;
import java.util.*;

public class server {
    private static final int PORT = 3923;
    private static final ArrayList<ClientHandler> clients = new ArrayList<>();
    private static int clientCounter = 0;
    private static final String FILES_DIRECTORY = "files"; 

    public static void main(String[] args) {
        try{
            File filesDir = new File(FILES_DIRECTORY);
            if (!filesDir.exists() || !filesDir.isDirectory()) {
                System.out.println("Warning: Files directory '" + FILES_DIRECTORY + "' not found or is not a directory.");
            }
            
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

                ClientHandler clientHandler = new ClientHandler(input, output, dataOut, clientNumber, communicationSocket);
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

    private static String getAvailableFiles() {
        File directory = new File(FILES_DIRECTORY);
        File[] files = directory.listFiles();
        
        if (files == null || files.length == 0) {
            return "No files available";
        }
        
        StringBuilder sb = new StringBuilder("Available files:\n");
        for (File file : files) {
            if (file.isFile()) {
                sb.append("- ").append(file.getName()).append("\n");
            }
        }
        return sb.toString();
    }

    private static class ClientHandler implements Runnable {
        private BufferedReader input; // read text
        private BufferedWriter output; // write text
        private DataOutputStream dataOut; // send file
        private String clientAddress;
        private int clientNumber;
        private Socket socket;

        public ClientHandler(BufferedReader input, BufferedWriter output, DataOutputStream dataOut, int clientNumber, Socket socket) {
            this.input = input;
            this.output = output;
            this.dataOut = dataOut;
            this.clientNumber = clientNumber;
            this.clientAddress = "unknown";
            this.socket = socket;
        }

        public int getClientNumber() {
            return clientNumber;
        }

        public void sendMessage(String message) throws IOException {
            output.write(message);
            output.newLine();
            output.flush();
        }

        private void showMenu() throws IOException {
            sendMessage("Press 1 to enter filename");
            sendMessage("Press 2 to show existing files");
            sendMessage("Press 3 to exit");
        }

        private void handleFileDownload() throws IOException {
            sendMessage("Enter the filename you want to download:");
            String filename = input.readLine();
            
            File file = new File(FILES_DIRECTORY + File.separator + filename);
            if (file.exists() && file.isFile()) {
                sendMessage("FOUND");
                dataOut.writeLong(file.length());
                FileInputStream fileInputStream = null;
                try {
                    fileInputStream = new FileInputStream(file);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        dataOut.write(buffer, 0, bytesRead);
                    }
                    dataOut.flush();
                    sendMessage("File sent successfully.");
                } finally {
                    if (fileInputStream != null) {
                        fileInputStream.close();
                    }
                }
            } else {
                sendMessage("NOT_FOUND");
                System.out.println("File not found: " + filename + " (looking in " + file.getAbsolutePath() + ")");
            }
        }

        @Override
        public void run() {
            try {
                this.clientAddress = socket.getInetAddress().getHostAddress();
                
                sendMessage("Connected as Client " + clientNumber);
                System.out.println("Active clients: " + getActiveClientsInfo());

                boolean keepRunning = true;
                while (keepRunning) {
                    showMenu();
                    
                    String choice = input.readLine();
                    
                    switch(choice) {
                        case "1": 
                            handleFileDownload();
                            break;
                            
                        case "2":
                            String availableFiles = getAvailableFiles();
                            sendMessage(availableFiles);
                            break;
                            
                        case "3":
                            sendMessage("Exiting...");
                            keepRunning = false;
                            break;
                            
                        default:
                            sendMessage("Invalid choice. Please try again.");
                            break;
                    }
                }

                System.out.println("Client " + clientNumber + " (" + clientAddress + ") disconnected");

            } catch (IOException e) {
                System.out.println(
                        "Error at Client " + clientNumber + ": " + e.getMessage());
            } finally {
                clients.remove(this);
                System.out.println("Active clients: " + getActiveClientsInfo());
                
                // Close all resources
                try {
                    if (input != null) input.close();
                    if (output != null) output.close();
                    if (dataOut != null) dataOut.close();
                    if (socket != null) socket.close();
                } catch (IOException e) {
                    System.out.println("Error closing resources for Client " + clientNumber + ": " + e.getMessage());
                }
            }
        }
    }

    private static String getActiveClientsInfo() {
        if (clients.isEmpty()) {
            return "None";
        }

        StringBuilder sb = new StringBuilder();
        for (ClientHandler client : clients) {
            sb.append("Client " + client.getClientNumber()+ "\n");
        }
        return sb.toString();
    }
}

