package proxyServer;


import com.sun.org.apache.bcel.internal.generic.Select;

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
    private ConnectionsListenInfo connectionsListenInfo;

    // all workers of process
    private ArrayList<ConnectionsWorker> workers;


    public ConnectionsAccepter(ProxyPortInfo port, ArrayList<ConnectionsWorker> workers) {
        connectionsListenInfo = null;

        try {
            Selector socketSelector = SelectorProvider.provider().openSelector();

            InetSocketAddress isa = new InetSocketAddress(port.fromPort);

            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(isa);
            serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

            connectionsListenInfo = new ConnectionsListenInfo(port, serverChannel, socketSelector);
        }
        catch (Exception e) {
            System.err.println("Can't open listen socket at port " + port.fromPort);
        }

        this.workers = workers;
    }


    @Override
    public void run() {
        System.out.println("ConnectionAccepter run in thread " + Thread.currentThread().getId());

        try {
            while(true) {
                connectionsListenInfo.selector.select();
                Set<SelectionKey> selectedKeys = connectionsListenInfo.selector.selectedKeys();
                for (SelectionKey key: selectedKeys) {
                    try {
                        if (!key.isValid()) {
                            throw new CancelledKeyException();
                        }

                        if (key.isAcceptable()) {
                            accept(connectionsListenInfo);
                        }
                    } catch (CancelledKeyException | IOException e) {
                        key.channel().close();
                    }
                }
                selectedKeys.clear();
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
        SocketChannelExtender clientSocketChannelExtender = new SocketChannelExtender(clientSocketChannel);

        SocketChannel proxySocketChannel = SocketChannel.open();
        proxySocketChannel.configureBlocking(false);
        proxySocketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        proxySocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        SocketChannelExtender proxySocketChannelExtender = new SocketChannelExtender(proxySocketChannel);

        clientSocketChannelExtender.setSecondChannel(proxySocketChannelExtender);
        proxySocketChannelExtender.setSecondChannel(clientSocketChannelExtender);

        boolean proxyNeedFinishConnection = !proxySocketChannel.connect(info.proxyPortInfo.toAddress);

        worker.addSocketPair(clientSocketChannelExtender, proxySocketChannelExtender, proxyNeedFinishConnection);
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
