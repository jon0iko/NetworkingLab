import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Client {

    private static final int    CHUNK_SIZE   = 1024;          // bytes / packet
    private static final int    WINDOW_SIZE  = 4;             // packets in flight
    private static final double ALPHA        = 0.125;         // EWMA
    private static final double BETA         = 0.25;          // EWMA
    private static final long   INIT_RTT_MS  = 100;           // initial guess
    private static final long   MAX_TIMEOUT_MS = 5000;        // Cap timeout to 5 seconds


    private static final class Packet {
        final int    seq;
        final byte[] data;
        long         sendTime;                                

        Packet(int seq, byte[] data) {
            this.seq  = seq;
            this.data = data;
        }
    }

    private final String host;
    private final int    port;
    private final Path   file;

    private Socket            sock;
    private DataInputStream    in;
    private DataOutputStream   out;

    // sliding window pointers
    private volatile int base      = 1;                       // oldest un-ACKed
    private int           nextSeq  = 1;                       // next unsent
    private final Map<Integer, Packet> buffer = new ConcurrentHashMap<>();

    // RTT / RTO
    private double estRtt = INIT_RTT_MS;
    private double devRtt = 0;
    private long   rto    = INIT_RTT_MS;

    // duplicate-ACK bookkeeping
    private volatile int lastAck     = 0;
    private volatile int dupAckCount = 0;

    // timer
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>              timerHandle;

    /* ---------- ctor ---------------------------------------------------- */
    public Client(String host, int port, Path file) {
        this.host = host;
        this.port = port;
        this.file = file;
    }

    /* ---------- main logic --------------------------------------------- */
    public void run() throws IOException, InterruptedException {
        connect();
        sendFileName();

        List<Packet> packets = sliceFile();
        int totalPkts        = packets.size();

        // start ACK-receiver thread
        Thread ackThread = new Thread(this::ackReceiver, "ack-rx");
        ackThread.start();

        /* main send / retransmit loop ----------------------------------- */
        while (base <= totalPkts) {

            // fill window
            while (nextSeq < base + WINDOW_SIZE && nextSeq <= totalPkts) {
                Packet p = packets.get(nextSeq - 1);
                transmit(p, false);
                nextSeq++;
            }

            // tiny sleep to avoid busy-loop; real TCP uses blocking select()
            Thread.sleep(2);
        }

        /* all data acked — send EOF sentinel */
        out.writeInt(-1);            // seq = –1
        out.flush();

        ackThread.join();
        cleanup();
        System.out.println("[Client] All packets delivered successfully!");
    }

    /* ---------- send one packet ---------------------------------------- */
    private synchronized void transmit(Packet p, boolean isRetrans) throws IOException {
        out.writeInt(p.seq);
        out.writeInt(p.data.length);
        out.write(p.data);
        out.flush();

        long now = System.currentTimeMillis();
        if (!isRetrans) p.sendTime = now;

        String tag = isRetrans ? "Resent" : "Sending";
        System.out.printf("%s Packet %d with Seq# %d%n", tag, p.seq, p.seq);

        // (re)start timer if this is the base packet
        if (p.seq == base) startTimer();
    }

    /* ---------- ack receiver ------------------------------------------- */
    private void ackReceiver() {
        try {
            while (true) {
                int ack = in.readInt();             // highest in-order seq received
                handleAck(ack);
            }
        } catch (IOException e) {
            // socket closed — exits thread
        }
    }

    /* ---------- ACK handling ------------------------------------------- */
    private synchronized void handleAck(int ack) {
        if (ack == 0) return;                       // final “session done” marker

        /* duplicate ACK bookkeeping */
        if (ack == lastAck) {
            dupAckCount++;
            System.out.printf("Received Duplicate ACK for Seq %d (dup=%d)%n",
                               ack, dupAckCount);
            if (dupAckCount == 3) {
                System.out.println("Fast Retransmit Triggered for Packet " + base);
                try {
                    rto = Math.min(rto, MAX_TIMEOUT_MS); // Reset timeout to a reasonable value
                    transmit(buffer.get(base), true);
                } catch (IOException ignored) {}
            }
            return;
        } else {
            dupAckCount = 0;
            lastAck     = ack;
        }

        /* RTT sample (only for newly acked highest packet) */
        Packet p = buffer.get(ack);
        if (p != null) {
            long sample = System.currentTimeMillis() - p.sendTime;
            updateRtt(sample);
        }

        /* slide window, remove acked packets from buffer */
        base = ack + 1;
        buffer.keySet().removeIf(seq -> seq <= ack);

        /* timer management */
        if (base == nextSeq) cancelTimer();         // window empty
        else startTimer();                          // restart for new base
    }

    /* ---------- RTT / RTO update --------------------------------------- */
    private void updateRtt(long sample) {
        estRtt = Math.min((1 - ALPHA) * estRtt + ALPHA * sample, MAX_TIMEOUT_MS);
        devRtt = Math.min((1 - BETA)  * devRtt + BETA * Math.abs(sample - estRtt), MAX_TIMEOUT_MS);
        rto    = Math.min((long) (estRtt + 4 * devRtt), MAX_TIMEOUT_MS);
        System.out.printf("ACK %d received. RTT=%dms  Est=%.2fms  Dev=%.2fms  Timeout=%dms%n",
                          lastAck, sample, estRtt, devRtt, rto);
    }

    /* ---------- timer helpers ------------------------------------------ */
    private void startTimer() {
        cancelTimer();
        timerHandle = sched.schedule(this::timeout, rto, TimeUnit.MILLISECONDS);
    }
    private void cancelTimer() {
        if (timerHandle != null) timerHandle.cancel(false);
    }
    private synchronized void timeout() {
        System.out.println("Timeout! Retransmitting Packet " + base);
        try {
            rto *= 2;                               // exponential back-off
            transmit(buffer.get(base), true);
        } catch (IOException e) {
            System.err.println("Retransmit failed: " + e.getMessage());
        }
    }

    /* ---------- connect & handshake ------------------------------------ */
    private void connect() throws IOException {
        sock = new Socket(host, port);
        in   = new DataInputStream(sock.getInputStream());
        out  = new DataOutputStream(sock.getOutputStream());
        System.out.println("[Client] Connected to server!");
        System.out.println("[Client] " + in.readUTF());       // read prompt
    }
    private void sendFileName() throws IOException {
        out.writeUTF(file.getFileName().toString());
        out.flush();
    }

    /* ---------- file slicing ------------------------------------------- */
    private List<Packet> sliceFile() throws IOException {
        byte[] all = Files.readAllBytes(file);
        int    seq = 1;
        List<Packet> list = new ArrayList<>();
        for (int pos = 0; pos < all.length; pos += CHUNK_SIZE, seq++) {
            int len  = Math.min(CHUNK_SIZE, all.length - pos);
            byte[] b = Arrays.copyOfRange(all, pos, pos + len);
            Packet p = new Packet(seq, b);
            buffer.put(seq, p);                      // pre-buffer for (re)send
            list.add(p);
        }
        System.out.printf("[Client] Prepared %d packets (%,d bytes)%n",
                          list.size(), all.length);
        return list;
    }

    /* ---------- clean-up ------------------------------------------------ */
    private void cleanup() throws IOException {
        cancelTimer();
        sched.shutdownNow();
        sock.close();
    }

    /* ---------- entry point -------------------------------------------- */
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 3923;

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter the file path: ");
        String filePath = scanner.nextLine();
        Path file = Paths.get(filePath);

        new Client(host, port, file).run();
    }
}