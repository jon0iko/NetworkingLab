import java.io.*;
import java.net.*;
import java.util.UUID;

public class atm {
    private static final String ip  = "192.168.137.208";
    private static final int    portt = 3923;

    public static void main(String[] args) {
        try (
            Socket socket = new Socket(ip,portt);
            PrintWriter serverOut = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in))
        ) {
            System.out.println("Connected to bank server at " + ip + ":" + portt);

           
            System.out.print("Enter card number: ");
            String cardNo = userIn.readLine();
            System.out.print("Enter PIN: ");
            String pin    = userIn.readLine();

            String authTx  = "TXN-" + UUID.randomUUID().toString();
            String authMsg = String.format("AUTH:%s:%s:%s", authTx, cardNo, pin);
            serverOut.println(authMsg);

            String authResp = serverIn.readLine();  
            if (authResp.startsWith("AUTH_OK")) {
                System.out.println("Authentication successful.\n");
            } else {
                System.out.println("Authentication failed. Exiting.");
                return;
            }

            boolean running = true;
            while (running) {
                System.out.println("Select option:");
                System.out.println("  1) Balance inquiry");
                System.out.println("  2) Withdraw");
                System.out.println("  3) Exit");
                System.out.print("Choice: ");
                String choice = userIn.readLine();

                switch (choice) {
                    case "1":
                        doBalanceInquiry(serverIn, serverOut, cardNo);
                        break;
                    case "2":
                        doWithdrawal(serverIn, serverOut, userIn, cardNo);
                        break;
                    case "3":
                        running = false;
                        System.out.println("Thank you. Goodbye!");
                        break;
                    default:
                        System.out.println("Invalid option. Please choose 1, 2, or 3.");
                }
                System.out.println();
            }

        } catch (IOException e) {
            System.err.println( e.getMessage());
        }
    }

    private static void doBalanceInquiry(BufferedReader in, PrintWriter out, String cardNo) 
            throws IOException {
        String tx = "TXN-" + UUID.randomUUID().toString();
        String req = String.format("BALANCE_REQ:%s:%s", tx, cardNo);
        out.println(req);

        String resp = in.readLine();  
        String[] parts = resp.split(":", 3);
        if (parts.length == 3 && parts[0].equals("BALANCE_RES") && parts[1].equals(tx)) {
            System.out.println("Your balance is: " + parts[2]);
        } else {
            System.out.println("Unexpected balance response: " + resp);
        }
        
        
        String ack = String.format("ACK:%s", tx);
        out.println(ack);
    }

    private static void doWithdrawal(BufferedReader in, PrintWriter out, 
                                     BufferedReader userIn, String cardNo) 
            throws IOException {
        System.out.print("Enter amount to withdraw: ");
        String amountStr = userIn.readLine();
        try {
           
            Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            System.out.println("Invalid amount format. Please enter a number.");
            return;
        }

        String tx = "TXN-" + UUID.randomUUID().toString();
        String req = String.format("WITHDRAW:%s:%s:%s", tx, cardNo, amountStr);
        out.println(req);

        String resp = in.readLine();  
        System.out.println(resp);
        String[] parts = resp.split(":");
        
        if ( parts[1].equals(tx)) {
            if (parts[0].equals("WITHDRAW_OK")) {
                System.out.println("Withdrawal approved.");
            } else if (parts[0].equals("INSUFFICIENT_FUNDS")) {
                System.out.println("Withdrawal denied: not enough funds.");
            } else {
                System.out.println("Unexpected withdrawal response: " + resp);
            }
        } else {
            System.out.println("Malformed withdrawal response: " + resp);
        }

        String ack = String.format("ACK:%s", tx);
        out.println(ack);
    }
}