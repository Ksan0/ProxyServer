package ProxyServer;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;


public class SocketChannelExtender {
    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    private ConnectionsAccepter connectionsAccepter;
    private SocketChannelExtender secondChannel;

    private SocketChannel channel;
    private ByteBuffer readBuffer;  // what we read from this.channel
    private ByteBuffer writeBuffer;  // what we must write to this.channel

    private AtomicInteger rwState;

    public SocketChannelExtender (ConnectionsAccepter connectionsAccepter, SocketChannel channel) {
        this.connectionsAccepter = connectionsAccepter;
        this.channel = channel;

        readBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        writeBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        writeBuffer.limit(0);

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

    public boolean exec() {
        int rwState = this.rwState.get();

        try {
            if ((rwState & SelectionKey.OP_READ) != 0) {
                int read = channel.read(readBuffer);
                if (read == -1) {
                    close();
                    return false;
                }

                if (this.readBuffer.remaining() > 0 && secondChannel.writeBuffer.remaining() <= 0)
                {
                    secondChannel.writeBufferUpdate();
                    secondChannel.channel.write(secondChannel.writeBuffer);
                }
            }
            if ((rwState & SelectionKey.OP_WRITE) != 0) {
                channel.write(writeBuffer);

                if (this.writeBuffer.remaining() <= 0) {
                    writeBufferUpdate();
                }
            }
        } catch (NotYetConnectedException e) {
        } catch (IOException e) {
            close();
            return false;
        }

        this.rwState.set(0);
        return true;
    }

    public void close() {
        try {
            channel.close();
        } catch (Exception e) {
        }
        try {
            secondChannel.channel.close();
        } catch (Exception e) {
        }

        connectionsAccepter.removeSocketChannel(this);
    }

    private void writeBufferUpdate() {
        ByteBuffer tmp = writeBuffer;

        writeBuffer = secondChannel.readBuffer;
        writeBuffer.flip();

        tmp.position(0);
        tmp.limit(tmp.capacity());
        secondChannel.readBuffer = tmp;
    }
}
