package noxy.Server;


import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

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
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import noxy.ServerInit;
import noxy.Balancer.BackendTracker;
import noxy.Balancer.BalanceStrategy;
import noxy.ServerConfig.FrontendConf;
import noxy.ServerConfig.HttpCondition;
import noxy.ServerConfig.HttpSettings;

public class HttpProxyModeClientHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LogManager.getLogger(HttpProxyModeClientHandler.class);

    private ConnectionLimits limits;
    private FrontendConf frontend;
    
    private Channel backend_channel;

    private ArrayList<Object> msg_list;

    private boolean is_https;

    public HttpProxyModeClientHandler(ConnectionLimits limits, FrontendConf frontend, boolean is_https) {
        this.limits       = limits;
        this.frontend     = frontend;
        this.is_https     = is_https;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (backend_channel == null) {
            if (this.msg_list == null) {
                this.msg_list = new ArrayList<>();
                this.msg_list.add(msg);
                ctx.channel().config().setAutoRead(false);
                if (!this.http_condition(ctx.channel(), msg)) {
                    flushAndClose(ctx.channel());
                    return;
                }
            } else {
                this.msg_list.add(msg);
            }
        } else {
            backend_channel.writeAndFlush(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.limits.connection_finished();
        flushAndClose(backend_channel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.debug("Connection with client "+ctx.channel().remoteAddress().toString()+" closed: "+e);
        flushAndClose(ctx.channel());
    }
    
    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private boolean http_condition(Channel channel, Object msg) {
        String backend_name = frontend.use_backend;
        if (frontend.http_condition != null) {
            HttpSettings settings = new HttpSettings();
            HttpHeaders headers;
            if (msg instanceof DefaultFullHttpRequest) {
                DefaultFullHttpRequest req = (DefaultFullHttpRequest)msg;
                headers                    = req.headers();
                settings.uri               = req.uri();
            } else {
                DefaultHttpRequest req = (DefaultHttpRequest)msg;
                headers                = req.headers();
                settings.uri           = req.uri();
            }
            settings.host   = headers.get("host").split(":")[0];
            settings.port   = ((InetSocketAddress)channel.localAddress()).getPort();
            settings.scheme = this.is_https ? "https" : "http";

            for (HttpCondition cond : frontend.http_condition) {
                try {
                    if (cond.evaluate(settings)) {
                        if (cond.use_backend != null) {
                            // this will be the new backend
                            backend_name = cond.use_backend;
                        } else if (cond.redirect != null) {
                            // Redirect and exit
                            this.redirect(channel, cond.evaluate_str(cond.redirect, settings));
                            return false;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error evaluating condition for frontend ["+frontend.name+"]: "+e);
                    return false;
                }
            }
        }
        this.connect_backend(channel, 0, backend_name);
        return true;
    }

    private void redirect(Channel channel, String redirect) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PERMANENT_REDIRECT);

        response.headers()
            .set(HttpHeaderNames.LOCATION, redirect);

        channel.writeAndFlush(response);
    }

    private void connect_backend(Channel client_channel, int attempts, String name) {
        BalanceStrategy balance_algo = ServerInit.balance_algorithm.get(name);
        int active_backends_count    = balance_algo.active_backends();

        // Asking for one of our backends to come to the rescue!
        BackendTracker backend = balance_algo.get_backend();
        if (backend == null) {
            // OH no! seems like we run out of active backends
            client_channel.close();
            return;
        }

        // Establishing connection to our backend
        Bootstrap b = new Bootstrap();
        b.group(client_channel.eventLoop()) // use the same EventLoop
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, frontend.timeout_connect)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline()
                        .addLast("readTimeoutHandler", new ReadTimeoutHandler(frontend.timeout_server, TimeUnit.MILLISECONDS))
                        .addLast(new HttpClientCodec())
                        .addLast(new HttpContentDecompressor())
                        .addLast(new ProxyModeBackendHandler(client_channel, balance_algo, backend));
                }
            });
        
        ChannelFuture f = b.connect(backend.addr_port);

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                backend_channel = f.channel();
                for (Object msg : this.msg_list) {
                    backend_channel.writeAndFlush(msg);
                }
                this.msg_list.clear();
                this.msg_list = null;
                client_channel.config().setAutoRead(true); // connection is ready, enable AutoRead
                return;
            } else {
                balance_algo.connection_finished(backend);
                if (attempts == active_backends_count) {
                    client_channel.close();
                } else {
                    int new_attempts = attempts+1;
                    f.channel().eventLoop().schedule(() -> {
                        connect_backend(client_channel, new_attempts, name);
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }
        });
    }
}
