package org.mark.project.tools.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyHttpServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyHttpServer.class);

    private final int port;
    private volatile Channel serverChannel;

    public ProxyHttpServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpProxyInitializer());

            serverChannel = b.bind(port).sync().channel();
            serverChannel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.parse(args);
        AuthUtil.configure(config.username, config.password);

        ProxyHttpServer server = new ProxyHttpServer(config.port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, closing server...");
            try {
                server.close();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));
        log.info("Starting HTTP Proxy on port {}", config.port);
        server.start();
    }
}
