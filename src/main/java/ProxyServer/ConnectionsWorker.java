package ProxyServer;


import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {
    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_REMOVED_SOCKET = 1 << 0;
    public static final int RES_ALLOCATE_BUFFER = 1 << 1;

    private AtomicLong lastCycleRunTime;
    private ConcurrentHashMap<SocketChannel, SocketChannelExtender> sockets;

    private RWSocketChannelBuffer readBuffer;

    public ConnectionsWorker() {
        lastCycleRunTime = new AtomicLong(0);
        sockets = new ConcurrentHashMap<>();
        readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
    }


    public long getLastCycleRunMSTime() {
        return lastCycleRunTime.get();
    }


    public void addSocket(SocketChannelExtender socket) {
        sockets.put(socket.getChannel(), socket);
    }


    public void removeSocket(SocketChannelExtender socket) {
        // we don't know who call "remove": client or proxy socket. So, try remove both
        sockets.remove(socket.getChannel());
        sockets.remove(socket.getSecondChannel().getChannel());
    }


    @Override
    public void run() {
        System.out.println("ConnectionWorker run in thread " + Thread.currentThread().getId());

        while(true) {
            long timeCycleBegin = (new Date()).getTime();

            for(Map.Entry<SocketChannel, SocketChannelExtender> entry: sockets.entrySet()) {

                SocketChannelExtender first = entry.getValue();
                if (execForSocket(first, first)) {
                    execForSocket(first.getSecondChannel(), first);
                }

            }

            lastCycleRunTime.set((new Date()).getTime() - timeCycleBegin);

            if (sockets.isEmpty()) {
                try {
                    Thread.currentThread().sleep(1);
                } catch (Exception e) {
                }
            }
        }
    }


    private boolean execForSocket(SocketChannelExtender socket, SocketChannelExtender removeBy) {
        boolean socketAlive = true;
        int res = socket.exec(readBuffer);

        if ((res & RES_REMOVED_SOCKET) != 0) {
            socketAlive = false;
        }
        if ((res & RES_ALLOCATE_BUFFER) != 0) {
            readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
        } else {
            readBuffer.clear();
        }

        return socketAlive;
    }
}
