import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class bank {
    private static final int PORT = 3923;
    private static final String LOG_FILE = "serverlog.txt";
    
    public static void main(String[] args) {
        BankProtocolHandler protocolHandler = new BankProtocolHandler();
        ServerSocket serverSocket = null;
        
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Bank Server started on port " + PORT);
            
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New ATM connection from: " + clientSocket.getInetAddress());
                    
                    ClientHandler handler = new ClientHandler(clientSocket, protocolHandler);
                    new Thread(handler).start();
                } catch (IOException e) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server initialization error: " + e.getMessage());
        } finally {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            }
        }
    }
    
    private static synchronized void logToFile(String message) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            out.println("[" + timestamp + "] " + message);
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
    

    static class ClientHandler implements Runnable {
        private Socket atmTerminalConnection;
        private BankProtocolHandler securityProtocolManager;
        private PrintWriter messageOutputStream;
        private BufferedReader messageInputStream;
        private String atmTerminalIdentifier;
        private String activeCardNumber = null;
        private Map<String, String> unacknowledgedResponses = new ConcurrentHashMap<>();
        private boolean terminalActive = true;
        
        public ClientHandler(Socket socket, BankProtocolHandler protocolHandler) {
            this.atmTerminalConnection = socket;
            this.securityProtocolManager = protocolHandler;
            this.atmTerminalIdentifier = socket.getInetAddress() + "_" + socket.getPort() + "_" + 
                    System.currentTimeMillis() % 10000;
        }
        
        @Override
        public void run() {
            try {
                messageOutputStream = new PrintWriter(atmTerminalConnection.getOutputStream(), true);
                messageInputStream = new BufferedReader(new InputStreamReader(atmTerminalConnection.getInputStream()));
                
                System.out.println("Handler started for client: " + atmTerminalIdentifier);
                
                
                String inputLine;
                while (terminalActive && (inputLine = messageInputStream.readLine()) != null) {
                    processClientMessage(inputLine);
                }
            } catch (IOException e) {
                System.err.println("Error in client handler for " + atmTerminalIdentifier + ": " + e.getMessage());
            } finally {
                disconnect();
            }
        }
        
        private void processClientMessage(String message) {
            System.out.println("Received from " + atmTerminalIdentifier + ": " + message);
            
            try {
                String[] messageParts = message.split(":");
                String messageProtocolType = messageParts[0];
                String transactionUniqueId = null;
                
                if (messageParts.length > 1 && messageParts[1].startsWith("TXN")) {
                    transactionUniqueId = messageParts[1];
                } else if (!messageProtocolType.equals("ACK")) {
                    transactionUniqueId = "TXN-" + UUID.randomUUID().toString();
                }
                
                if (messageProtocolType.equals("ACK") && messageParts.length > 1) {
                    String acknowledgedTransactionId = messageParts[1];
                    handleAcknowledgement(acknowledgedTransactionId);
                    return;
                }
                
                if (transactionUniqueId == null) {
                    System.out.println("Warning: Message without transaction ID: " + message);
                    return;
                }
                
                logToFile("TRANSACTION: " + messageProtocolType + " ID=" + transactionUniqueId);
                
                if (securityProtocolManager.isCompletedTransaction(transactionUniqueId)) {
                    String cachedResponseMessage = securityProtocolManager.getCachedResponse(transactionUniqueId);
                    if (cachedResponseMessage != null) {
                        sendResponseWithRetry(cachedResponseMessage, transactionUniqueId);
                        System.out.println("Resent cached response for duplicate transaction: " + transactionUniqueId);
                    }
                    return;
                }
                
                String responseMessage = processMessageByType(messageProtocolType, messageParts, transactionUniqueId);
                if (responseMessage != null) {
                    sendResponseWithRetry(responseMessage, transactionUniqueId);
                }
            } catch (Exception ex) {
                System.err.println("Error processing message: " + message + ", Error: " + ex.getMessage());
            }
        }
        
        private String processMessageByType(String messageProtocolType, String[] messageParts, String transactionUniqueId) {
            switch (messageProtocolType) {
                case "AUTH":
                    if (messageParts.length >= 3) {
                        String cardNo = messageParts.length > 1 ? messageParts[2] : "";
                        String pin = messageParts.length > 2 ? messageParts[3] : "";
                        System.out.println("Processing AUTH: Card=" + cardNo + ", PIN=" + pin);
                        
                        // If there was a previous card, release it
                        if (activeCardNumber != null) {
                            securityProtocolManager.releaseCard(activeCardNumber);
                        }
                        
                        String response = securityProtocolManager.handleAuthentication(cardNo, pin, transactionUniqueId);
                        
                        // Store the card if authentication was successful
                        if (response.startsWith("AUTH_OK")) {
                            activeCardNumber = cardNo;
                        }
                        
                        return response;
                    }
                    return "AUTH_FAIL:" + transactionUniqueId + ":INVALID_FORMAT";
                    
                case "LOGOUT":
                    if (activeCardNumber != null) {
                        securityProtocolManager.releaseCard(activeCardNumber);
                        activeCardNumber = null;
                        return "LOGOUT_OK:" + transactionUniqueId;
                    }
                    return "ERROR:" + transactionUniqueId + ":NOT_LOGGED_IN";
                
                case "BALANCE_REQ":
                    if (messageParts.length >= 2) {
                        String cardNo = messageParts[2];
                        return securityProtocolManager.handleBalanceRequest(cardNo, transactionUniqueId);
                    }
                    return "ERROR:" + transactionUniqueId + ":INVALID_FORMAT";
                    
                case "WITHDRAW":
                    if (messageParts.length >= 3) {
                        String cardNo = messageParts[2];
                        double amount;
                        try {
                            amount = Double.parseDouble(messageParts[3]);
                        } catch (NumberFormatException e) {
                            return "ERROR:" + transactionUniqueId + ":INVALID_AMOUNT";
                        }
                        return securityProtocolManager.handleWithdrawal(cardNo, amount, transactionUniqueId);
                    }
                    return "ERROR:" + transactionUniqueId + ":INVALID_FORMAT";
                    
                default:
                    return "ERROR:" + transactionUniqueId + ":UNKNOWN_COMMAND";
            }
        }
        
        private void sendResponseWithRetry(String response, String transactionId) {
            messageOutputStream.println(response);
            System.out.println("Sent to " + atmTerminalIdentifier + ": " + response);
            
            unacknowledgedResponses.put(transactionId, response);
        }
        
        private void handleAcknowledgement(String transactionId) {
            unacknowledgedResponses.remove(transactionId);
            System.out.println("Received ACK for transaction: " + transactionId);
            
            logToFile("ACK RECEIVED: " + transactionId);
        }
        
        
        private void disconnect() {
            terminalActive = false;
            
            // Release card when client disconnects
            if (activeCardNumber != null) {
                securityProtocolManager.releaseCard(activeCardNumber);
                activeCardNumber = null;
            }
            
            try {
                if (messageOutputStream != null) messageOutputStream.close();
                if (messageInputStream != null) messageInputStream.close();
                if (atmTerminalConnection != null && !atmTerminalConnection.isClosed()) atmTerminalConnection.close();
                System.out.println("Client disconnected: " + atmTerminalIdentifier);
            } catch (IOException e) {
                System.err.println("Error during disconnect: " + e.getMessage());
            }
        }
    }
}


