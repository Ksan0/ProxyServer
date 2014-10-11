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
        public SocketChannelExtender clientChannel;
        public int clientOps;
        public SocketChannelExtender proxyChannel;
        public int proxyOps;

        RegisterInfo(SocketChannelExtender clientChannel, int clientOps, SocketChannelExtender proxyChannel, int proxyOps) {
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
        registerSockets.add(new RegisterInfo(
                clientSocketChannel,
                SelectionKey.OP_READ,
                proxySocketChannel,
                proxyNeedFinishConnection ? SelectionKey.OP_CONNECT : SelectionKey.OP_READ
        ));

        selector.wakeup();
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
                            }
                            if (key.isReadable()) {
                                read(socketChannelExtender);
                            }
                            if (key.isWritable()) {
                                write(socketChannelExtender, key);
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

                    regInfo.clientChannel.getChannel().register(this.selector, regInfo.clientOps);
                    regInfo.proxyChannel.getChannel().register(this.selector, regInfo.proxyOps);

                    sockets.put(regInfo.clientChannel.getChannel(), regInfo.clientChannel);
                    sockets.put(regInfo.proxyChannel.getChannel(), regInfo.proxyChannel);
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
            readBuffer.clear();
            return;
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
            readBuffer.clear();
            return;
        }
        if ((result & RES_WRITE_DATA_END) != 0) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }


    private void closeSocketPair(SocketChannelExtender socket) {
        sockets.remove(socket.getSecondChannel().getChannel());
        sockets.remove(socket.getChannel());

        socket.close();
    }
}
