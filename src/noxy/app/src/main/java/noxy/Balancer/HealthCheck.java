package noxy.Balancer;

import java.net.InetSocketAddress;
import java.util.function.IntConsumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class HealthCheck {
    static EventLoopGroup group = new NioEventLoopGroup();

    static public void check(InetSocketAddress addr_port, int ind, IntConsumer is_up, IntConsumer is_down) {
        Bootstrap b = new Bootstrap();
        b.group(group) // use the same EventLoop
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .option(ChannelOption.AUTO_READ, false)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                }
            });
        
        ChannelFuture f    = b.connect(addr_port);

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                is_up.accept(ind);
            } else {
                is_down.accept(ind);
            }
        });
    }
}
