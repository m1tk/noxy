package noxy.Server;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import noxy.ServerConfig.FrontendConf;


public class HttpProxyModeHandler extends ApplicationProtocolNegotiationHandler {
    static Logger logger = LogManager.getLogger(HttpProxyModeHandler.class);

    private ConnectionLimits limits;
    private FrontendConf frontend;

    public HttpProxyModeHandler(ConnectionLimits limits, FrontendConf frontend) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.limits       = limits;
        this.frontend     = frontend;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ChannelPipeline p      = ctx.pipeline();
        Channel client_channel = p.channel();
        
        if (!this.limits.new_connection()) {
            client_channel.close();
            return;
        }

        logger.debug("New connection from "+client_channel.remoteAddress());
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        ChannelPipeline p = ctx.pipeline();
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            p.addLast(Http2FrameCodecBuilder.forServer()
                    .autoAckPingFrame(true)
                    .autoAckSettingsFrame(true)
                    .build());
            p.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));
                    ch.pipeline().addLast("handler", new HttpProxyModeClientHandler(limits, frontend, true));
                }
            }));
            p.remove(this);
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            p.addLast(new HttpServerCodec());
            p.addLast("handler", new HttpProxyModeClientHandler(limits, frontend, true));
            p.remove(this);
            return;
        }


        throw new IllegalStateException("Unknown protocol: " + protocol);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.limits.connection_finished();
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
}
