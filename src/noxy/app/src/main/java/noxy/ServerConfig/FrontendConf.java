package noxy.ServerConfig;

import java.util.List;

public class FrontendConf {
    public String name;

    public Integer timeout_connect;
    public Long timeout_client;
    public Long timeout_server;
    public Long maxconn;
    public String mode;

    public List<BindConf> bind;
    public String use_backend;

    public List<HttpCondition> http_condition;
}
