package proxyServer;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

public class ProxyServerStarter {
    private static final int WORKERS_COUNT = 4;

    public static void main(String args[]) {
        ArrayList<Thread> threads = new ArrayList<>();

        // workers init
        ArrayList<ConnectionsWorker> workers = new ArrayList<>();
        InitWorkers(threads, workers);

        // connection accepters init
        ArrayList<ConnectionsAccepter> accepters = new ArrayList<>();
        InitAccepters(threads, accepters, workers);

        System.out.println("--- Statistic ---");
        System.out.println("ConnectionAccepters count = " + accepters.size());
        System.out.println("Workers count = " + workers.size());
        System.out.println("Worker buffer size = " + ConnectionsWorker.DEFAULT_BUFFER_SIZE * 4 / 1024.0f + " KB");

        // start all
        System.out.println("----------------");
        System.out.println("--- jeronimo ---");
        System.out.println("----------------");

        for (Thread thread: threads) {
            thread.start();
        }

        while(true) {
            for (ConnectionsWorker wrk : workers) {
                System.out.print(
                        "selector " + wrk.keysCount() + "/hashmap " + wrk.socketsSize() + " (" + wrk.timeMs() + "ms) | "
                );
            }
            System.out.println();
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }

        /*
        for (Thread thread: threads) {
            try {
                thread.join();
            }
            catch (Exception e) {
            }
        }*/
    }


    private static void InitWorkers(ArrayList<Thread> threads, ArrayList<ConnectionsWorker> workers) {
        for (int i = 0; i < WORKERS_COUNT; ++i) {
            try {
                ConnectionsWorker worker = new ConnectionsWorker();
                workers.add(worker);
                Thread thread = new Thread(worker);
                threads.add(thread);
            } catch (IOException e) {
                System.err.println("Can't create worker");
                e.printStackTrace();
            }
        }
    }


    private static void InitAccepters(ArrayList<Thread> threads, ArrayList<ConnectionsAccepter> accepters, ArrayList<ConnectionsWorker> workers) {
        accepters.add(new ConnectionsAccepter((short)9001, new InetSocketAddress("localhost", 80), workers));
        accepters.add(new ConnectionsAccepter((short)9081, new InetSocketAddress("localhost", 8081), workers));
        accepters.add(new ConnectionsAccepter((short)9002, new InetSocketAddress("localhost", 80), workers));
        accepters.add(new ConnectionsAccepter((short)9082, new InetSocketAddress("localhost", 8082), workers));

        for (ConnectionsAccepter connectionsAccepter: accepters) {
            Thread thread = new Thread(connectionsAccepter);
            threads.add(thread);
        }
    }
}
