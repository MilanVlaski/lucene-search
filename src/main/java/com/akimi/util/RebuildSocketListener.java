package com.akimi.util;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RebuildSocketListener implements Runnable {

    private final Path socketPath;
    private final String targetCommand;
    private final Runnable rebuildAction;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(); // Background worker pool
    private volatile boolean running = true;

    public RebuildSocketListener(String socketPathStr, String targetCommand, Runnable rebuildAction) {
        this.socketPath = Path.of(socketPathStr);
        this.targetCommand = targetCommand;
        this.rebuildAction = rebuildAction;
    }

    @Override
    public void run() {
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            System.err.println("[SocketListener] Failed to clean up legacy socket: " + e.getMessage());
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(address);
            // Here is where we would set the group on the socket file, to
            // allow access by users
            System.out.println("[SocketListener] Service listening on " + socketPath);

            while (running) {
                // Notice the client channel is opened inside a nested try-block
                try (SocketChannel clientChannel = serverChannel.accept()) {
                    ByteBuffer buffer = ByteBuffer.allocate(128);
                    int bytesRead = clientChannel.read(buffer);

                    if (bytesRead > 0) {
                        buffer.flip();
                        String command = StandardCharsets.UTF_8.decode(buffer).toString().trim();

                        if (targetCommand.equalsIgnoreCase(command)) {
                            // 1. Instantly respond to the client
                            writeResponse(clientChannel, "ACK: Command received. Closing connection and executing rebuild.\n");

                            // 2. Explicitly force close the client connection right now
                            clientChannel.close();

                            // 3. Hand the actual work to the executor thread pool to run asynchronously
                            executor.submit(rebuildAction);
                        } else {
                            writeResponse(clientChannel, "ERROR: Unknown command\n");
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[SocketListener] Processing error: " + e.getMessage());
                    }
                }
                // The clientChannel auto-closes here at the end of the block if it wasn't closed manually
            }
        } catch (IOException e) {
            System.err.println("[SocketListener] Critical socket failure: " + e.getMessage());
        } finally {
            executor.shutdown(); // Clean up thread pool on shutdown
        }
    }

    private void writeResponse(SocketChannel channel, String message) throws IOException {
        if (channel.isOpen()) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            channel.write(buffer);
        }
    }

    public void stop() {
        this.running = false;
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {}
        executor.shutdownNow();
    }
}
