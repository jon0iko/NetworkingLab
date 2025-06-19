import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class Server {
    private static final int PORT = 3923;
    private static final ArrayList<ClientHandler> clients = new ArrayList<>();
    private static int clientCounter = 0;

    public static void main(String[] args) {
        try {
            ServerSocket handshakingSocket = new ServerSocket(PORT);
            System.out.println("Server started on port " + PORT);

            Thread serverInputThread = new Thread(() -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String input;
                try {
                    System.out.println("-------------------------------");
                    System.out.println("Format: 'clientNumber Message'");
                    System.out.println("Type '0 Message' to broadcast to all clients");
                    System.out.println("Type 'exit' to shut down the server");
                    System.out.println("-------------------------------");

                    while ((input = reader.readLine()) != null) {
                        Pattern pattern = Pattern.compile("^(\\d+)\\s+(.+)$");
                        Matcher matcher = pattern.matcher(input);
                        
                        if(input.equalsIgnoreCase("exit")) {
                            System.out.println("Server shutting down...");
                            for (ClientHandler client : clients) {
                                client.sendMessage("Server is terminated by admin.");
                            }
                            handshakingSocket.close();
                            break;
                        }

                        if (!matcher.matches()) {
                            System.out.println("Invalid format");
                            continue;
                        }

                        int targetClientNumber = Integer.parseInt(matcher.group(1));
                        String message = matcher.group(2);

                        if (targetClientNumber == 0) {
                            broadcastMessage(message);
                        } else {
                            boolean sent = false;
                            for (ClientHandler client : clients) {
                                if (client.getClientNumber() == targetClientNumber) {
                                    client.sendMessage(message);
                                    sent = true;
                                    break;
                                }
                            }
                            if (!sent) {
                                System.out.println("No such client number.");
                            }
                        }

                    }
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            });
            serverInputThread.start();

            while (true) {
                Socket communicationSocket = handshakingSocket.accept();
                
                clientCounter++;
                int clientNumber = clientCounter;

                System.out.println("Client " + clientNumber + ": " +
                        communicationSocket.getInetAddress().getHostAddress());

                DataOutputStream dataOut = new DataOutputStream(communicationSocket.getOutputStream());
                DataInputStream dataIn = new DataInputStream(communicationSocket.getInputStream());

                ClientHandler clientHandler = new ClientHandler(communicationSocket, dataIn, dataOut, clientNumber);
                clients.add(clientHandler);

                Thread clientThread = new Thread(clientHandler);
                clientThread.start();
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private static void broadcastMessage(String message) {
        for (ClientHandler client : clients) {
            try {
                client.sendMessage(message);
            } catch (IOException e) {
                System.out.println("Error sending message to Client " + client.getClientNumber());
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket socket;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;
        private String clientAddress;
        private int clientNumber;

        public ClientHandler(Socket socket, DataInputStream dataIn, DataOutputStream dataOut, int clientNumber) {
            this.socket = socket;
            this.dataIn = dataIn;
            this.dataOut = dataOut;
            this.clientAddress = socket.getInetAddress().getHostAddress();
            this.clientNumber = clientNumber;
        }

        public int getClientNumber() {
            return clientNumber;
        }

        public void sendMessage(String message) throws IOException {
            dataOut.writeUTF(message);
        }

        @Override
        public void run() {
            try {
                dataOut.writeUTF("Connected as Client " + clientNumber);
                System.out.println("Active clients: " + getActiveClientsInfo());

                String message;
                while (true) {
                    message = dataIn.readUTF();
                    System.out.println("Client " + clientNumber + ": " + message);

                    if (message.equalsIgnoreCase("Exit")) {
                        dataOut.writeUTF("Client " + clientNumber + " terminated the connection");
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