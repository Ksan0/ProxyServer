package proxyServer;


import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {

    class ExecConnectionStatus {
        public boolean alive = true;
        public boolean idle = false;
    }

    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_REMOVED_SOCKET  = 1 << 1;
    public static final int RES_ALLOCATE_BUFFER = 1 << 2;

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
        clientSocketChannel.getChannel().register(selector, SelectionKey.OP_READ);
        if (proxyNeedFinishConnection) {
            proxySocketChannel.getChannel().register(selector, SelectionKey.OP_CONNECT);
        } else {
            proxySocketChannel.getChannel().register(selector, SelectionKey.OP_READ);
        }

        sockets.put(clientSocketChannel.getChannel(), clientSocketChannel);
    }


    public void removeSocketPair(SocketChannelExtender socket) {
        // we don't know who call "remove": client or proxy socket. So, try remove both
        sockets.remove(socket.getChannel());
        sockets.remove(socket.getSecondChannel().getChannel());
    }

    @Override
    public void run() {
        System.out.println("ConnectionWorker run in thread " + Thread.currentThread().getId());

        try {
            while (true) {
                /*boolean idle = true;
                long timeCycleBegin = (new Date()).getTime();

                for(Map.Entry<SocketChannel, SocketChannelExtender> entry: sockets.entrySet()) {

                    SocketChannelExtender first = entry.getValue();
                    ExecConnectionStatus status = execForSocket(first);
                    if (status.alive) {
                        if (!status.idle) {
                            idle = false;
                        }
                        status = execForSocket(first.getSecondChannel());
                        if (status.alive && !status.idle) {
                            idle = false;
                        }int result = 0;
                    }

                }

                lastCycleRunTime.set((new Date()).getTime() - timeCycleBegin);

                int sleepTime = 0;
                if (sockets.isEmpty()) {
                    sleepTime = 10;
                } else if (idle) {
                    sleepTime = 1;
                }
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (Exception e) {
                    }
                }*/


                selector.select();
                long timeCycleBegin = (new Date()).getTime();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                for (SelectionKey key: selectedKeys) {
                    try {
                        if (!key.isValid()) {
                            throw new CancelledKeyException();
                        }

                        SocketChannelExtender socketChannelExtender = sockets.get(key.channel());

                        if (key.isConnectable()) {
                            finishConnect(key);
                        }
                        if (key.isReadable()) {

                        }
                        if (key.isWritable()) {

                        }

                        /*
                        int newRWState = key.readyOps();
                        if (newRWState != 0) {
                            SocketChannelExtender socketChannelExtender = sockets.get(key.channel());
                            if (socketChannelExtender != null) {
                                socketChannelExtender.setRWState(newRWState);
                            }
                        }
                        */
                    } catch (CancelledKeyException | IOException e) {
                        key.channel().close();
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
        channel.register(selector, SelectionKey.OP_READ);
    }


    private ExecConnectionStatus execForSocket(SocketChannelExtender socket) {
        ExecConnectionStatus status = new ExecConnectionStatus();
        int res = socket.exec(readBuffer);

        status.alive = (res & RES_REMOVED_SOCKET) == 0;
        status.idle = (res & RES_IDLE_CALL) != 0;

        if ((res & RES_ALLOCATE_BUFFER) != 0) {
            readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
        } else {
            readBuffer.clear();
        }

        return status;
    }
}
