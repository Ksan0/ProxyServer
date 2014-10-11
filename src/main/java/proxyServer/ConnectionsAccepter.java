package proxyServer;


import com.sun.org.apache.bcel.internal.generic.Select;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Set;


public class ConnectionsAccepter implements Runnable {


    // local for object
    private SocketAddress proxyToAddress;
    private ServerSocketChannel serverSocketChannel;
    private Selector selector;


    // all workers of process
    private ArrayList<ConnectionsWorker> workers;


    public ConnectionsAccepter(short proxyFromPort, SocketAddress proxyToAddress, ArrayList<ConnectionsWorker> workers) {
        try {
            this.selector = SelectorProvider.provider().openSelector();

            InetSocketAddress isa = new InetSocketAddress(proxyFromPort);

            this.serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(isa);
            serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        }
        catch (Exception e) {
            System.err.println("Can't open listen socket at port " + proxyFromPort);
        }

        this.proxyToAddress = proxyToAddress;
        this.workers = workers;
    }


    @Override
    public void run() {
        try {
            System.out.println( "ConnectionAccepter #" + Thread.currentThread().getId() + ": " + serverSocketChannel.getLocalAddress() + " -> " + proxyToAddress);

            while(true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                for (SelectionKey key: selectedKeys) {
                    try {
                        if (!key.isValid()) {
                            throw new CancelledKeyException();
                        }

                        if (key.isAcceptable()) {
                            accept();
                        }
                    } catch (CancelledKeyException e) {
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


    private void accept()
    {
        ConnectionsWorker worker = findWorkerForConnection();

        SocketChannel clientSocketChannel = null;
        SocketChannel proxySocketChannel = null;

        try {
            clientSocketChannel = serverSocketChannel.accept();
            clientSocketChannel.configureBlocking(false);
            clientSocketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            clientSocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            SocketChannelExtender clientSocketChannelExtender = new SocketChannelExtender(clientSocketChannel);

            proxySocketChannel = SocketChannel.open();
            proxySocketChannel.configureBlocking(false);
            proxySocketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            proxySocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            SocketChannelExtender proxySocketChannelExtender = new SocketChannelExtender(proxySocketChannel);

            clientSocketChannelExtender.setSecondChannel(proxySocketChannelExtender);
            proxySocketChannelExtender.setSecondChannel(clientSocketChannelExtender);

            boolean proxyNeedFinishConnection = !proxySocketChannel.connect(proxyToAddress);

            worker.addSocketPair(clientSocketChannelExtender, proxySocketChannelExtender, proxyNeedFinishConnection);
        } catch(IOException e) {
            try {
                if (clientSocketChannel != null) {
                    clientSocketChannel.close();
                }
            } catch(IOException idle) {
            }
            try {
                if (proxySocketChannel != null) {
                    proxySocketChannel.close();
                }
            } catch(IOException idle) {
            }
        }
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
