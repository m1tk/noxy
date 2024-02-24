package noxy;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import noxy.Balancer.BalanceStrategy;
import noxy.Balancer.LeastConnDistribution;
import noxy.Balancer.RoundRobinDistribution;
import noxy.Server.ConnectionLimits;
import noxy.Server.HttpProxyModeClientHandler;
import noxy.Server.HttpProxyModeHandler;
import noxy.Server.TcpProxyModeClientHandler;
import noxy.ServerConfig.BindConf;
import noxy.ServerConfig.FrontendConf;

public class ServerInit {
    static Logger logger = LogManager.getLogger(ServerInit.class);
    
    static String[] TLS_VERSIONS   = new String[] { "TLSv1.1", "TLSv1.2", "TLSv1.3" };
    static String[] ALPN_PROTOCOLS = new String[] { ApplicationProtocolNames.HTTP_1_1, ApplicationProtocolNames.HTTP_2 };

    public static HashMap<String, BalanceStrategy> balance_algorithm  = new HashMap<>();
    public static HashMap<InetSocketAddress, SslContext> ssl_ctx_list = new HashMap<>();


    private Config conf;

    static public long maxconn;
    static public AtomicLong maxconn_counter;

    public ServerInit(Config conf) {
        this.conf = conf;

        // Giving limits
        ConnectionLimits.global_maxconn         = conf.global.maxconn;
        ConnectionLimits.global_maxconn_counter = new AtomicLong(0);
        // Number of threads configured
        EventLoopGroup group = new NioEventLoopGroup(this.threads(conf.global));

        // Adding backend
        this.setup_backend(this.conf.backend, this.conf.global);
        
        // Starting frontends
        List<ChannelFuture> futures = new ArrayList<>();
        for (FrontendConf frontend : this.conf.frontend) {
            this.setup_frontend(group, conf.global, frontend, this.conf.backend, futures);
        }

        // Block until server shutdown.
        try {
            futures.get(0).channel().closeFuture().sync();
        } catch (InterruptedException e ) {
            return;
        }
    }

    private void setup_backend(List<BackEndConf> backends, GlobalConf globals) {
        // Deciding balancing algorithm
        for (BackEndConf backend_conf : backends) {
            List<Pair<String, InetSocketAddress>> backend_list = new ArrayList<>();
            for (ServerConf server: backend_conf.servers) {
                URI uri;
                try {
                    uri = new URI(null, server.addr_port, null, null, null).parseServerAuthority();
                } catch (Exception e) {
                    logger.error("Can't parse address "+server.addr_port+": "+e.toString());
                    System.exit(2);
                    return;
                }
                backend_list.add(Pair.of(backend_conf.name, new InetSocketAddress(uri.getHost(), uri.getPort())));
            }

            BalanceStrategy balance_algo;
            if (backend_conf.balance.equals("roundrobin")) {
                balance_algo = new RoundRobinDistribution(backend_list, globals.health_check);
            } else if (backend_conf.balance.equals("leastconn")) {
                balance_algo = new LeastConnDistribution(backend_list, globals.health_check);
            } else {
                logger.error("Balance algorithm "+backend_conf.balance+" in backend ["+backend_conf.name+"] not found");
                System.exit(1);
                return;
            }

            balance_algorithm.put(backend_conf.name, balance_algo);
        }
    }

