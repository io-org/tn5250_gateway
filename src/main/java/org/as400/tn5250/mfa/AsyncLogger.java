package org.as400.tn5250.mfa;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AsyncLogger implements AutoCloseable {
    private static final String POISON_PILL = "__SHUTDOWN__";
    
    private final BlockingQueue<String> queue;
    private final BufferedWriter writer;
    private final Thread workerThread;

    public AsyncLogger(String filePath, int maxQueueCapacity) throws IOException {
        Path path = Path.of(filePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        
        this.writer = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
        
        // Bounded queue prevents OutOfMemoryError if disk I/O stalls
        this.queue = new LinkedBlockingQueue<>(maxQueueCapacity);
        
        this.workerThread = new Thread(this::processQueue, "audit-log-writer");
        this.workerThread.start();
    }

    // Called by your gateway threads (non-blocking / thread-safe)
    public boolean log(String s) {
        // offer() returns false immediately if the queue is full instead of freezing your gateway
        boolean added = queue.offer(s);
        if (!added) {
            System.err.println("CRITICAL: Log queue full, dropped event: " + s);
        }
        return added;
    }

    // Single background consumer thread (naturally thread-safe for file writing)
    private void processQueue() {
        try {
            while (true) {
                // Waits efficiently until a log arrives
                String line = queue.take();
                if (POISON_PILL.equals(line)) {
                    break;
                }
                
                writer.write(line);
                writer.newLine();
                
                // Batch-flush optimization: only flush when the queue is momentarily empty
                if (queue.isEmpty()) {
                    writer.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("Failed to write audit log: " + e.getMessage());
        } finally {
            closeWriter();
        }
    }

    private void closeWriter() {
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to close audit log writer: " + e.getMessage());
        }
    }

    // Call this during application shutdown (e.g., via Runtime.getRuntime().addShutdownHook)
    @Override
    public void close() throws InterruptedException {
        queue.offer(POISON_PILL, 5, TimeUnit.SECONDS);
        workerThread.join(5000); // Wait up to 5s for remaining logs to flush to disk
    }
}

