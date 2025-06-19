import java.io.*;
import java.net.*;

public class Client {
    private static final String ip = "localhost";
    private static final int portt = 3923;

    public static void main(String[] args) {
        try {
            Socket socket = new Socket(ip, portt);
            System.out.println("Connected to chat server at " + ip + ":" + portt);

            DataInputStream serverIn = new DataInputStream(socket.getInputStream());
            DataOutputStream serverOut = new DataOutputStream(socket.getOutputStream());
            BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in));

            Thread messageReceiver = new Thread(() -> {
                try {
                    String message;
                    while (true) {
                        message = serverIn.readUTF();
                        System.out.println("Server: " + message);
                       
                        
                        if (message.equals("Exit")) {
                            System.out.println("Server closed the connection.");
                            socket.close();
                            System.exit(0);
                            break;
                        }
                    }
                } catch (EOFException eof) {
                    System.out.println("Server closed the connection.");
                } catch (IOException e) {
                    System.out.println("Connection to server lost: " + e.getMessage());
                } finally {
                    try { socket.close(); } catch
                     (IOException ignored) {}
                    System.exit(0);
                }
            });
            messageReceiver.start();

            System.out.println("Type your messages. Type 'Exit' to quit.");
            String userInput;
            while ((userInput = userIn.readLine()) != null) {
                serverOut.writeUTF(userInput);
               
                
                if ("Exit".equalsIgnoreCase(userInput)) {
                    System.out.println("Disconnecting from server...");
                    break;
                }
            }

           
            Thread.sleep(1000);
            socket.close();
            System.exit(0);

        } catch (IOException e) {
            System.out.println("Error connecting to server: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Connection interrupted: " + e.getMessage());
        }
    }
}
