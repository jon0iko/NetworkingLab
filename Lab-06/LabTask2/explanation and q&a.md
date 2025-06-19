# Reliable Data Transfer (RDT) Implementation Explanation

This document explains how the key features of Reliable Data Transfer (RDT), as specified in the lab manual and implemented in `Client.java` and `Server.java`, work.

## Implemented RDT Features:

### 1. Timer and SampleRTT Calculation (Client-side)

The client needs to measure the Round Trip Time (RTT) for each acknowledged packet to dynamically adjust its retransmission timeout.

- **Starting Timer**: When a packet is sent, the client records the current system time.
    - In `Client.java`:
        
        ```java
        // ...existing code...
        // Create and send packet
        Packet pkt = new Packet(sequenceNumber, data, bytesRead);
        dataOut.writeInt(pkt.seqNum);
        dataOut.writeInt(pkt.length);
        dataOut.write(pkt.data, 0, pkt.length);
        dataOut.flush();
        
        // Record send time for SampleRTT measurement
        pkt.sendTime = System.currentTimeMillis();
        sendTimes.put(pkt.seqNum, pkt.sendTime);
        buffer.put(pkt.seqNum, pkt); // Save in buffer for potential retransmission
        // ...existing code...
        
        ```
        
- **Calculating SampleRTT**: When an ACK is received for a packet, the `SampleRTT` is calculated as the difference between the current time and the recorded send time of that packet.
    - In `Client.java`:
        
        ```java
        // ...existing code...
        if (ack == sequenceNumber) {
            // ACK matches expected one - update timers and sequence progress
            long sampleRTT = System.currentTimeMillis() - sendTimes.get(ack);
        // ...existing code...
        
        ```
        

### 2. EWMA (Exponential Weighted Moving Average) for Timeout Calculation (Client-side)

The client uses EWMA to smooth out RTT measurements and calculate a robust retransmission timeout interval (`TimeoutInterval`).

- **EWMA Equations**:
    - `EstimatedRTT = (1 - α) * EstimatedRTT + α * SampleRTT`
    - `DevRTT = (1 - β) * DevRTT + β * |SampleRTT - EstimatedRTT|`
    - `TimeoutInterval = EstimatedRTT + 4 * DevRTT`
- **Implementation**:
    - Constants `ALPHA` (α) and `BETA` (β) are defined.
    - `estimatedRTT` and `devRTT` are updated upon receiving an ACK.
    - A `MIN_TIMEOUT` is enforced to prevent the timeout from becoming too small.
    - The socket's `SO_TIMEOUT` is set to this `timeoutInterval`.
    - In `Client.java`:
        
        ```java
        // ...existing code...
        private static final double ALPHA = 0.125;
        private static final double BETA = 0.25;
        private static final int INITIAL_TIMEOUT = 1000; // ms
        private static final int MIN_TIMEOUT = 100; // Minimum timeout value
        // ...existing code...
        // Measure SampleRTT and update timeout using EWMA
        if (estimatedRTT == 0) {
            estimatedRTT = sampleRTT;
        } else {
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
        }
        devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
        timeoutInterval = estimatedRTT + 4 * devRTT;
        
        // Enforce minimum timeout to prevent near-zero values
        if (timeoutInterval < MIN_TIMEOUT) {
            timeoutInterval = MIN_TIMEOUT;
        }
        
        System.out.printf("ACK %d received. RTT = %dms. EstimatedRTT = %.0fms, DevRTT = %.2fms, Timeout = %.0fms\\n",
            ack, sampleRTT, estimatedRTT, devRTT, timeoutInterval);
        // ...existing code...
        // Set socket timeout for retransmission
        socket.setSoTimeout((int)timeoutInterval);
        // ...existing code...
        
        ```
        
- **Timeout Event**: If an ACK is not received within `timeoutInterval`, a `SocketTimeoutException` is caught, and the packet is retransmitted.
    - In `Client.java`:
        
        ```java
        // ...existing code...
        } catch (SocketTimeoutException e) {
            // Timeout - retransmit the current packet
            System.out.println("Timeout! Retransmitting Packet " + sequenceNumber);
            Packet resendPkt = buffer.get(sequenceNumber);
            if (resendPkt != null) {
                dataOut.writeInt(resendPkt.seqNum);
                dataOut.writeInt(resendPkt.length);
                dataOut.write(resendPkt.data, 0, resendPkt.length);
                dataOut.flush();
                resendPkt.sendTime = System.currentTimeMillis();
                sendTimes.put(resendPkt.seqNum, resendPkt.sendTime);
            }
        }
        // ...existing code...
        
        ```
        

