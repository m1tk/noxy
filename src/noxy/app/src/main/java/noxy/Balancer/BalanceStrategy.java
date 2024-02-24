package noxy.Balancer;

public interface BalanceStrategy {
    /// Ask for next backend
    BackendTracker get_backend();

    /// Connection is closed
    void connection_finished(BackendTracker tracker);

    /// Number of active backends
    int active_backends();
}
