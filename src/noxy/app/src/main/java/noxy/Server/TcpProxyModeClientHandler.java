package noxy.Server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import noxy.ServerInit;
import noxy.Balancer.BackendTracker;
import noxy.Balancer.BalanceStrategy;
import noxy.ServerConfig.FrontendConf;

public class TcpProxyModeClientHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LogManager.getLogger(TcpProxyModeClientHandler.class);


    private ConnectionLimits limits;
    private FrontendConf frontend;
    private BalanceStrategy balance_algo;

    private Channel client_channel;
    private Channel backend_channel;

    private BackendTracker backend;

    private AtomicReference<Channel> backend_ch;
    
    public TcpProxyModeClientHandler(ConnectionLimits limits, FrontendConf frontend) {
        this.limits       = limits;
        this.frontend     = frontend;
        this.backend_ch   = new AtomicReference<>();
        this.balance_algo = ServerInit.balance_algorithm.get(frontend.use_backend);
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // We got new connection
        client_channel = ctx.channel();
        client_channel.config().setAutoRead(false);

        if (!this.limits.new_connection()) {
            client_channel.close();
            return;
        }

        logger.debug("New connection from "+client_channel.remoteAddress());

        if (!connect_backend(0)) {
            return;
        }
    }

    private Boolean connect_backend(int attempts) {
        int active_backends_count = this.balance_algo.active_backends();

        // Asking for one of our backends to come to the rescue!
        backend = balance_algo.get_backend();
        if (backend == null) {
            // OH no! seems like we run out of active backends
            client_channel.close();
            return false;
        }

        // Establishing connection to one of our backends
        Bootstrap b = new Bootstrap();
        b.group(client_channel.eventLoop()) // use the same EventLoop
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, frontend.timeout_connect)
                .channel(client_channel.getClass())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                            .addLast("readTimeoutHandler", new ReadTimeoutHandler(frontend.timeout_server, TimeUnit.MILLISECONDS))
                            .addLast(new ProxyModeBackendHandler(client_channel, balance_algo, backend));
                    }
                });
        
        ChannelFuture f = b.connect(backend.addr_port);

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                backend_ch.set(f.channel());
                client_channel.config().setAutoRead(true); // connection is ready, enable AutoRead
            } else {
                this.balance_algo.connection_finished(backend);
                if (attempts == active_backends_count) {
                    client_channel.close();
                } else {
                    int new_attempts = attempts+1;
                    f.channel().eventLoop().schedule(() -> {
                        connect_backend(new_attempts);
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }
        });

        return true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (backend_ch != null) {
            backend_channel = backend_ch.get();
            backend_ch = null;
        }
        backend_channel.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.limits.connection_finished();
        flushAndClose(backend_channel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.debug("Connection with client "+ctx.channel().remoteAddress().toString()+" closed: "+e);
        flushAndClose(client_channel);
    }

    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
