import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) {
        String host = "10.42.0.203";
        int port = 3923;

        try {
            Socket socket = new Socket(host, port);
            Scanner scanner = new Scanner(System.in);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String message;
            System.out.print(" -> ");
            message = scanner.nextLine();

            while (!message.equalsIgnoreCase("#")) {
                out.println(message); // send to server
                String response = in.readLine(); // receive from server
                System.out.println("Received from server: " + response);

                System.out.print(" -> ");
                message = scanner.nextLine();
            }

            socket.close();
            scanner.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// import java.io.BufferedReader;
// import java.io.DataOutputStream;
// import java.io.IOException;
// import java.io.InputStreamReader;
// // import java.net.*;

// public class ClientOneWay {
// public static void main(String[] args) throws IOException {
// Socket socket = new Socket("10.42.0.203", 3923);
// Scanner s = new Scanner(System.in);
// System.out.println("Client Connected at server Handshaking port " +
// s.getPort());

// System.out.println("Client’s communcation port " + s.getLocalPort());
// System.out.println("Client is Connected");
// System.out.println("Enter the messages that you want to send and send
// \"stop\" to close the connection:");

// DataOutputStream output = new DataOutputStream(s.getOutputStream());
// BufferedReader read = new BufferedReader(new InputStreamReader(System.in));

// PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
// BufferedReader in = new BufferedReader(new
// InputStreamReader(socket.getInputStream()));

// String message = "";
// while (!message.equalsIgnoreCase("bye")) {
// out.println(message); // send to server
// String response = in.readLine(); // receive from server
// System.out.println("Received from server: " + response);

// System.out.print(" -> ");
// message = s.nextLine();
// }

// output.close();
// read.close();
// s.close();
// }
// }