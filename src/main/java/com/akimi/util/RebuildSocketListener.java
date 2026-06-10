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

public class RebuildSocketListener implements Runnable {

    private final Path socketPath;
    private final String targetCommand;
    private final Runnable rebuildAction;

    public RebuildSocketListener(String socketPathStr, String targetCommand, Runnable rebuildAction) {
        this.socketPath = Path.of(socketPathStr);
        this.targetCommand = targetCommand;
        this.rebuildAction = rebuildAction;
    }

    @Override
    public void run() {
        try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(address);
            System.out.println("[SocketListener] Service listening on " + socketPath);

            while (true) {
                try (SocketChannel clientChannel = serverChannel.accept()) {
                    ByteBuffer buffer = ByteBuffer.allocate(128);
                    int bytesRead = clientChannel.read(buffer);

                    if (bytesRead > 0) {
                        buffer.flip(); // Prepare buffer for reading out the command
                        String command = StandardCharsets.UTF_8.decode(buffer).toString().trim();

                        if (targetCommand.equalsIgnoreCase(command)) {
                            writeResponse(clientChannel, "ACK: Executing rebuild.\n");
                            rebuildAction.run(); // Synchronous execution on this virtual thread
                        } else {
                            writeResponse(clientChannel, "ERROR: Unknown command\n");
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[SocketListener] Connection error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[SocketListener] Critical failure: " + e.getMessage());
        }
    }

    private void writeResponse(SocketChannel channel, String message) throws IOException {
        if (channel.isOpen()) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            channel.write(buffer);
        }
    }
}
