package ProxyServer;


import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {
    public static final int DEFAULT_BUFFER_SIZE = 1024 * 16;

    public static final int RES_REMOVE_SOCKETS = 1 << 0;
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


    public void addPair(SocketChannelExtender first, SocketChannelExtender second) {
        sockets.put(first.getChannel(), first);
        sockets.put(second.getChannel(), second);
    }


    @Override
    public void run() {
        System.out.println("ConnectionWorker run in thread " + Thread.currentThread().getId());

        while(true) {
            long timeCycleBegin = (new Date()).getTime();

            for(Map.Entry<SocketChannel, SocketChannelExtender> entry: sockets.entrySet()) {

                int res = entry.getValue().exec(readBuffer);
                if ((res & RES_REMOVE_SOCKETS) != 0) {
                    sockets.remove(entry.getValue().getSecondChannel());
                    sockets.remove(entry.getKey());
                }
                if ((res & RES_ALLOCATE_BUFFER) != 0) {
                    readBuffer = new RWSocketChannelBuffer(DEFAULT_BUFFER_SIZE);
                } else {
                    readBuffer.clear();
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
}
