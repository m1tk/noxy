package noxy.Balancer;

import java.net.InetSocketAddress;

public class BackendTracker {
    public InetSocketAddress addr_port;
    public int id;

    public BackendTracker(InetSocketAddress addr_port, int id) {
        this.addr_port = addr_port;
        this.id        = id;
    }
}
