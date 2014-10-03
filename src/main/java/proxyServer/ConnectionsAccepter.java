package proxyServer;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;


public class ConnectionsAccepter implements Runnable {

    private class ConnectionsListenInfo {
        public ProxyPortInfo proxyPortInfo;
        public ServerSocketChannel serverSocketChannel;
        public Selector selector;

        public ConnectionsListenInfo(ProxyPortInfo proxyPortInfo, ServerSocketChannel serverSocketChannel, Selector selector) {
            this.proxyPortInfo = proxyPortInfo;
            this.serverSocketChannel = serverSocketChannel;
            this.selector = selector;
        }
    }

    // local for object
    private ArrayList<ConnectionsListenInfo> connectionsListenInfo;
    private HashMap<SocketChannel, SocketChannelExtender> sockets;
    private ConcurrentLinkedQueue<SocketChannelExtender> removeSocketsQueue;

    // all workers of process
    private ArrayList<ConnectionsWorker> workers;


    public ConnectionsAccepter(ArrayList<ProxyPortInfo> ports, ArrayList<ConnectionsWorker> workers) {
        connectionsListenInfo = new ArrayList<>();
        sockets = new HashMap<>();
        removeSocketsQueue = new ConcurrentLinkedQueue<>();

        for (ProxyPortInfo proxyPortInfo: ports) {
            try {
                Selector socketSelector = SelectorProvider.provider().openSelector();

                InetSocketAddress isa = new InetSocketAddress(proxyPortInfo.fromPort);

                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.socket().bind(isa);
                serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

                connectionsListenInfo.add(new ConnectionsListenInfo(proxyPortInfo, serverChannel, socketSelector));
            }
            catch (Exception e) {
                System.err.println("Can't open listen socket at port " + proxyPortInfo.fromPort);
            }
        }
        ports.clear();

        this.workers = workers;
    }


    /**
     *
     * @param socketChannelExtender this object will be removed. It's pair too.
     */
    public void removeSocketChannel(SocketChannelExtender socketChannelExtender) {
        removeSocketsQueue.add(socketChannelExtender);
    }


    @Override
    public void run() {
        System.out.println("ConnectionAccepter run in thread " + Thread.currentThread().getId());

        try {
            while(true) {
                for (ConnectionsListenInfo info: connectionsListenInfo) {
                    info.selector.selectNow();
                    Set<SelectionKey> selectedKeys = info.selector.selectedKeys();

                    for (SelectionKey key : selectedKeys) {
                        try {
                            if (key.isAcceptable()) {
                                accept(info);
                            }
                            if (key.isConnectable()) {
                                finishConnect(info, key);
                            }

                            int newRWState = key.readyOps();
                            if (newRWState != 0) {
                                SocketChannelExtender socketChannelExtender = sockets.get(key.channel());
                                if (socketChannelExtender != null) {
                                    socketChannelExtender.setRWState(newRWState);
                                }
                            }
                        } catch (CancelledKeyException | IOException e) {
                            SocketChannelExtender socketChannelExtender = sockets.get(key.channel());
                            if (socketChannelExtender != null) {
                                socketChannelExtender.close();
                            }
                        }
                    }

                    selectedKeys.clear();
                }

                try {
                    Iterator<SocketChannelExtender> iterator = removeSocketsQueue.iterator();
                    while (iterator.hasNext()) {
                        SocketChannelExtender next = iterator.next();
                        iterator.remove();

                        sockets.remove(next.getSecondChannel().getChannel());
                        sockets.remove(next.getChannel());
                    }
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
            System.err.println("Something goes wrong");
            e.printStackTrace();
            System.exit(1);
        }
    }


    private void accept(ConnectionsListenInfo info)
            throws IOException
    {
        ConnectionsWorker worker = findWorkerForConnection();

        SocketChannel clientSocketChannel = info.serverSocketChannel.accept();
        clientSocketChannel.configureBlocking(false);
        clientSocketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        clientSocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        clientSocketChannel.register(info.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        SocketChannelExtender clientSocketChannelExtender = new SocketChannelExtender(this, worker, clientSocketChannel);
        sockets.put(clientSocketChannel, clientSocketChannelExtender);

        SocketChannel proxySocketChannel = SocketChannel.open();
        proxySocketChannel.configureBlocking(false);
        proxySocketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        proxySocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        if (!proxySocketChannel.connect(info.proxyPortInfo.toAddress)) {
            proxySocketChannel.register(info.selector, SelectionKey.OP_CONNECT);
        } else {
            proxySocketChannel.register(info.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
        SocketChannelExtender proxySocketChannelExtender = new SocketChannelExtender(this, worker, proxySocketChannel);
        sockets.put(proxySocketChannel, proxySocketChannelExtender);

        clientSocketChannelExtender.setSecondChannel(proxySocketChannelExtender);
        proxySocketChannelExtender.setSecondChannel(clientSocketChannelExtender);

        worker.addSocket(clientSocketChannelExtender);
    }


    private void finishConnect(ConnectionsListenInfo info, SelectionKey key)
            throws IOException
    {
        SocketChannel channel = (SocketChannel) key.channel();
        channel.finishConnect();
        channel.register(info.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }


    private ConnectionsWorker findWorkerForConnection() {
        int minTimeIndex = 0;
        long minTime = workers.get(0).getLastCycleRunMSTime();
        long tmpTime;
        for (int i = 1; i < workers.size(); ++i) {
            tmpTime = workers.get(i).getLastCycleRunMSTime();
            if (tmpTime < minTime) {
                minTime = tmpTime;
                minTimeIndex = i;
            }
        }

        return workers.get(minTimeIndex);
    }
}
