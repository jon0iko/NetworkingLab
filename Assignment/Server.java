
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Server {
    private static final int PORT = 3923;

    public static void main(String[] args) {
        File serverfiles = new File("ServerFiles");
        File clientfiles = new File("ClientFiles");
        
        if (!serverfiles.exists()) {
            serverfiles.mkdir();
        }
        if (!clientfiles.exists()) {
            clientfiles.mkdir();
        }
        
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());

            server.createContext("/download", new DownloadHandler(serverfiles));
            server.createContext("/upload", new UploadHandler(clientfiles));

            server.start();

            System.out.println("Server started on port: " + PORT);

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
    private static Map<String, List<String>> parseQuery(String query) throws UnsupportedEncodingException {
        Map<String, List<String>> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    
                    List<String> values = params.get(key);
                    if (values == null) {
                        values = new ArrayList<>();
                        params.put(key, values);
                    }
                    values.add(value);
                }
            }
        }
        return params;
    }

    static class DownloadHandler implements HttpHandler {
        private final File directory;

        public DownloadHandler(File directory) {
            this.directory = directory;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
                return;
            }

            URI reqUri = exchange.getRequestURI();
            String query = reqUri.getQuery();
            Map<String, List<String>> params = Server.parseQuery(query);
            
            int statusCode = 200;
            String response = "";

            if (params.containsKey("filename") && !params.get("filename").isEmpty()) {
                String filename = params.get("filename").get(0);
                File file = new File(directory, filename);
                
                if (file.exists()) {
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                    exchange.sendResponseHeaders(200, file.length());
                    
                    try (FileInputStream fis = new FileInputStream(file);
                         OutputStream os = exchange.getResponseBody()) {
                        
                        byte[] buffer = new byte[1024];
                        int count;
                        while ((count = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, count);
                        }
                    }
                    return;
                } else {
                    statusCode = 404;
                    response = "File Not Found";
                }
            } else {
                statusCode = 400;
                response = "Missing 'filename' query parameter.";
            }
            
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static class UploadHandler implements HttpHandler {
        private final File directory;

        public UploadHandler(File directory) {
            this.directory = directory;
        }        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
                return;
            }

            URI reqUri = exchange.getRequestURI();
            String query = reqUri.getQuery();
            Map<String, List<String>> params = Server.parseQuery(query);
            
            // Default filename with timestamp if no filename provided
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timestamp = now.format(formatter);
            
            String filename = "upload_" + timestamp;
            
            // Use original filename if provided in the request
            if (params.containsKey("filename") && !params.get("filename").isEmpty()) {
                filename = params.get("filename").get(0);
            }
            
            File outputFile = new File(directory, filename);

            int statusCode = 200;
            String response = "";
            
            try (InputStream is = exchange.getRequestBody();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {
                
                byte[] buffer = new byte[1024];
                int count;
                while ((count = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, count);
                }
                
                response = "File uploaded successfully as: " + outputFile.getName();
            } catch (IOException e) {
                statusCode = 500;
                response = "Error uploading file: " + e.getMessage();
            }
            
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
}