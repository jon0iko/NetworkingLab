import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Client {
    private static final int CHUNK_SIZE = 1024;          
    private static final int WINDOW_SIZE = 4;             
    private static final double ALPHA = 0.125;         
    private static final double BETA = 0.25;          
    private static final long INITIAL_RTT = 100;           
    private static final long MAX_TIMEOUT = 2000;      

    private static final class Packet {
        final int    seq;
        final byte[] data;
        long sendTime;                                

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

    private volatile int base = 1;                       
    private int nextSeq = 1;                       
    private final Map<Integer, Packet> buffer = new ConcurrentHashMap<>();
    private double estRtt = INITIAL_RTT;
    private double devRtt = 0;
    private long timeout = INITIAL_RTT;

    private volatile int lastAck = 0;
    private volatile int dupAckCount = 0;

    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timerHandle;

    public Client(String host, int port, Path file) {
        this.host = host;
        this.port = port;
        this.file = file;
    }

    public void run() throws IOException, InterruptedException {
        connect();
        sendFileName();
        List<Packet> packets = sliceFile();
        int totalPkts = packets.size();

        Thread ackThread = new Thread(() -> ackReceiver());
        ackThread.start();

        while (base <= totalPkts) {
            while (nextSeq < base + WINDOW_SIZE && nextSeq <= totalPkts) {
                Packet p = packets.get(nextSeq - 1);
                transmit(p, false);
                nextSeq++;
            }
            // Thread.sleep(2);
        }

        out.writeInt(-1); // seq = -1 says EOF      
        out.flush();

        ackThread.join();
        cleanup();
        System.out.println("[Client] All packets delivered successfully!");
    }

    private synchronized void transmit(Packet p, boolean isRetrans) throws IOException {
        out.writeInt(p.seq);
        out.writeInt(p.data.length);
        out.write(p.data);
        out.flush();

        long now = System.currentTimeMillis();
        if (!isRetrans) p.sendTime = now;

        String tag = isRetrans ? "Resent" : "Sending";
        System.out.printf("%s Packet %d with Seq# %d%n", tag, p.seq, p.seq);

        if (p.seq == base) startTimer();
    }

    private void ackReceiver() {
        try {
            while (true) {
                int ack = in.readInt();             
                handleAck(ack);
            }
        } catch (IOException e) {
            System.err.println("[Client] Error receiving ACKs: " + e.getMessage());
        }
    }

    private synchronized void handleAck(int ack) {
        if (ack == 0) return;                     

        if (ack == lastAck) {
            dupAckCount++;
            System.out.printf("Received Duplicate ACK for Seq %d (dup=%d)%n", ack, dupAckCount);
            if (dupAckCount == 3) {
                System.out.println("Fast Retransmit Triggered for Packet " + base);
                try {
                    timeout = Math.min(timeout, MAX_TIMEOUT);
                    transmit(buffer.get(base), true);
                } catch (IOException ignored) {}
            }
            return;
        } else {
            dupAckCount = 0;
            lastAck = ack;
        }

        Packet p = buffer.get(ack);
        if (p != null) {
            long sample = System.currentTimeMillis() - p.sendTime;
            updateRtt(sample);
        }
        base = ack + 1;
        buffer.keySet().removeIf(seq -> seq <= ack);

        if (base == nextSeq) cancelTimer();         // window empty
        else startTimer();                          // restart for new base
    }

    private void updateRtt(long sample) {
        estRtt = (1 - ALPHA) * estRtt + ALPHA * sample;
        devRtt = (1 - BETA)  * devRtt + BETA * Math.abs(sample - estRtt);
        timeout = Math.min((long) (estRtt + 4 * devRtt), MAX_TIMEOUT);
        System.out.printf("ACK %d received. RTT=%dms  EstimatedRTT=%.2fms  DevRTT=%.2fms  Timeout=%dms%n", lastAck, sample, estRtt, devRtt, timeout);
    }

    private void startTimer() {
        cancelTimer();
        timerHandle = sched.schedule(() -> timeout(), timeout, TimeUnit.MILLISECONDS);
    }
    private void cancelTimer() {
        if (timerHandle != null) timerHandle.cancel(false);
    }
    private synchronized void timeout() {
        System.out.println("Timeout! Retransmitting Packet " + base);
        try {
            timeout *= 2;                              
            transmit(buffer.get(base), true);
        } catch (IOException e) {
            System.err.println("Retransmit failed: " + e.getMessage());
        }
    }

    private void connect() throws IOException {
        sock = new Socket(host, port);
        in   = new DataInputStream(sock.getInputStream());
        out  = new DataOutputStream(sock.getOutputStream());
        System.out.println("Client: Connected to server!");
        System.out.println("Server: " + in.readUTF());
    }
    private void sendFileName() throws IOException {
        out.writeUTF(file.getFileName().toString());
        out.flush();
    }

    private List<Packet> sliceFile() throws IOException {
        byte[] all = Files.readAllBytes(file);
        int seq = 1;
        List<Packet> list = new ArrayList<>();
        for (int pos = 0; pos < all.length; pos += CHUNK_SIZE, seq++) {
            int len = Math.min(CHUNK_SIZE, all.length - pos);
            byte[] b = Arrays.copyOfRange(all, pos, pos + len);
            Packet p = new Packet(seq, b);
            buffer.put(seq, p);                      
            list.add(p);
        }
        System.out.printf("Client: Prepared %d packets (%,d bytes)%n", list.size(), all.length);
        return list;
    }

    private void cleanup() throws IOException {
        cancelTimer();
        sched.shutdownNow();
        sock.close();
    }

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