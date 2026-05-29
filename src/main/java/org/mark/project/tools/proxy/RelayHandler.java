package org.mark.project.tools.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    private final Channel otherSide;

    public RelayHandler(Channel otherSide) {
        this.otherSide = otherSide;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (otherSide.isActive()) {
            otherSide.writeAndFlush(msg);
        } else {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (otherSide.isActive()) {
            otherSide.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Relay error", cause);
        ctx.close();
    }
}
