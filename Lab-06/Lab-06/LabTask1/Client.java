import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    private static final String ip_address = "10.42.0.57";
    private static final int portNumb = 3923;
    public static int chunkSize = 0;

    public static void main(String[] args) {
        try {
            Socket socket = new Socket(ip_address, portNumb);
            System.out.println("Connected to server at " + ip_address + ":" + portNumb);

            // Updated IO streams to match server implementation
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            
            // Read the server prompt using DataInputStream instead of BufferedReader
            String serverPrompt = dataIn.readUTF();
            System.out.println("Server: " + serverPrompt);
            
            Scanner scanner = new Scanner(System.in);
            String fileName = scanner.nextLine();
            
            dataOut.writeUTF(fileName);
            dataOut.flush();

            File file = new File(fileName);
            if (!file.exists()) {
                System.out.println("File not found: " + fileName);
                socket.close();
                return;
            }

            FileInputStream fileIn = new FileInputStream(file);
            int sequenceNumber = 0;
            int bytesRead;

            while (true) {
                int rwnd = dataIn.readInt();
                chunkSize = rwnd;
                byte[] buffer = new byte[chunkSize];
                bytesRead = fileIn.read(buffer);
                if(bytesRead == -1) {
                    break;
                }
               //seq no. sending
                dataOut.writeInt(sequenceNumber);
                //chunck size sending
                dataOut.writeInt(bytesRead); //size of current chunk
                // chunk data sending
                dataOut.write(buffer, 0, bytesRead);
                dataOut.flush();

                System.out.println("Sent chunk " + sequenceNumber + " (" + bytesRead + " bytes)");

                // Waiting for acknowledgment
                int ack = dataIn.readInt();
                if (ack == sequenceNumber) {
                    System.out.println("Received ACK for chunk " + ack);
                    sequenceNumber++;
                } else {
                    System.out.println("Error: Expected ACK " + sequenceNumber + " but received " + ack);
                    
                }

                int urwnd = dataIn.readInt();
                System.out.println("Updated Window Size: " + urwnd);
            }

            
            dataOut.writeInt(-1);
            dataOut.flush();

          
            fileIn.close();
            socket.close();
            System.out.println("File transfer completed and connection closed.");

        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}