package proxyServer;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {

    class RegisterInfo {
        public SocketChannel clientChannel;
        public int clientOps;
        public SocketChannel proxyChannel;
        public int proxyOps;

        RegisterInfo(SocketChannel clientChannel, int clientOps, SocketChannel proxyChannel, int proxyOps) {
            this.clientChannel = clientChannel;
            this.clientOps = clientOps;
            this.proxyChannel = proxyChannel;
            this.proxyOps = proxyOps;
        }
    }


    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_CLOSE_SOCKET        = 1 << 1;
    public static final int RES_ALLOCATE_BUFFER     = 1 << 2;
    public static final int RES_WRITE_PAIR_SOCKET   = 1 << 3;
    public static final int RES_WRITE_DATA_END      = 1 << 4;

    private AtomicLong lastCycleRunTime;
    private HashMap<SocketChannel, SocketChannelExtender> sockets;
    private ConcurrentLinkedQueue<RegisterInfo> registerSockets;

    private Selector selector;
    private RWSocketChannelBuffer readBuffer;

    public ConnectionsWorker()
            throws IOException
    {
        lastCycleRunTime = new AtomicLong(0);
        sockets = new HashMap<>();
        registerSockets = new ConcurrentLinkedQueue<>();
        selector = SelectorProvider.provider().openSelector();
        readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
    }


    public long getLastCycleRunMSTime() {
        return lastCycleRunTime.get();
    }


    public void addSocketPair(SocketChannelExtender clientSocketChannel, SocketChannelExtender proxySocketChannel, boolean proxyNeedFinishConnection)
    {
        sockets.put(clientSocketChannel.getChannel(), clientSocketChannel);
        sockets.put(proxySocketChannel.getChannel(), proxySocketChannel);

        registerSockets.add(new RegisterInfo(
                clientSocketChannel.getChannel(),
                SelectionKey.OP_READ,
                proxySocketChannel.getChannel(),
                proxyNeedFinishConnection ? SelectionKey.OP_CONNECT : SelectionKey.OP_READ
        ));

        selector.wakeup();
    }


    public int keysCount() {
        return selector.keys().size();
    }

    public int socketsSize() {
        return sockets.size();
    }

    public long timeMs() {
        return lastCycleRunTime.get();
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
                                Debug.LogEnd();
                            }
                            if (key.isReadable()) {
                                Debug.LogStart(socketChannelExtender.d_getID(), "read");
                                read(socketChannelExtender);
                                Debug.LogEnd();
                            }
                            if (key.isWritable()) {
                                Debug.LogStart(socketChannelExtender.d_getID(), "write");
                                write(socketChannelExtender, key);
                                Debug.LogEnd();
                            }
                        } else {
                            key.channel().close();
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

                Iterator<RegisterInfo> iter = registerSockets.iterator();
                while(iter.hasNext()) {
                    RegisterInfo regInfo = iter.next();
                    iter.remove();
                    regInfo.clientChannel.register(this.selector, regInfo.clientOps);
                    regInfo.proxyChannel.register(this.selector, regInfo.proxyOps);
                }

                if (!sockets.isEmpty()) {
                    lastCycleRunTime.set((new Date()).getTime() - timeCycleBegin);
                } else {
                    lastCycleRunTime.set(0);
                }
            }
        } catch (Exception e) {
            System.err.println("Some problems with worker");
            e.printStackTrace();
            System.exit(1);
        }
    }


    private void finishConnect(SelectionKey key)
            throws IOException
    {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.finishConnect()) {
            key.interestOps(SelectionKey.OP_READ);
        }
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
        Debug.LogStart(socket.d_getID(), "closeSocketPair");
        socket.close();

        sockets.remove(socket.getChannel());
        sockets.remove(socket.getSecondChannel().getChannel());
        Debug.LogEnd();
    }
}