### 3. Cumulative Acknowledgment (Server-side)

The server acknowledges the highest sequence number of an in-order packet it has received. This implies that all packets with sequence numbers up to the acknowledged number have been received.

- **Tracking Expected Sequence Number**: The server maintains `expectedSeqNum`.
- **In-order Packet**: If the received `seqNum` matches `expectedSeqNum`, the server processes the packet, sends an ACK for `seqNum`, and increments `expectedSeqNum`. `lastAck` is updated to this `seqNum`.
    - In `server.java`:
        
        ```java
        // ...existing code...
        int lastAck = 0;
        int expectedSeqNum = 1; // Start from 1 to match client
        // ...existing code...
        if (seqNum == expectedSeqNum) {
            // Check if the packet sequence number is expected
            fileOut.write(data, 0, length);
            fileOut.flush();
            bytesReceived += length;
            received[seqNum] = true;
        
            System.out.println("Received Packet " + seqNum + ". Sending ACK " + seqNum);
        
            // Update expected sequence number
            expectedSeqNum++;
        
            // Sending ACK – cumulative ACK up to highest in-order packet
            dataOut.writeInt(seqNum); // ACK number
            dataOut.writeInt(rwnd - bytesReceived); // Window size
            dataOut.flush();
        
            lastAck = seqNum;
        // ...existing code...
        
        ```
        
- **Out-of-order Packet**: If a packet arrives with `seqNum > expectedSeqNum`, it means a preceding packet was lost. The server sends a duplicate ACK for `lastAck` (the last in-order packet received).
    - In `server.java`:
        
        ```java
        // ...existing code...
        } else {
            // Out-of-order packet - send duplicate ACK
            if (seqNum > expectedSeqNum) {
                System.out.println("-- Packet " + expectedSeqNum + " not received --");
            }
            System.out.println("Received Packet " + seqNum + ". Sending Duplicate ACK " + lastAck);
            dataOut.writeInt(lastAck);
            dataOut.writeInt(rwnd - bytesReceived);
            dataOut.flush();
        }
        // ...existing code...
        
        ```
        
- **Duplicate of Already Received Packet**: If `seqNum < expectedSeqNum` and the packet was already marked as `received[seqNum]`, it's a retransmitted packet that has already been processed. The server re-sends an ACK for `seqNum`.
    - In `server.java`:
        
        ```java
        // ...existing code...
        } else if (seqNum < expectedSeqNum && received[seqNum]) {
            // Retransmitted packet (already received)
            System.out.println("Received Packet " + seqNum + " (retransmitted). Sending ACK " + seqNum);
            dataOut.writeInt(seqNum);
            dataOut.writeInt(rwnd - bytesReceived);
            dataOut.flush();
        // ...existing code...
        
        ```
        

### 4. Fast Retransmit (Client-side)

The client retransmits a presumably lost packet if it receives three duplicate ACKs for the same sequence number, without waiting for a timeout.

- **Counting Duplicate ACKs**: The client tracks `duplicateAckCount`. An ACK is considered a duplicate if `ack == lastAck` (where `lastAck` is the highest ACK received that was not a duplicate).
- **Triggering Fast Retransmit**: If `duplicateAckCount` reaches 3, the client immediately retransmits the packet it expects an ACK for (i.e., `sequenceNumber`, which is the one after `lastAck`).

The packet to be retransmitted is fetched from the `buffer`.
    - In `Client.java`:
        
        ```java
        // ...existing code...
        } else if (ack == lastAck) {
            // Duplicate ACK received
            duplicateAckCount++;
            System.out.println("Received Duplicate ACK for Seq " + ack);
        
            if (duplicateAckCount == 3) {
                // Fast Retransmit after 3 duplicate ACKs
                System.out.println("Fast Retransmit Triggered for Packet " + sequenceNumber);
                Packet resendPkt = buffer.get(sequenceNumber);
                if (resendPkt != null) {
                    dataOut.writeInt(resendPkt.seqNum);
                    dataOut.writeInt(resendPkt.length);
                    dataOut.write(resendPkt.data, 0, resendPkt.length);
                    dataOut.flush();
                    resendPkt.sendTime = System.currentTimeMillis(); // Reset send time for RTT calc if this ACKed
                    sendTimes.put(resendPkt.seqNum, resendPkt.sendTime);
                    System.out.println("Resending Packet " + resendPkt.seqNum);
                }
                duplicateAckCount = 0; // Reset count after retransmission
            }
        }
        // ...existing code...
        
        ```
        

