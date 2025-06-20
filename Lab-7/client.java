import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class client {

    private static final String HOST = "localhost";
    private static final int PORT = 3923;
    private static final String FILE_TO_SEND = "Board.jpeg";
    private static final int CHUNK_SIZE = 1024;
    private static final int INITIAL_SSTHRESH = 64;
    private static final int TIMEOUT_MS = 500;

    private enum Mode { TAHOE, RENO }

    private final Mode mode;
    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private PrintWriter logWriter;

    private int cwnd = 1;
    private int ssthresh = INITIAL_SSTHRESH;
    private int base = 1;
    private int nextSeq = 1;
    private int lastAck = 0;
    private int duplicateAcks = 0;
    private boolean inFastRecovery = false;

    private final Map<Integer, byte[]> filePackets = new TreeMap<>();

    public client(Mode mode) {
        this.mode = mode;
    }

    public static void main(String[] args) {
        try {
            Path filePath = Paths.get(FILE_TO_SEND);
        } catch (InvalidPathException e) {
            System.err.println(e.getMessage());
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("Select TCP Congestion Control Mode:");
        System.out.println("1. TCP Tahoe");
        System.out.println("2. TCP Reno");
        System.out.print("Enter choice (1 or 2): ");
        int choice = scanner.nextInt();
        Mode selectedMode = (choice == 2) ? Mode.RENO : Mode.TAHOE;
        scanner.close();

        try {
            new client(selectedMode).run();
        } catch (IOException e) {
            System.err.println("\nClient error: " + e.getMessage());
        }
    }

    public void run() throws IOException {
        connect();
        sliceFile();
        int totalPkts = filePackets.size();
        sock.setSoTimeout(TIMEOUT_MS);

        String logFileName = (mode == Mode.TAHOE) ? "tahoe.txt" : "reno.txt";
        this.logWriter = new PrintWriter(new FileWriter(logFileName, false)); // Overwrite log
        System.out.println("Logging congestion window sizes to " + logFileName);

        System.out.println("\n== TCP " + mode.name().toUpperCase() + " Mode ==");

        for (int round = 1; base <= totalPkts; round++) {
            System.out.printf("\nRound %d: cwnd = %d, ssthresh = %d%n", round, cwnd, ssthresh);
            
            logWriter.printf("%d : %d%n", round, cwnd);

            // Send Phase
            List<Integer> sentInRound = new ArrayList<>();
            while ((nextSeq - base) < cwnd && nextSeq <= totalPkts) {
                transmit(nextSeq);
                sentInRound.add(nextSeq);
                nextSeq++;
            }
            if (!sentInRound.isEmpty()) {
                System.out.println("Sent packets: " + sentInRound.stream().map(s -> "pkt" + s).collect(Collectors.joining(", ")));
            }

            // ACK Phase
            boolean lossDetected = false;
            int acksToReceive = nextSeq - base;
            for (int i = 0; i < acksToReceive; i++) {
                try {
                    int ack = in.readInt();
                    lossDetected = handleAck(ack);
                    if (lossDetected) break;
                } catch (SocketTimeoutException e) {
                    System.out.println("==> Timeout waiting for ACK! <===");
                    lossDetected = handleTimeout();
                    break;
                }
            }

            // Congestion Control Update Phase
            if (!lossDetected) {
                if (cwnd < ssthresh) {
                    cwnd *= 2;
                    System.out.println("Slow Start: cwnd -> " + cwnd);
                } else {
                    cwnd += 1;
                    System.out.println("Congestion Avoidance: cwnd -> " + cwnd);
                }
            }
        }
        shutdown();
    }

    private void transmit(int seq) throws IOException {
        out.writeInt(seq);
        byte[] data = filePackets.get(seq);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private boolean handleAck(int ack) throws IOException {
        System.out.println("Received: ACK:pkt" + ack);
        if (ack > lastAck) { // New ACK
            base = ack + 1;
            lastAck = ack;
            duplicateAcks = 0;
            // if (inFastRecovery) { // Fast Recovery
            //     cwnd = ssthresh;
            //     inFastRecovery = false;
            // }
            return false;
        } else { // Duplicate ACK
            duplicateAcks++;
            if (duplicateAcks == 3) {
                System.out.println("==> 3 Duplicate ACKs: Fast Retransmit triggered.");
                ssthresh = Math.max(cwnd / 2, 2);
                transmit(ack + 1); // Fast Retransmit
                
                if (mode == Mode.TAHOE) {
                    cwnd = 1;
                    System.out.println("TCP TAHOE Reset: cwnd -> 1");
                } else { 
                    cwnd = ssthresh;
                    // inFastRecovery = true;
                    System.out.println("TCP RENO Action: ssthresh -> " + ssthresh + ", cwnd -> " + cwnd);
                }
                nextSeq = base;
                return true;
            }
            return false;
        }
    }

    private boolean handleTimeout() throws IOException {
        ssthresh = Math.max(cwnd / 2, 2);
        cwnd = 1;
        duplicateAcks = 0;
        inFastRecovery = false;
        System.out.println("TCP " + mode.name().toUpperCase() + " Timeout Reset: cwnd -> 1");
        transmit(base);
        nextSeq = base + 1;
        return true;
    }

    private void connect() throws IOException {
        sock = new Socket(HOST, PORT);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
        System.out.println("Connected to server. Preparing to send " + FILE_TO_SEND);
    }

    private void sliceFile() throws IOException {
        byte[] allBytes = Files.readAllBytes(Paths.get(FILE_TO_SEND));
        for (int i = 0, seq = 1; i < allBytes.length; i += CHUNK_SIZE, seq++) {
            int len = Math.min(CHUNK_SIZE, allBytes.length - i);
            filePackets.put(seq, Arrays.copyOfRange(allBytes, i, i + len));
        }
        System.out.printf("File sliced into %d packets.%n", filePackets.size());
    }

    private void shutdown() throws IOException {
        System.out.println("\nFile transfer complete.");
        out.writeInt(-1); // Signal EOF
        out.flush();
        
        if (logWriter != null) {
            logWriter.close();
        }

        if (sock != null) sock.close();
    }
}