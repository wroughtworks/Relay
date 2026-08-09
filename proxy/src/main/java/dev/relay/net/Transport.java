package dev.relay.net;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.ThreadFactory;

/**
 * Picks the best available Netty transport.
 *
 * <p>Epoll where it exists, NIO everywhere else. Since spec &sect;9 targets a Linux
 * Docker host this usually resolves to epoll in production and NIO on a developer's
 * machine, with no behavioural difference between them.
 */
public enum Transport {

    EPOLL,
    NIO;

    public static Transport best() {
        return Epoll.isAvailable() ? EPOLL : NIO;
    }

    public EventLoopGroup createGroup(String namePrefix, int threads) {
        ThreadFactory factory = new DefaultThreadFactory(namePrefix, true);
        return this == EPOLL
                ? new EpollEventLoopGroup(threads, factory)
                : new NioEventLoopGroup(threads, factory);
    }

    public Class<? extends ServerChannel> serverChannelType() {
        return this == EPOLL ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    public Class<? extends Channel> clientChannelType() {
        return this == EPOLL ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
