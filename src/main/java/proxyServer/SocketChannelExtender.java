package proxyServer;


import java.io.IOException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;


public class SocketChannelExtender {
    private static AtomicLong d_idCounter = new AtomicLong(0);
    private long d_id;

    private SocketChannelExtender secondChannel;

    private SocketChannel channel;
    private RWSocketChannelBuffer readBuffer;  // what we read from this.channel and must write to secondChannel

    public SocketChannelExtender (SocketChannel channel) {
        d_id = d_idCounter.incrementAndGet();

        this.channel = channel;

        readBuffer = null;
    }

    public long d_getID() {
        return d_id;
    }

    public SocketChannelExtender getSecondChannel() {
        return secondChannel;
    }

    public void setSecondChannel(SocketChannelExtender secondChannel) {
        this.secondChannel = secondChannel;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public void close() {
        try {
            readBuffer = null;
            channel.close();
        } catch (Exception e) {
        }
        try {
            secondChannel.readBuffer = null;
            secondChannel.channel.close();
        } catch (Exception e) {
        }
    }

    public int read(RWSocketChannelBuffer workerBuffer)
    {
        RWSocketChannelBuffer usingBuffer = readBuffer != null ? readBuffer : workerBuffer;
        int result = 0;

        try {
            int read;
            int write;
            do {
                write = usingBuffer.write(secondChannel.channel);
                read = usingBuffer.read(channel);
                if (read == -1) {
                    throw new IOException();
                }
            } while (!usingBuffer.isEmpty() && read > 0 && write > 0);
        } catch (NotYetConnectedException e) {
        } catch (IOException e) {
            return ConnectionsWorker.RES_CLOSE_SOCKET;
        }

        if (!usingBuffer.isEmpty()) {
            if (usingBuffer == workerBuffer) {
                readBuffer = workerBuffer;
                result |= ConnectionsWorker.RES_ALLOCATE_BUFFER;
            }
        } else {
            if (usingBuffer == readBuffer) {
                readBuffer = null;
                result |= ConnectionsWorker.RES_WRITE_DATA_END;
            }
        }

        if (readBuffer != null) {
            result |= ConnectionsWorker.RES_WRITE_PAIR_SOCKET;
        }

        return result;
    }

    public int write()
    {
        int result = 0;

        try {
            if (secondChannel.readBuffer != null) {
                secondChannel.readBuffer.write(channel);
                if (secondChannel.readBuffer.isEmpty()) {
                    secondChannel.readBuffer = null;
                    result |= ConnectionsWorker.RES_WRITE_DATA_END;
                }
            }
        } catch (IOException e) {
            return ConnectionsWorker.RES_CLOSE_SOCKET;
        }

        return result;
    }

}
