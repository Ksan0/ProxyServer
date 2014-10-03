package proxyServer;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class RWSocketChannelBuffer {

    private ByteBuffer buffer;
    private int rPos;
    private int wPos;
    private int dataLen;

    public RWSocketChannelBuffer(int size) {
        buffer = ByteBuffer.allocate(size);
        rPos = 0;
        wPos = 0;
        dataLen = 0;
    }

    public void clear() {
        buffer.clear();
        rPos = 0;
        wPos = 0;
        dataLen = 0;
    }

    public int read(SocketChannel channel)
            throws IOException
    {
        buffer.limit(buffer.capacity());
        buffer.position(rPos);

        int read = 0;
        if (buffer.remaining() > 0) {
            read = channel.read(buffer);
            if (read != -1) {
                dataLen += read;
                rPos = buffer.position();
            }
        }

        return read;
    }

    public int write(SocketChannel channel)
            throws IOException
    {
        buffer.limit(wPos + dataLen);
        buffer.position(wPos);

        int write = 0;
        if (buffer.remaining() > 0) {
            write = channel.write(buffer);
            dataLen -= write;
            wPos = buffer.position();
        }

        if (buffer.remaining() <= 0) {
            clear();
        }

        return write;
    }

    public boolean canWrite() {
        return dataLen > 0;
    }
}
