package ProxyServer;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class SocketChannelExtender {
    private ConnectionsAccepter connectionsAccepter;
    private SocketChannelExtender secondChannel;

    private SocketChannel channel;
    private RWSocketChannelBuffer readBuffer;  // what we read from this.channel and must write to secondChannel

    private AtomicInteger rwState;

    public SocketChannelExtender (ConnectionsAccepter connectionsAccepter, SocketChannel channel) {
        this.connectionsAccepter = connectionsAccepter;
        this.channel = channel;

        readBuffer = null;

        rwState = new AtomicInteger(0);
    }

    public void setSecondChannel(SocketChannelExtender secondChannel) {
        this.secondChannel = secondChannel;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public SocketChannel getSecondChannel() {
        return secondChannel.channel;
    }

    public void setRWState(int value) {
        rwState.set(value);
    }

    public int exec(RWSocketChannelBuffer workerBuffer) {
        int result = 0;
        int rwState = this.rwState.get();

        try {
            if ((rwState & SelectionKey.OP_READ) != 0) {
                this.rwState.set(rwState & ~SelectionKey.OP_READ);
                RWSocketChannelBuffer usingBuffer = readBuffer != null ? readBuffer : workerBuffer;
                result |= readWriteCycle(usingBuffer, workerBuffer);
            }

            if ((rwState & SelectionKey.OP_WRITE) != 0) {
                this.rwState.set(rwState & ~SelectionKey.OP_WRITE);
                if (secondChannel.readBuffer != null) {
                    result |= secondChannel.readWriteCycle(secondChannel.readBuffer, null);
                }
            }
        } catch (NotYetConnectedException e) {
        } catch (IOException e) {
            close();
            return ConnectionsWorker.RES_REMOVE_SOCKETS;
        }

        return result;
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

        connectionsAccepter.removeSocketChannel(this);
    }

    private int readWriteCycle(RWSocketChannelBuffer usingBuffer, RWSocketChannelBuffer workerBuffer)
            throws IOException
    {
        int result = 0;

        int read;
        int write;
        do {
            write = usingBuffer.write(secondChannel.channel);
            read = usingBuffer.read(channel);
            if (read == -1) {
                throw new IOException();
            }
        } while (usingBuffer.canWrite() && read > 0 && write > 0);

        if (usingBuffer.canWrite()) {
            if (usingBuffer == workerBuffer) {
                readBuffer = workerBuffer;
                result |= ConnectionsWorker.RES_ALLOCATE_BUFFER;
            }
        } else {
            if (usingBuffer == readBuffer) {
                readBuffer = null;
            }
        }

        return result;
    }

}