### 5. Packet Loss Simulation (Server-side)

The server simulates network packet loss to test the RDT mechanisms.

- **Random Loss**: A packet received by the server has a probability `p` (e.g., 0.1 or 10%) of being "lost".
- **Implementation**: If `rand.nextDouble() < p`, the server prints a message indicating packet loss and `continue`s the loop, effectively dropping the packet and not sending an ACK.
    - In `server.java`:
        
        ```java
        // ...existing code...
        Random rand = new Random();
        double p = 0.1; // Probability of packet loss (10%)
        // ...existing code...
        // Randomly simulate packet loss with probability p
        if (rand.nextDouble() < p) {
            System.out.println("-- Packet " + seqNum + " lost during transmission --");
            // Drop packet (do not send ACK)
            continue;
        }
        // ...existing code...
        
        ```
        

---

# Q&A for RDT Implementation Viva

Here are some potential questions a senior networking course teacher might ask about this RDT implementation, designed to test your understanding:

**Q1: In `Client.java`, you're using EWMA to calculate `timeoutInterval`. What is the role of `ALPHA` and `BETA`? What would happen if `ALPHA` was set to 1? What if `BETA` was 0?A1:**

- `ALPHA` (for `EstimatedRTT`): This is the smoothing factor for the RTT estimation. It determines how much weight is given to the most recent `SampleRTT` versus the historical `EstimatedRTT`. A value closer to 1 makes the `EstimatedRTT` respond very quickly to changes in `SampleRTT` (less smoothing). A value closer to 0 makes it respond slowly (more smoothing).
- `BETA` (for `DevRTT`): This is the smoothing factor for the RTT deviation. It determines how much weight is given to the most recent difference between `SampleRTT` and `EstimatedRTT` versus the historical `DevRTT`.
- If `ALPHA` was 1: `EstimatedRTT = (1 - 1) * EstimatedRTT + 1 * SampleRTT = SampleRTT`. The `EstimatedRTT` would simply be the latest `SampleRTT`, with no memory of past RTTs. This could make the timeout too volatile.
- If `BETA` was 0: `DevRTT = (1 - 0) * DevRTT + 0 * Math.abs(sampleRTT - estimatedRTT) = DevRTT`. The `DevRTT` would never change from its initial value (or the first calculated deviation if it starts at 0). This would make the timeout interval less adaptive to changes in RTT variance.

**Q2: Why is `DevRTT` included in the `TimeoutInterval` calculation (`EstimatedRTT + 4 * DevRTT`)? Why multiply `DevRTT` by 4?A2:**

- `DevRTT` (Deviation RTT) measures the variability or jitter in RTTs. Including it in the `TimeoutInterval` makes the timeout more robust. If RTTs are highly variable, `DevRTT` will be larger, leading to a larger timeout, thus reducing the chance of premature timeouts. If RTTs are stable, `DevRTT` will be small, allowing for a tighter, more efficient timeout.
- Multiplying `DevRTT` by 4 is a common practice (recommended in RFC 6298 for TCP RTO calculation). It provides a safety margin. The idea is that most RTT samples will fall within `EstimatedRTT ± k * DevRTT`. Using `k=4` makes the timeout interval large enough to accommodate most RTT fluctuations, reducing spurious retransmissions.

**Q3: The client code has a `MIN_TIMEOUT`. What's its purpose? What could go wrong if it wasn't there or was set too low?A3:**

- Purpose: `MIN_TIMEOUT` ensures that the `timeoutInterval` doesn't become excessively small, especially during periods of very low and stable RTTs or if `DevRTT` becomes very small.
- If not there or too low: If the calculated `timeoutInterval` becomes extremely small (e.g., a few milliseconds), it could lead to a high number of premature timeouts and unnecessary retransmissions, especially on networks with even slight processing delays or if an ACK is only marginally delayed. This would waste bandwidth and reduce efficiency.

**Q4: Explain the difference between a retransmission triggered by a timeout and a retransmission triggered by Fast Retransmit. When is each one beneficial?A4:**

