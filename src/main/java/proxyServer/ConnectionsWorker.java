package proxyServer;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {

    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_CLOSE_SOCKET        = 1 << 1;
    public static final int RES_ALLOCATE_BUFFER     = 1 << 2;
    public static final int RES_WRITE_PAIR_SOCKET   = 1 << 3;
    public static final int RES_WRITE_DATA_END      = 1 << 4;

    private AtomicLong lastCycleRunTime;
    private ConcurrentHashMap<SocketChannel, SocketChannelExtender> sockets;

    private Selector selector;
    private RWSocketChannelBuffer readBuffer;

    public ConnectionsWorker()
            throws IOException
    {
        lastCycleRunTime = new AtomicLong(0);
        sockets = new ConcurrentHashMap<>();
        selector = SelectorProvider.provider().openSelector();
        readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
    }


    public long getLastCycleRunMSTime() {
        return lastCycleRunTime.get();
    }


    public void addSocketPair(SocketChannelExtender clientSocketChannel, SocketChannelExtender proxySocketChannel, boolean proxyNeedFinishConnection)
            throws IOException
    {
        selector.wakeup();

        clientSocketChannel.getChannel().register(selector, SelectionKey.OP_READ);
        if (proxyNeedFinishConnection) {
            proxySocketChannel.getChannel().register(selector, SelectionKey.OP_CONNECT);
        } else {
            proxySocketChannel.getChannel().register(selector, SelectionKey.OP_READ);
        }

        sockets.put(clientSocketChannel.getChannel(), clientSocketChannel);
        sockets.put(proxySocketChannel.getChannel(), proxySocketChannel);
    }


    @Override
    public void run() {
        System.out.println("ConnectionWorker run in thread " + Thread.currentThread().getId());

        try {
            while (true) {
                selector.select();
                long timeCycleBegin = (new Date()).getTime();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                for (SelectionKey key: selectedKeys) {
                    try {
                        if (!key.isValid()) {
                            throw new CancelledKeyException();
                        }

                        SocketChannelExtender socketChannelExtender = sockets.get(key.channel());

                        if (socketChannelExtender != null) {
                            if (key.isConnectable()) {
                                finishConnect(key);
                            }
                            if (key.isReadable()) {
                                read(socketChannelExtender);
                            }
                            if (key.isWritable()) {
                                write(socketChannelExtender, key);
                            }
                        }
                    } catch (CancelledKeyException | IOException e) {
                        SocketChannelExtender socket = sockets.get(key.channel());
                        if (socket != null) {
                            closeSocketPair(socket);
                        } else {
                            key.channel().close();
                        }
                    }
                }
                selectedKeys.clear();
                lastCycleRunTime.set((new Date()).getTime() - timeCycleBegin);
            }
        } catch (Exception e) {
            System.err.println("Some problems with worker");
            e.printStackTrace();
        }
    }


    private void finishConnect(SelectionKey key)
            throws IOException
    {
        SocketChannel channel = (SocketChannel) key.channel();
        channel.finishConnect();
        key.interestOps(SelectionKey.OP_READ);
    }

    private void read(SocketChannelExtender socket) {
        int result = socket.read(readBuffer);

        if ((result & RES_CLOSE_SOCKET) != 0) {
            closeSocketPair(socket);
        }
        if ((result & RES_ALLOCATE_BUFFER) != 0) {
            readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
        } else {
            readBuffer.clear();
        }
        if ((result & RES_WRITE_PAIR_SOCKET) != 0) {
            SelectionKey secondKey = socket.getSecondChannel().getChannel().keyFor(selector);
            if (secondKey != null) {
                secondKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
        }
        if ((result & RES_WRITE_DATA_END) != 0) {
            SelectionKey secondKey = socket.getSecondChannel().getChannel().keyFor(selector);
            if (secondKey != null) {
                secondKey.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    private void write(SocketChannelExtender socket, SelectionKey key) {
        int result = socket.write();

        if ((result & RES_CLOSE_SOCKET) != 0) {
            closeSocketPair(socket);
        }
        if ((result & RES_WRITE_DATA_END) != 0) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }


    private void closeSocketPair(SocketChannelExtender socket) {
        socket.close();

        sockets.remove(socket.getChannel());
        sockets.remove(socket.getSecondChannel().getChannel());
    }
}
