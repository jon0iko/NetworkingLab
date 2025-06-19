import java.io.*;
import java.net.*;
import java.util.*;

public class FileClient {
    private static final String ip = "localhost";
    private static final int portt = 3923;

    public static void main(String[] args) {
        try {
            Socket socket = new Socket(ip, portt);
            System.out.println("Connected to file server at " + ip + ":" + portt);

            BufferedReader sTextIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedReader clientIn = new BufferedReader(new InputStreamReader(System.in));
            BufferedWriter sTextOut = new BufferedWriter((new OutputStreamWriter(socket.getOutputStream())));
            DataInputStream fileIn = new DataInputStream(socket.getInputStream());

            // Read welcome message
            String sMsg = sTextIn.readLine();
            System.out.println("Server: " + sMsg);

            boolean keepRunning = true;
            while (keepRunning) {
                // Read menu options from server
                for (int i = 0; i < 3; i++) { // Read all 3 menu options
                    sMsg = sTextIn.readLine();
                    System.out.println("Server: " + sMsg);
                }

                // Get user choice
                System.out.print("Enter your choice: ");
                String choice = clientIn.readLine();
                
                // Send choice to server
                sTextOut.write(choice);
                sTextOut.newLine();
                sTextOut.flush();
                
                switch(choice) {
                    case "1": // Enter filename
                        handleFileDownload(sTextIn, sTextOut, fileIn, clientIn);
                        break;
                        
                    case "2": // Show existing files
                        // Read and display available files
                        sMsg = sTextIn.readLine();
                        while (!sMsg.isEmpty()) {
                            System.out.println("Server: " + sMsg);
                            sMsg = sTextIn.readLine();
                        }
                        break;
                        
                    case "3": // Exit
                        // Read exit confirmation message from server
                        sMsg = sTextIn.readLine();
                        System.out.println("Server: " + sMsg);
                        keepRunning = false;
                        break;
                        
                    default:
                        // Read error message from server
                        sMsg = sTextIn.readLine();
                        System.out.println("Server: " + sMsg);
                        break;
                }
            }

            // Close all resources
            sTextIn.close();
            clientIn.close();
            sTextOut.close();
            fileIn.close();
            socket.close();
            
        } catch (IOException e) {
            System.out.println("Error connecting to server: " + e.getMessage());
        }
    }
    
    private static void handleFileDownload(BufferedReader sTextIn, BufferedWriter sTextOut, 
                                          DataInputStream fileIn, BufferedReader clientIn) throws IOException {
        // Read prompt for filename
        String sMsg = sTextIn.readLine();
        System.out.println("Server: " + sMsg);
        
        // Get filename from user
        String filename = clientIn.readLine();
        
        // Send filename to server
        sTextOut.write(filename);
        sTextOut.newLine();
        sTextOut.flush();
        
        // Check server response
        String res = sTextIn.readLine();
        if (res.equals("FOUND")) {
            System.out.println("File found on server. Downloading...");

            long fSize = fileIn.readLong();
            System.out.println("File size: " + fSize + " Bytes");
            String downloadedF = "downloaded_" + filename;

            FileOutputStream fOut = new FileOutputStream(downloadedF);

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesRead = 0;
            
            while (totalBytesRead < fSize) {
                int bytesToRead = (int) Math.min(buffer.length, fSize - totalBytesRead);
                bytesRead = fileIn.read(buffer, 0, bytesToRead);
                
                if (bytesRead == -1) {
                    break;
                }
                
                fOut.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                // Print progress percentage
                int progress = (int) ((totalBytesRead * 100) / fSize);
                System.out.print("\rDownloading: " + progress + "% complete");
            }
            fOut.close();
            System.out.println("\nFile downloaded successfully!!");
            
            // Read the confirmation message after file transfer
            sMsg = sTextIn.readLine();
            System.out.println("Server: " + sMsg);
        }
        else if (res.equals("NOT_FOUND")) {
            System.out.println("File not found!");
        }
        else {
            System.out.println("Unexpected response from server: " + res);
        }
    }
}