class BankProtocolHandler {
    private Map<String, Account> accounts = new HashMap<>();
    
    private Map<String, String> completedTransactions = new ConcurrentHashMap<>();
    
    private Map<String, String> authenticatedSessions = new ConcurrentHashMap<>();
    private Set<String> withdrawalCompletedSessions = Collections.synchronizedSet(new HashSet<>());
    private Set<String> activeCards = Collections.synchronizedSet(new HashSet<>());
    
    public BankProtocolHandler() {
        initializeTestAccounts();
    }
    
    private void initializeTestAccounts() {
        accounts.put("123456", new Account("123456", "1234", 1000.0));
        accounts.put("0987654321", new Account("0987654321", "4321", 500.0));
        accounts.put("1111222233", new Account("1111222233", "5678", 250.0));
        
        System.out.println("Bank initialized with " + accounts.size() + " test accounts");
        logCompletedTransaction("INIT", "Bank initialized with " + accounts.size() + " test accounts");
    }
    
    public boolean isCompletedTransaction(String transactionId) {
        return completedTransactions.containsKey(transactionId);
    }
    
    public String getCachedResponse(String transactionId) {
        return completedTransactions.get(transactionId);
    }
    
    public String handleAuthentication(String cardNo, String pin, String transactionId) {
        System.out.println("Processing AUTH request: Card=" + cardNo + ", TXN=" + transactionId);
        
        if (isCompletedTransaction(transactionId)) {
            return completedTransactions.get(transactionId);
        }
        
        // Check if card is already in use
        if (activeCards.contains(cardNo)) {
            String response = "AUTH_FAIL:" + transactionId + ":CARD_IN_USE";
            logCompletedTransaction(transactionId, response);
            System.out.println("AUTH failed: Card " + cardNo + " is already in use");
            return response;
        }
        
        Account account = accounts.get(cardNo);
        String response;
        
        if (account != null && account.validatePin(pin)) {
            activeCards.add(cardNo);
            authenticatedSessions.put(cardNo, transactionId);
            response = "AUTH_OK:" + transactionId;
            logSession(cardNo, transactionId, "AUTHENTICATED");
        } else {
            response = "AUTH_FAIL:" + transactionId;
        }
        
        logCompletedTransaction(transactionId, response);
        System.out.println("AUTH result: " + response);
        
        return response;
    }
    