- **Timeout-based Retransmission**: Occurs when the client sends a packet and does not receive an ACK for it within the calculated `timeoutInterval`. This is a fallback mechanism that handles cases where a packet (or its ACK) is lost and no further information (like duplicate ACKs) is received. It's essential for ensuring reliability when packets are significantly delayed or multiple packets in a window are lost.
- **Fast Retransmit**: Occurs when the client receives three duplicate ACKs for the same sequence number. Duplicate ACKs indicate that the receiver has received out-of-order packets, strongly suggesting that a specific packet *before* the out-of-order ones was lost.
- **Benefits**:
    - Fast Retransmit is beneficial because it allows the sender to infer packet loss and retransmit *before* the timeout timer expires. This can significantly improve performance, especially in networks with moderate packet loss but relatively long RTTs (where waiting for a timeout would cause a long stall).
    - Timeout-based retransmission is crucial for handling cases where Fast Retransmit cannot be triggered (e.g., if all packets in a window are lost, or if ACKs themselves are lost, preventing the accumulation of duplicate ACKs).

**Q5: How does the server's cumulative ACK mechanism work, and why is it useful? For example, if the server receives Packet 1, then Packet 3 (Packet 2 is lost), what ACKs does it send?A5:**

- **Mechanism**: The server sends an ACK for the highest sequence number of an *in-order* packet it has received. So, if it expects packet `N` and receives packet `N`, it ACKs `N`. If it then receives packet `N+2` (meaning `N+1` is missing), it will send a *duplicate* ACK for `N`.
- **Usefulness**: Cumulative ACKs are efficient. A single ACK can acknowledge multiple packets. If the client receives an ACK for sequence number `X`, it knows that the server has received all packets up to and including `X`. This reduces the number of ACKs that need to be sent and processed.
- **Example**:
    1. Server expects Packet 1. Receives Packet 1.
        - Server sends `ACK 1`. `lastAck` becomes 1. `expectedSeqNum` becomes 2.
    2. Server expects Packet 2. Packet 2 is lost. Server receives Packet 3.
        - Packet 3 (`seqNum=3`) is not `expectedSeqNum` (which is 2).
        - Server sends a *duplicate* `ACK 1` (value of `lastAck`). `expectedSeqNum` remains 2.

**Q6: In `Client.java`, there's a `buffer` (a `Map<Integer, Packet>`). What is its purpose in this RDT implementation?A6:**

- The `buffer` stores packets that have been sent but not yet acknowledged. Its purpose is to enable retransmissions.
- If a timeout occurs for `sequenceNumber`, the client needs to resend that specific packet. It retrieves `Packet resendPkt = buffer.get(sequenceNumber)` and sends it again.
- Similarly, for Fast Retransmit, when three duplicate ACKs are received, the client needs to resend `sequenceNumber` (the packet presumed lost). It retrieves this packet from the `buffer`.
- Once a packet is successfully acknowledged (`ack == sequenceNumber`), it's removed from the buffer (`buffer.remove(ack)`).

**Q7: What happens if an ACK sent by the server is lost? How does your client RDT implementation handle this scenario?A7:**

- If an ACK sent by the server is lost, the client will not receive it.
- The client has sent a packet and started a timer (via `socket.setSoTimeout((int)timeoutInterval)`).
- Since the ACK is lost, the client will continue to wait. Eventually, the `timeoutInterval` will expire.
- A `SocketTimeoutException` will be caught by the client.
- The client will then assume the original packet (or its ACK) was lost and will retransmit the packet from its `buffer`.
- So, lost ACKs are handled by the client's timeout mechanism.

**Q8: In `server.java`, inside the `ClientHandler.run()` method, there's this logic: `if (rwnd - bytesReceived < rwnd / 2) { bytesReceived = 0; ... }`. What is the apparent purpose of this block, and how does it relate to the overall data transfer?A8:**

