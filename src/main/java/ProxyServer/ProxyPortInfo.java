package ProxyServer;


import java.net.SocketAddress;


public class ProxyPortInfo {
    public short fromPort;
    public SocketAddress toAddress;

    public ProxyPortInfo(short fromPort, SocketAddress toAddress) {
        this.fromPort = fromPort;
        this.toAddress = toAddress;
    }
}
