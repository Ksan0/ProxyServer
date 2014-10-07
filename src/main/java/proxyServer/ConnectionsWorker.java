package proxyServer;


import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {

    class ExecConnectionStatus {
        public boolean alive = true;
        public boolean idle = false;
    }

    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_REMOVED_SOCKET  = 1 << 0;
    public static final int RES_IDLE_CALL       = 1 << 1;
    public static final int RES_ALLOCATE_BUFFER = 1 << 2;

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


    static AtomicLong l = new AtomicLong(0);

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
            boolean idle = true;
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
            }
        }
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