- This block appears to be a simplified simulation of how a receiver might manage its receive buffer and advertise its receive window (`rwnd`).
- `bytesReceived` seems to track the amount of data written to the file *since the last window update or reset*.
- The condition `rwnd - bytesReceived < rwnd / 2` checks if the available space in the current conceptual receive window (which is `rwnd` at the start of a cycle) has fallen below half its capacity.
- If it has, `bytesReceived` is reset to 0. This simulates the server processing the buffered data (e.g., writing it to disk and freeing up buffer space) and thus being ready to receive more data, effectively "resetting" its available window for the client for the *next* packet it ACKs.
- When the server sends an ACK, it also sends `rwnd - bytesReceived` as the current available window size. So, after this reset, it would advertise a larger window again.
- This is more related to flow control (managing how much data the client sends to avoid overwhelming the server's buffer) than to the core RDT mechanisms of error detection and retransmission, though they operate concurrently. The lab manual mentions "Set the Receive Buffer Size to simulate the Receive Window" for the server.

**Q9: In `Client.java`, why is the `acked` boolean flag used within a `while (!acked)` loop when waiting for an ACK? Could this be implemented differently?A9:**

- The `while (!acked)` loop ensures that the client continues to attempt to receive an ACK (and retransmit if necessary) for the *current* `sequenceNumber` until that specific packet is successfully acknowledged.
- Inside this loop:
    - It sets a socket timeout.
    - It tries to read an ACK.
    - If the correct ACK is received (`ack == sequenceNumber`), `acked` is set to `true`, and the loop terminates, allowing the client to proceed to send the next packet.
    - If a duplicate ACK is received, `acked` remains `false`, and the loop continues (potentially triggering fast retransmit).
    - If a timeout occurs, `acked` remains `false`, the packet is retransmitted, and the loop continues, waiting for an ACK for the retransmitted packet.
- **Alternative Implementation**: One could potentially restructure this. For instance, the main sending loop could iterate, and the ACK handling logic (including timeout and fast retransmit) could be encapsulated in a function that returns whether the current packet was successfully ACKed. However, the `while(!acked)` loop is a clear way to express "keep trying for this packet until it's confirmed." The current structure is quite standard for stop-and-wait or go-back-N style logic for a single packet's confirmation.

**Q10: The client sends `dataOut.writeInt(sequenceNumber); dataOut.writeInt(-1);` to signal the end of the file. Why is `-1` chosen as the length? What's the server expecting?A10:**

- `1` is chosen as a special signal value for the length to indicate to the server that there is no more actual file data being sent for the current file transfer and that this is the end-of-file (EOF) marker packet.
- The server, in its packet reading loop, checks `if (length == -1) { break; }`.
    
    ```java
    // filepath: e:\\Lab-06\\LabTask2\\server.java
    // ...existing code...
    int seqNum = dataIn.readInt();
    int length = dataIn.readInt();
    if (length == -1) {
        break; // Exit the loop for receiving packets for this file
    }
    // ...existing code...
    
    ```
    
- This allows the server to cleanly terminate the loop responsible for reading packet data for the current file and proceed to finalize the file reception (e.g., close `fileOut` and send a completion message). The `sequenceNumber` is still sent with this EOF marker, likely so the server can acknowledge it if needed, ensuring the client knows the EOF signal was received.

**Q11: If you were to improve this RDT implementation, what's one feature from a more advanced RDT protocol (like TCP's RDT) you might consider adding and why?A11:**

- One significant improvement would be to implement a **sliding window protocol (like Go-Back-N or Selective Repeat)** on the client side, instead of what appears to be a stop-and-wait like behavior for each packet's ACK before sending the next (though it does read `rwnd` from the server, the ACK wait loop `while(!acked)` processes one packet at a time before `sequenceNumber++`).
- **Why**: A sliding window allows the sender to send multiple packets (`N` packets, the window size) before waiting for an acknowledgment. This can significantly improve throughput, especially on networks with high latency (long RTTs). With the current approach, the channel is idle while the client waits for an ACK for each single packet. A sliding window would keep the "pipe" fuller, leading to more efficient use of network bandwidth. Implementing Selective Repeat would be even more efficient than Go-Back-N in scenarios with multiple packet losses within a window, as it allows the receiver to buffer out-of-order packets and only requests retransmission of actually lost packets.

**Q12: The server code has a `received` boolean array: `boolean[] received = new boolean[10000];`. What is its primary role, and are there any limitations to this approach?A12:**

- **Primary Role**: The `received` array is used by the server to keep track of which specific packets (by sequence number) have already been successfully received and processed. This is crucial for correctly handling retransmitted packets. If a packet arrives with `seqNum < expectedSeqNum` (meaning it's an older packet), the server checks `received[seqNum]`. If `true`, it knows this is a duplicate of an already processed packet (likely due to a lost ACK for it earlier), so it can just re-send the ACK for `seqNum` without reprocessing the data.
- **Limitations**:
    - **Fixed Size**: The array has a fixed size (10000). If a file transfer involves more than 10,000 packets, an `ArrayIndexOutOfBoundsException` could occur, or sequence numbers could wrap around and lead to incorrect behavior if not handled (though sequence numbers typically wrap at a much larger value in full TCP). For this lab task, 10000 might be sufficient, but it's not a general solution.
    - **Memory**: For very large sequence number spaces, a boolean array might consume a lot of memory if not all sequence numbers are used or if the range is vast. More dynamic data structures (like a hash map or a sliding window bitmap) are used in more complex implementations.
    - **Sequence Number Wrapping**: If sequence numbers were to wrap around (e.g., after reaching the maximum value and restarting from 0), this simple boolean array would not handle it correctly without additional logic.

Good luck with your viva!