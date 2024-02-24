package noxy.Server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import noxy.Balancer.BackendTracker;
import noxy.Balancer.BalanceStrategy;

public class ProxyModeBackendHandler extends ChannelInboundHandlerAdapter {
    static Logger logger = LogManager.getLogger(ProxyModeBackendHandler.class);

    public Channel client_channel;
    private Channel backend_channel;

    private BalanceStrategy balance_algo;
    private BackendTracker backend_track;

    public ProxyModeBackendHandler(Channel client_channel, BalanceStrategy balancer, BackendTracker tracker) {
        this.client_channel = client_channel;
        this.balance_algo   = balancer;
        this.backend_track  = tracker;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.backend_channel = ctx.channel();
        logger.debug("Connected to backend "+backend_channel.remoteAddress());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        client_channel.writeAndFlush(msg); // just forward
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Connection with backend "+ctx.channel().remoteAddress().toString()+" closed");
        this.balance_algo.connection_finished(backend_track);
        flushAndClose(client_channel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.debug("Connection with backend "+ctx.channel().remoteAddress().toString()+" closed: "+e);
        flushAndClose(backend_channel);
    }

    private void flushAndClose(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
