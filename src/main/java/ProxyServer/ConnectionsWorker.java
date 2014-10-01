package ProxyServer;


import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class ConnectionsWorker implements Runnable {

    private AtomicLong lastCycleRunTime;
    private ConcurrentHashMap<SocketChannel, SocketChannelExtender> sockets;


    public ConnectionsWorker() {
        lastCycleRunTime = new AtomicLong(0);
        sockets = new ConcurrentHashMap<>();
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

                if (!entry.getValue().exec()) {
                    sockets.remove(entry.getValue().getSecondChannel());
                    sockets.remove(entry.getKey());
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
