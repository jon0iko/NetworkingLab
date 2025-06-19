import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 3923;
    private static final String BASE_URL = "http://" + SERVER_HOST + ":" + SERVER_PORT;
    
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.println("\nHTTP File Client");
            System.out.println("1. Upload File");
            System.out.println("2. Download File");
            System.out.println("3. Exit");
            System.out.print("Choose an option: ");
            
            String choice = scanner.nextLine();
            
            switch (choice) {
                case "1":
                    uploadFile(scanner);
                    break;
                case "2":
                    downloadFile(scanner);
                    break;
                case "3":
                    System.out.println("Exiting...");
                    scanner.close();
                    return;
                default:
                    System.out.println("Invalid option. Please try again.");
            }
        }
    }
    
    private static void uploadFile(Scanner scanner) {
        try {
            System.out.print("Enter the file path to upload: ");
            String filePath = scanner.nextLine();
            
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                System.out.println("File not found: " + filePath);
                return;
            }            // Extract just the filename from the path
            String originalFilename = file.getName();
            
            URI uri = new URI(BASE_URL + "/upload?filename=" + URLEncoder.encode(originalFilename, "UTF-8"));
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            
            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = connection.getOutputStream()) {
                
                byte[] buffer = new byte[1024];
                int count;
                
                while ((count = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, count);
                }
            }
            
            int responseCode = connection.getResponseCode();
            
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode == 200 ? connection.getInputStream() : connection.getErrorStream()))) {
                
                String line;
                StringBuilder response = new StringBuilder();
                
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                
                System.out.println("Server response: " + response.toString());
            }
              } catch (IOException | URISyntaxException e) {
            System.out.println("Error uploading file: " + e.getMessage());
        }
    }
    
    private static void downloadFile(Scanner scanner) {
        try {
            System.out.print("Enter the filename to download: ");
            String filename = scanner.nextLine();
              URI uri = new URI(BASE_URL + "/download?filename=" + URLEncoder.encode(filename, "UTF-8"));
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 200) {
                String saveDir = "ClientFiles";
                File directory = new File(saveDir);
                if (!directory.exists()) {
                    directory.mkdir();
                }
                
                File outputFile = new File(directory, filename);
                
                try (InputStream is = connection.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outputFile)) {
                    
                    byte[] buffer = new byte[1024];
                    int count;
                    
                    while ((count = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                }
                
                System.out.println("File downloaded successfully to " + outputFile.getAbsolutePath());
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String line;
                    StringBuilder response = new StringBuilder();
                    
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    
                    System.out.println("Error: " + response.toString());
                }
            }
              } catch (IOException | URISyntaxException e) {
            System.out.println("Error downloading file: " + e.getMessage());
        }
    }
}
