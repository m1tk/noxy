package noxy.Server;

import java.util.concurrent.atomic.AtomicLong;

public class ConnectionLimits {
    static public long global_maxconn;
    static public AtomicLong global_maxconn_counter;

    private long frontend_maxconn;
    private AtomicLong frontend_maxconn_counter;

    public ConnectionLimits(long limit) {
        this.frontend_maxconn         = limit;
        this.frontend_maxconn_counter = new AtomicLong(0L);
    }

    public boolean new_connection() {
        if (ConnectionLimits.global_maxconn_counter.incrementAndGet() > ConnectionLimits.global_maxconn) {
            return false;
        }

        if (this.frontend_maxconn_counter.incrementAndGet() > this.frontend_maxconn) {
            return false;
        }

        return true;
    }

    public void connection_finished() {
        ConnectionLimits.global_maxconn_counter.decrementAndGet();
        this.frontend_maxconn_counter.decrementAndGet();
    }
}
