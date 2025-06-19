import java.io.*;
import java.net.*;

public class Server {

    public static boolean isPrime(int num) {
        if (num <= 1)
            return false;
        if (num == 2)
            return true;
        if (num % 2 == 0)
            return false;
        for (int i = 3; i <= Math.sqrt(num); i += 2) {
            if (num % i == 0)
                return false;
        }
        return true;
    }

    public static void main(String[] args) {
        int port = 4537;
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("Server started. Waiting for a connection...");
            Socket socket = serverSocket.accept();
            System.out.println("Connected to: " + socket.getInetAddress().getHostAddress());

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.matches("\\d+")) {
                    System.out.println("number: " + inputLine);
                    int number = Integer.parseInt(inputLine);
                    if (isPrime(number)) {
                        out.println("its prime");
                    } else {
                        out.println("its not prime");
                    }
                } else {
                    System.out.println("From connected user: " + inputLine);
                    out.println(inputLine.toUpperCase());
                }
            }

            socket.close();
            serverSocket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