    private void setup_frontend(EventLoopGroup group, GlobalConf globals, FrontendConf frontend,
            List<BackEndConf> backends, List<ChannelFuture> futures) {

        List<InetSocketAddress> listen_addrs = new ArrayList<>();

        // Iterating over all addresses we want to bind
        for (BindConf addr: frontend.bind) {
            URI uri;
            try {
                uri = new URI(null, addr.addr_port, null, null, null).parseServerAuthority();
            } catch (Exception e) {
                logger.error("Can't parse address "+addr.addr_port+": "+e.toString());
                System.exit(2);
                return;
            }

            InetSocketAddress addr_port = new InetSocketAddress(uri.getHost(), uri.getPort());
            listen_addrs.add(addr_port);

            // TLS cert for port
            if (addr.ssl != null) {
                ssl_ctx_list.put(addr_port, this.load_ssl_cert(addr_port, addr.ssl));
            }
        }

        // Setup new server
        ServerBootstrap b = new ServerBootstrap();

        b.group(group)
            .channel(NioServerSocketChannel.class)
            .handler(new LoggingHandler(LogLevel.DEBUG))
            .option(ChannelOption.SO_REUSEADDR, true);

        ConnectionLimits limits = new ConnectionLimits(frontend.maxconn);
        
        if (frontend.mode.equals("tcp")) {
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline()
                        .addLast("readTimeoutHandler", new ReadTimeoutHandler(frontend.timeout_client, TimeUnit.MILLISECONDS));
                                        InetSocketAddress addr = (InetSocketAddress)ch.localAddress();
                    SslContext ssl_ctx_found;
                    if ((ssl_ctx_found = ssl_ctx_list.get(addr)) != null
                            || (ssl_ctx_found = ssl_ctx_list.get(new InetSocketAddress(addr.getPort()))) != null) {
                        ch.pipeline()
                            .addLast("ssl", ssl_ctx_found.newHandler(ch.alloc()))
                            .addLast(new TcpProxyModeClientHandler(limits, frontend));
                    } else {
                        ch.pipeline()
                            .addLast(new TcpProxyModeClientHandler(limits, frontend));
                    }
                }
            });
        } else if (frontend.mode.equals("http")) {
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline()
                        .addLast("readTimeoutHandler", new ReadTimeoutHandler(frontend.timeout_client, TimeUnit.MILLISECONDS));
                    InetSocketAddress addr = (InetSocketAddress)ch.localAddress();
                    SslContext ssl_ctx_found;
                    if ((ssl_ctx_found = ssl_ctx_list.get(addr)) != null
                            || (ssl_ctx_found = ssl_ctx_list.get(new InetSocketAddress(addr.getPort()))) != null) {
                        ch.pipeline()
                            .addLast("ssl", ssl_ctx_found.newHandler(ch.alloc()))
                            .addLast(new HttpProxyModeHandler(limits, frontend));
                    } else {
                        if (!limits.new_connection()) {
                            limits.connection_finished();
                            ch.close();
                            return;
                        }

                        logger.debug("New connection from "+ch.remoteAddress());

                        ch.pipeline()
                            .addLast(new HttpServerCodec())
                            .addLast("handler", new HttpProxyModeClientHandler(limits, frontend, false));
                    }
                }
            });
        } else {
            logger.error("Proxy mode "+frontend.mode+" for frontend ["+frontend.name+"] not found");
            System.exit(1);
        }


        // Listening to all ports
        for (InetSocketAddress addr_port : listen_addrs) {
            ChannelFuture f = b.bind(addr_port);
            f.addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    if (future.isSuccess()) {
                        logger.info("Listening on: " + addr_port);
                    } else {
                        logger.error("Failed listening on: " + addr_port);
                    }
                }
            });

            try {
                f.sync();
            } catch (InterruptedException e) {
                logger.error("Server start failed: "+e.toString());
                System.exit(1);
            }
            futures.add(f);
        }
    }

    private SslContext load_ssl_cert(InetSocketAddress bind, LinkedHashMap<String, Object> cert) {
        final SslContext ssl_ctx;
        try {
            SslContextBuilder ssl_ctx_builder;
            LinkedHashMap<String, Object> cert_key;

            if (cert instanceof LinkedHashMap) {
                // Loading certificate from external files
                cert_key = (LinkedHashMap<String, Object>)cert;
                
                if (cert_key.containsKey("type") && cert_key.get("type") == "gen_cert") {
                    // We want to generate a new self signed certificate
                    SelfSignedCertificate ssc = new SelfSignedCertificate();
                    ssl_ctx_builder           = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
                    
                    logger.debug("Generating self signed certificate for "+bind);
                } else {
                    if (!cert_key.containsKey("cert")) {
                        logger.error("Missing certificate for "+bind);
                        System.exit(1);
                    } else if (!cert_key.containsKey("key")) {
                        logger.error("Missing private key for "+bind);
                        System.exit(1);
                    }

                    File cert_file  = new File((String)cert_key.get("cert"));
                    File key_file   = new File((String)cert_key.get("key"));

                    // Checking if pass is needed
                    String pass;
                    if ((pass = (String)cert_key.get("pass")) != null) {
                        ssl_ctx_builder = SslContextBuilder.forServer(cert_file, key_file, pass);
                    } else {
                        ssl_ctx_builder = SslContextBuilder.forServer(cert_file, key_file);
                    }

                    if (cert_key.containsKey("ca")) {
                        if (!(cert_key.get("ca") instanceof String)) {
                            logger.error("CA for "+bind+" is not a string");
                            System.exit(1);
                        }
                        ssl_ctx_builder
                            .trustManager(new File((String)cert_key.get("ca")))
                            .clientAuth(ClientAuth.REQUIRE);
                    }

                    logger.info("Certificate "+cert_key.get("cert")+" and key "+cert_key.get("key")+" loaded for "+bind);
                }
            } else {
                logger.error("Error reading ssl config for "+bind);
                System.exit(1);
                return null;
            }



            if (cert_key.containsKey("alpn")) {
                Object protocols = cert_key.get("alpn");
                if (!(protocols instanceof List) || ((List)protocols).size() > 0 && !(((List)protocols).get(0) instanceof String)) {
                    logger.error("Ssl ALPN procols for "+bind+" must be a list of strings");
                    System.exit(1);
                }
                List<String> list_protocols = (List<String>)protocols;
                if (!list_protocols.isEmpty()) {
                    for (String prot : list_protocols) {
                        if (!Arrays.stream(ALPN_PROTOCOLS).anyMatch(x -> x.equals(prot))) {
                            logger.error("Unknown/unsupported ALPN protocol "+prot+" for "+bind);
                            System.exit(1);
                        }
                    }
                }

                ssl_ctx_builder.applicationProtocolConfig(
                    new ApplicationProtocolConfig(Protocol.ALPN, SelectorFailureBehavior.FATAL_ALERT,
                    SelectedListenerFailureBehavior.ACCEPT, list_protocols));
            }

            if (cert_key.containsKey("versions")) {
                Object versions = cert_key.get("versions");
                if (!(versions instanceof List) || ((List)versions).size() > 0 && !(((List)versions).get(0) instanceof String)) {
                    logger.error("Ssl versions for "+bind+" must be a list of strings");
                    System.exit(1);
                }
                List<String> list_versions = (List<String>)versions;
                if (!list_versions.isEmpty()) {
                    for (String ver : list_versions) {
                        if (!Arrays.stream(TLS_VERSIONS).anyMatch(x -> x.equals(ver))) {
                            logger.error("Unknown/unsupported ssl version "+ver+" for "+bind);
                            System.exit(1);
                        }
                    }
                    ssl_ctx_builder.protocols(list_versions);
                }
            }

            ssl_ctx = ssl_ctx_builder.build();
        } catch (Exception e) {
            logger.error("Error loading TLS certificate: "+e.toString());
            System.exit(1);
            return null; // Does nothing, just to stop ssl_ctx may not be initialized error
        }
        return ssl_ctx;
    }

    private int threads(GlobalConf global) {
        int threads;
        if (global.threads instanceof String && ((String)global.threads).equals("max")) {
            threads = Runtime.getRuntime().availableProcessors();
        } else if (global.threads instanceof Integer) {
            threads = (Integer)global.threads;
            if (threads > Runtime.getRuntime().availableProcessors()) {
                logger.warn("Only "+Runtime.getRuntime().availableProcessors()+" threads detected in system, "
                        +"using more threads than system threads may not increase performance");
            }
        } else {
            logger.error("Unknewn value "+global.threads+" for threads");
            System.exit(1);
            return 0;
        }

        logger.info("Using "+threads+" threads");
        return threads;
    }
}
