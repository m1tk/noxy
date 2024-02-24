package noxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.apache.commons.lang3.builder.RecursiveToStringStyle;
import org.apache.commons.lang3.builder.ToStringBuilder;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import noxy.ServerConfig.FrontendConf;
import noxy.ServerConfig.HttpCondition;
import noxy.ServerConfig.HttpSettings;

class GlobalConf {
    public Long maxconn = 5000L;
    public Object threads = 1;
    public Long health_check = 5000L;
}

class DefaultConf {
    public Integer timeout_connect = 10000;
    public Long timeout_client     = 30000L;
    public Long timeout_server     = 30000L;
    public Long maxconn            = 1000L;
    public String mode             = "tcp";
}

class BackEndConf {
    public String name;
    public String balance;
    public List<ServerConf> servers;
}

class ServerConf {
    public String addr_port;
}

class InputConfig {
    public GlobalConf global;
    public DefaultConf defaults;
    public List<FrontendConf> frontend;
    public List<BackEndConf> backend;
}

class Config {
    static transient Logger logger = LogManager.getLogger(Config.class);
    
    public GlobalConf global;
    public List<FrontendConf> frontend;
    public List<BackEndConf> backend;

    public void load(String conf_file) {
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(new File(conf_file));
        } catch (FileNotFoundException e) {
            logger.error("Error loading config file: "+conf_file);
            System.exit(1);
            return;
        }
        Yaml yaml        = new Yaml(new Constructor(InputConfig.class));
        InputConfig conf = yaml.load(inputStream);

        logger.info("Running with config: " + ToStringBuilder.reflectionToString(conf, new RecursiveToStringStyle()));
        
        set_defaults(conf);

        this.global   = conf.global;
        this.frontend = conf.frontend;
        this.backend  = conf.backend;
    }

    private void set_defaults(InputConfig conf) {
        HttpCondition.init();
        
        for (FrontendConf frontend: conf.frontend) {
            if (frontend.timeout_client == null) {
                frontend.timeout_client = conf.defaults.timeout_client;
            }
            if (frontend.timeout_connect == null) {
                frontend.timeout_connect = conf.defaults.timeout_connect;
            }
            if (frontend.timeout_server == null) {
                frontend.timeout_server = conf.defaults.timeout_server;
            }
            if (frontend.maxconn == null) {
                frontend.maxconn = conf.defaults.maxconn;
            }
            if (frontend.mode == null) {
                frontend.mode = conf.defaults.mode;
            }

            if (frontend.http_condition != null) {
                if (frontend.mode.equals("tcp")) {
                    logger.error("Http condition cannot exist in tcp mode for frontend ["+frontend.name+"]");
                    System.exit(1);
                }
                for (HttpCondition cond: frontend.http_condition) {
                    if (!(cond.when instanceof String)) {
                        logger.error("Http condition of frontend ["+frontend.name+"] is not string");
                        System.exit(1);
                    }

                    if (cond.redirect != null && !(cond.redirect instanceof String)) {
                        logger.error("Http redirect statement of frontend ["+frontend.name+"] is not string");
                        System.exit(1);
                    }

                    try {
                        cond.compile();
                        cond.evaluate(HttpSettings.defaults());
                        if (cond.redirect != null) {
                            cond.evaluate_str(cond.redirect, HttpSettings.defaults());
                        }
                    } catch (Exception e) {
                        logger.error("Http condition of frontend ["+frontend.name+"] contains error: "+e);
                        System.exit(1);
                    }

                    if (cond.use_backend != null) {
                        boolean backend_found = false;
                        for (BackEndConf backend: conf.backend) {
                            if (backend.name.equals(cond.use_backend)) {
                                backend_found = true;
                                break;
                            }
                        }
                        if (!backend_found) {
                            logger.error("No backend "+cond.use_backend+" used in http condition found for frontend ["+frontend.name+"]");
                            System.exit(1);
                        }
                    }
                }
            }

            boolean backend_found = false;
            for (BackEndConf backend: conf.backend) {
                if (backend.name.equals(frontend.use_backend)) {
                    backend_found = true;
                    break;
                }
            }

            if (!backend_found) {
                logger.error("No backend configured for frontend "+frontend.name);
                System.exit(1);
            }
        }
    }
}
