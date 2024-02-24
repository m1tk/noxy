package noxy.Balancer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class Backend {
    public InetSocketAddress addr_port;
    public String name;

    public Backend(InetSocketAddress addr_port, String name) {
        this.addr_port = addr_port;
        this.name      = name;
    }
}

class BackendRef {
    public int index;
    public ArrayList<Integer> list;

    public BackendRef(int index, ArrayList<Integer> list) {
        this.index = index;
        this.list  = list;
    }
}

/// Robin round Balancer
/// A simple balancer that chooses next backend when new connection comes
public class RoundRobinDistribution implements BalanceStrategy {
    static Logger logger = LogManager.getLogger(RoundRobinDistribution.class);

    private List<Backend> backends = null;
    private AtomicReference<BackendRef> active_backends = null;

    public RoundRobinDistribution(List<Pair<String, InetSocketAddress>> backends, long health_check_timeout) {
        this.backends              = new ArrayList<>();
        BackendRef active_backends = new BackendRef(0, new ArrayList<>());
        
        for (int i = 0; i < backends.size(); i++) {
            this.backends.add(new Backend(backends.get(i).getRight(), backends.get(i).getLeft()));
            //active_backends.list.add(i);
        }
        this.active_backends = new AtomicReference<BackendRef>(active_backends);

        Runnable runnable = () -> { this.health_check(health_check_timeout); };
        Thread thread = new Thread(runnable);
        thread.start();
    }

    private void health_check(long health_check_timeout) {
        while (true) {
            for (int i = 0; i < this.backends.size(); i++) {
                IntConsumer on_backend_up = ind -> {
                    active_backends.getAndUpdate(ac -> {
                        if (!ac.list.contains(ind)) {
                            logger.info("Backend ["+this.backends.get(ind).name+":"+this.backends.get(ind).addr_port+"] is now up");
                            ac.list.add(ind);
                        }
                        return ac;
                    });
                };

                IntConsumer on_backend_down = ind -> {
                    active_backends.getAndUpdate(ac -> {
                        ac.list.removeIf(a -> {
                            if (a == ind) {
                                logger.warn("Backend ["+this.backends.get(ind).name+":"+this.backends.get(ind).addr_port+"] is now down");
                                return true;
                            } else {
                                return false;
                            }
                        });
                        return ac;
                    });
                };

                HealthCheck.check(this.backends.get(i).addr_port, i, on_backend_up, on_backend_down);
            }
            try {
                Thread.sleep(health_check_timeout, 0);
            } catch (InterruptedException e) {}
        }
    }

    public BackendTracker get_backend() {
        // Increment next backend, if we reach an index out of scope of backends
        // we go back to 0
        AtomicInteger index = new AtomicInteger(-1);
        active_backends.getAndUpdate(ac -> {
            int size = ac.list.size();
            if (size != 0) {
                int ind = ac.index;
                if (ind >= size) {
                    ind = 0;
                }
                ac.index = ind+1;
                index.set(ac.list.get(ind));
            }
            return ac;
        });
        int ind = index.get();
        if (ind == -1) {
            return null;
        } else {
            return new BackendTracker(this.backends.get(ind).addr_port, ind);
        }
    }

    public void connection_finished(BackendTracker tracker) {
        // We do nothing
        // Robin round don't account for number of connections
    }

    public int active_backends() {
        return active_backends.get().list.size();
    }
}
