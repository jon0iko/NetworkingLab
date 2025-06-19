import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Client {

    // --- Configuration ---
    private static final String HOST = "localhost";
    private static final int PORT = 3923;
    private static final String FILE_TO_SEND = "Board.jpeg"; // File to be sent
    private static final int N_ROUNDS = 20; // Number of rounds to run the simulation
    private static final int CHUNK_SIZE = 1024; // bytes / packet
    private static final int INITIAL_SSTHRESH = 8; // Initial ssthresh in packets
    private static final long INITIAL_RTO_MS = 200; // Initial RTO
    private static final long MAX_TIMEOUT_MS = 5000; // Cap timeout to 5 seconds

    // --- TCP Congestion Control Modes ---
    private enum Mode {
        TAHOE, RENO
    }

    // --- Packet Definition ---
    private static final class Packet {
        final int seq;
        final byte[] data;
        long sendTime;

        Packet(int seq, byte[] data) {
            this.seq = seq;
            this.data = data;
        }
    }

    // --- Client State ---
    private final String host;
    private final int port;
    private final Path file;
    private final Mode mode;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;

    // Congestion Control State
    private volatile int cwnd = 1;
    private volatile int ssthresh = INITIAL_SSTHRESH;

    // Sliding Window & Packet Buffer
    private volatile int base = 1; // Oldest un-ACKed packet
    private int nextSeq = 1; // Next packet sequence number to send
    private final Map<Integer, Packet> sentPackets = new ConcurrentHashMap<>();

    // RTT / RTO Estimation
    private double estRtt = INITIAL_RTO_MS;
    private double devRtt = 0;
    private volatile long rto = INITIAL_RTO_MS;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    // Duplicate ACK Tracking
    private volatile int lastAck = 0;
    private volatile int dupAckCount = 0;

    // Timer for Timeouts
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timerHandle;
    private volatile boolean fastRetransmitOccurred = false;

    public Client(String host, int port, Path file, Mode mode) {
        this.host = host;
        this.port = port;
        this.file = file;
        this.mode = mode;
    }

    public void run() throws IOException, InterruptedException {
        connect();
        sendFileName();

        List<Packet> allPackets = sliceFile();
        int totalPkts = allPackets.size();

        Thread ackThread = new Thread(this::ackReceiver, "ack-receiver");
        ackThread.start();

        System.out.println("\n== TCP " + mode.name() + " Mode ==");

        for (int round = 1; round <= N_ROUNDS && base <= totalPkts; round++) {
            System.out.printf("\nRound %d: cwnd = %d, ssthresh = %d%n", round, cwnd, ssthresh);
            fastRetransmitOccurred = false;

            // Send a burst of packets up to the congestion window size
            int packetsSentThisRound = 0;
            StringJoiner sentPacketNames = new StringJoiner(", ");
            while ((nextSeq - base) < cwnd && nextSeq <= totalPkts) {
                Packet p = allPackets.get(nextSeq - 1);
                transmit(p, false);
                sentPacketNames.add("pkt" + p.seq);
                nextSeq++;
                packetsSentThisRound++;
            }
            if (packetsSentThisRound > 0) {
                System.out.println("Sent packets: " + sentPacketNames);
            } else {
                 System.out.println("Window full or file sent, waiting for ACKs...");
            }

            // Wait for ACKs. A simple sleep is sufficient for this simulation.
            Thread.sleep(rto + 50);

            // After waiting, update cwnd for the next round if no loss was detected.
            // Loss events (timeout/fast retransmit) handle their own cwnd updates.
            if (!fastRetransmitOccurred) {
                if (cwnd < ssthresh) {
                    // Slow Start: double cwnd
                    cwnd *= 2;
                    System.out.printf("Slow Start: cwnd -> %d%n", cwnd);
                } else {
                    // Congestion Avoidance: increment cwnd
                    cwnd += 1;
                    System.out.printf("Congestion Avoidance: cwnd -> %d%n", cwnd);
                }
            }
        }

        System.out.println("\n[Client] " + (base > totalPkts ? "File completely sent." : N_ROUNDS + " rounds finished."));
        out.writeInt(-1); // Send EOF sentinel
        out.flush();

        ackThread.join(1000);
        cleanup();
        System.out.println("[Client] Connection closed.");
    }

    private synchronized void transmit(Packet p, boolean isRetrans) throws IOException {
        if (p == null) return;
        out.writeInt(p.seq);
        out.writeInt(p.data.length);
        out.write(p.data);
        out.flush();

        if (!isRetrans) {
            p.sendTime = System.currentTimeMillis();
        }

        if (p.seq == base) {
            startTimer();
        }
    }

    private void ackReceiver() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int ack = in.readInt();
                if (ack == -1) break; // Server closed connection
                handleAck(ack);
            }
        } catch (IOException e) {
            // Socket closed, thread will exit.
        }
    }

    private synchronized void handleAck(int ack) {
        System.out.printf("Received: ACK:pkt%d%n", ack);

        if (ack < base) {
            return; // Ignore old ACKs
        }

        if (ack == lastAck) {
            dupAckCount++;
            if (dupAckCount == 3) {
                System.out.println("==> 3 Duplicate ACKs: Fast Retransmit triggered.");
                fastRetransmitOccurred = true;
                ssthresh = Math.max(cwnd / 2, 2);

                if (mode == Mode.TAHOE) {
                    cwnd = 1;
                    System.out.printf("TCP TAHOE Reset: cwnd -> 1, ssthresh -> %d%n", ssthresh);
                } else { // RENO
                    cwnd = ssthresh;
                    System.out.printf("TCP RENO Fast Recovery: cwnd -> %d, ssthresh -> %d%n", cwnd, ssthresh);
                }

                try {
                    transmit(sentPackets.get(base), true);
                } catch (IOException ignored) {}
                dupAckCount = 0; // Reset after handling
            }
            return;
        }

        if (ack > lastAck) {
            // New ACK
            dupAckCount = 0;
            lastAck = ack;

            Packet p = sentPackets.get(ack);
            if (p != null) {
                long sample = System.currentTimeMillis() - p.sendTime;
                updateRtt(sample);
            }

            base = ack + 1;
            sentPackets.keySet().removeIf(seq -> seq <= ack);

            if (base == nextSeq) {
                cancelTimer();
            } else {
                startTimer();
            }
        }
    }

    private void updateRtt(long sample) {
        estRtt = (1 - ALPHA) * estRtt + ALPHA * sample;
        devRtt = (1 - BETA) * devRtt + BETA * Math.abs(sample - estRtt);
        rto = Math.min((long) (estRtt + 4 * devRtt), MAX_TIMEOUT_MS);
    }

    private void startTimer() {
        cancelTimer();
        timerHandle = sched.schedule(this::timeout, rto, TimeUnit.MILLISECONDS);
    }

    private void cancelTimer() {
        if (timerHandle != null) {
            timerHandle.cancel(false);
        }
    }

    private synchronized void timeout() {
        System.out.println("Timeout! Retransmitting Packet " + base);
        fastRetransmitOccurred = true;
        
        // Both Tahoe and Reno reset on timeout
        ssthresh = Math.max(cwnd / 2, 2);
        cwnd = 1;
        dupAckCount = 0;
        System.out.printf("TCP %s Timeout Reset: cwnd -> 1, ssthresh -> %d%n", mode.name(), ssthresh);

        try {
            rto = Math.min(rto * 2, MAX_TIMEOUT_MS); // Exponential back-off
            transmit(sentPackets.get(base), true);
        } catch (IOException e) {
            System.err.println("Retransmit failed: " + e.getMessage());
        }
    }

    private void connect() throws IOException {
        sock = new Socket(host, port);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
        System.out.println("[Client] Connected to server!");
        System.out.println("[Client] " + in.readUTF());
    }

    private void sendFileName() throws IOException {
        out.writeUTF(file.getFileName().toString());
        out.flush();
    }

    private List<Packet> sliceFile() throws IOException {
        byte[] allBytes = Files.readAllBytes(file);
        int seq = 1;
        List<Packet> packetList = new ArrayList<>();
        for (int pos = 0; pos < allBytes.length; pos += CHUNK_SIZE, seq++) {
            int len = Math.min(CHUNK_SIZE, allBytes.length - pos);
            byte[] chunk = Arrays.copyOfRange(allBytes, pos, pos + len);
            Packet p = new Packet(seq, chunk);
            sentPackets.put(seq, p);
            packetList.add(p);
        }
        System.out.printf("[Client] Prepared %d packets for file '%s' (%,d bytes)%n",
                packetList.size(), file.getFileName(), allBytes.length);
        return packetList;
    }

    private void cleanup() throws IOException {
        cancelTimer();
        sched.shutdownNow();
        if (sock != null && !sock.isClosed()) {
            sock.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Select TCP Congestion Control Mode:");
        System.out.println("1. TCP Tahoe");
        System.out.println("2. TCP Reno");
        System.out.print("Enter choice (1 or 2): ");
        int choice = scanner.nextInt();
        Mode selectedMode = (choice == 2) ? Mode.RENO : Mode.TAHOE;
        scanner.close();

        Path file = Paths.get(FILE_TO_SEND);
        if (!Files.exists(file)) {
            System.err.println("Error: File '" + FILE_TO_SEND + "' not found in the current directory.");
            // Create a dummy file for testing if it doesn't exist.
            Files.write(file, new byte[150 * 1024]); // 150 KB dummy file
            System.out.println("Created a dummy 150KB file named 'Board.jpeg' for this test run.");
        }

        new Client(HOST, PORT, file, selectedMode).run();
    }
}