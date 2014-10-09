package proxyServer;


import java.io.IOException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class SocketChannelExtender {
    private ConnectionsWorker connectionsWorker;
    private SocketChannelExtender secondChannel;

    private SocketChannel channel;
    private RWSocketChannelBuffer readBuffer;  // what we read from this.channel and must write to secondChannel

    public SocketChannelExtender (ConnectionsWorker connectionsWorker, SocketChannel channel) {
        this.connectionsWorker = connectionsWorker;
        this.channel = channel;

        readBuffer = null;
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

    public int exec(RWSocketChannelBuffer workerBuffer) {
        int result = 0;

        try {

            if ((rwState & SelectionKey.OP_READ) != 0) {
                this.rwState.set(rwState & ~SelectionKey.OP_READ);

                result |= read(usingBuffer, workerBuffer);

                SelectionKey secondKey = secondChannel.channel.keyFor(connectionsAccepter.getSelector());
                if (secondKey != null) {
                    if (readBuffer != null) {
                        secondKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    } else {
                        secondKey.interestOps(SelectionKey.OP_READ);
                    }
                }

            }

            if ((rwState & SelectionKey.OP_WRITE) != 0) {
                this.rwState.set(rwState & ~SelectionKey.OP_WRITE);
                result |= write();

                SelectionKey key = channel.keyFor(connectionsAccepter.getSelector());
                if (key != null) {
                    if (secondChannel.readBuffer == null) {
                        key.interestOps(SelectionKey.OP_READ);
                    }
                }
            }

        } catch (NotYetConnectedException e) {
        } catch (IOException e) {
            close();
            return ConnectionsWorker.RES_REMOVED_SOCKET;
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
        connectionsWorker.removeSocket(this);
    }

    private int read(RWSocketChannelBuffer workerBuffer)
            throws IOException
    {
        RWSocketChannelBuffer usingBuffer = readBuffer != null ? readBuffer : workerBuffer;
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

    private void write()
            throws IOException
    {
        if (secondChannel.readBuffer != null) {
            secondChannel.readBuffer.write(channel);
            if (!secondChannel.readBuffer.canWrite()) {
                secondChannel.readBuffer = null;
            }
        }
    }

}