    public String handleBalanceRequest(String cardNo, String transactionId) {
        System.out.println("Processing BALANCE_REQ: Card=" + cardNo + ", TXN=" + transactionId);
        
        if (isCompletedTransaction(transactionId)) {
            return completedTransactions.get(transactionId);
        }
        
        if (!isAuthenticated(cardNo)) {
            String response = "ERROR:" + transactionId + ":NOT_AUTHENTICATED";
            logCompletedTransaction(transactionId, response);
            return response;
        }
        
        Account account = accounts.get(cardNo);
        String response;
        
        if (account != null) {
            response = "BALANCE_RES:" + transactionId + ":" + account.getBalance();
        } else {
            response = "ERROR:" + transactionId + ":ACCOUNT_NOT_FOUND";
        }
        
        logCompletedTransaction(transactionId, response);
        System.out.println("BALANCE result: " + response);
        
        return response;
    }
    
    public String handleWithdrawal(String cardNo, double amount, String transactionId) {
        System.out.println("Processing WITHDRAW request: Card=" + cardNo + ", Amount=" + amount + ", TXN=" + transactionId);
        
        if (isCompletedTransaction(transactionId)) {
            return completedTransactions.get(transactionId);
        }
        
        if (!isAuthenticated(cardNo)) {
            String response = "ERROR:" + transactionId + ":NOT_AUTHENTICATED";
            logCompletedTransaction(transactionId, response);
            return response;
        }

        if (withdrawalCompletedSessions.contains(cardNo)) {
            String response = "ERROR:" + transactionId + ":WITHDRAWAL_LIMIT_REACHED";
            logCompletedTransaction(transactionId, response);
            System.out.println("Withdrawal denied: Card " + cardNo + " already performed a withdrawal in this session");
            return response;
        }
        
        Account account = accounts.get(cardNo);
        String response;
        
        if (account != null) {
            if (account.getBalance() >= amount) {
                account.withdraw(amount);
                response = "WITHDRAW_OK:" + transactionId;
                withdrawalCompletedSessions.add(cardNo);
                System.out.println("Withdrawal successful: Card=" + cardNo + ", Amount=" + amount + 
                                ", New Balance=" + account.getBalance());
            } else {
                response = "INSUFFICIENT_FUNDS:" + transactionId;
                System.out.println("Insufficient funds: Card=" + cardNo + ", Amount=" + amount + 
                                ", Available=" + account.getBalance());
            }
        } else {
            response = "ERROR:" + transactionId + ":ACCOUNT_NOT_FOUND";
            System.out.println("Account not found: Card=" + cardNo);
        }
        
        logCompletedTransaction(transactionId, response);
        
        return response;
    }
    
    private boolean isAuthenticated(String cardNo) {
        return authenticatedSessions.containsKey(cardNo);
    }
    
    private void logCompletedTransaction(String transactionId, String response) {
        completedTransactions.put(transactionId, response);
        
        try (FileWriter fw = new FileWriter("serverlog.txt", true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            out.println("[" + timestamp + "] COMPLETED TRANSACTION: " + transactionId + " -> " + response);
        } catch (IOException e) {
            System.err.println("Error writing transaction to log file: " + e.getMessage());
        }
    }
    
    private void logSession(String cardNo, String transactionId, String status) {
        try (FileWriter fw = new FileWriter("serverlog.txt", true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            out.println("[" + timestamp + "] SESSION: Card=" + cardNo + ", TXN=" + transactionId + ", Status=" + status);
        } catch (IOException e) {
            System.err.println("Error writing session to log file: " + e.getMessage());
        }
    }
    
    public void releaseCard(String cardNo) {
        if (cardNo != null) {
            activeCards.remove(cardNo);
            withdrawalCompletedSessions.remove(cardNo);
            authenticatedSessions.remove(cardNo);
            System.out.println("Card released: " + cardNo);
            logSession(cardNo, "LOGOUT", "CARD_RELEASED");
        }
    }
}


class Account {
    private String cardNumber;
    private String pin;
    private double balance;
    
    public Account(String cardNumber, String pin, double initialBalance) {
        this.cardNumber = cardNumber;
        this.pin = pin;
        this.balance = initialBalance;
    }
    
    public String getCardNumber() {
        return cardNumber;
    }
    
    public double getBalance() {
        return balance;
    }
    
    public boolean validatePin(String enteredPin) {
        return this.pin.equals(enteredPin);
    }
    
    public void withdraw(double amount) {
        if (amount > 0 && balance >= amount) {
            balance -= amount;
        }
    }
    
    public void deposit(double amount) {
        if (amount > 0) {
            balance += amount;
        }
    }
}