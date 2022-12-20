/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.RakOfflineState;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakAcknowledgeHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakDatagramCodec;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakClientOfflineHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public static final String NAME = "rak-client-handler";

    private final ChannelPromise successPromise;
    private ScheduledFuture<?> timeoutFuture;
    private ScheduledFuture<?> retryFuture;

    private RakOfflineState state = RakOfflineState.HANDSHAKE_1;
    private int connectionAttempts;

    public RakClientOfflineHandler(ChannelPromise promise) {
        this.successPromise = promise;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        long timeout = channel.config().getOption(RakChannelOption.RAK_CONNECT_TIMEOUT);
        this.timeoutFuture = channel.eventLoop().schedule(this::onTimeout, timeout, TimeUnit.MILLISECONDS);
        this.retryFuture = channel.eventLoop().scheduleAtFixedRate(() -> this.onRetryAttempt(channel), 0, 1, TimeUnit.SECONDS);
        this.successPromise.addListener(future -> safeCancel(this.timeoutFuture, channel));
        this.successPromise.addListener(future -> safeCancel(this.retryFuture, channel));

        this.retryFuture.addListener(future -> {
            if (future.cause() != null) {
                this.successPromise.tryFailure(future.cause());
            }
        });
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        safeCancel(this.timeoutFuture, ctx.channel());
        safeCancel(this.retryFuture, ctx.channel());
    }

    private void onRetryAttempt(Channel channel) {
        switch (this.state) {
            case HANDSHAKE_1:
                this.sendOpenConnectionRequest1(channel);
                this.connectionAttempts++;
                break;
            case HANDSHAKE_2:
                this.sendOpenConnectionRequest2(channel);
                break;
        }
    }

    private void onTimeout() {
        this.successPromise.tryFailure(new ConnectTimeoutException());
    }

    private void onSuccess(ChannelHandlerContext ctx) {
        // Create new session which decodes RakDatagramPacket to RakMessage
        // We use addAfter() here, because user could have already added own handlers to the pipeline end
        RakChannel channel = (RakChannel) ctx.channel();
        RakSessionCodec sessionCodec = new RakSessionCodec(channel);
        channel.pipeline().addAfter(NAME, RakDatagramCodec.NAME, new RakDatagramCodec());
        channel.pipeline().addAfter(RakDatagramCodec.NAME, RakAcknowledgeHandler.NAME, new RakAcknowledgeHandler(sessionCodec));
        channel.pipeline().addAfter(RakAcknowledgeHandler.NAME, RakSessionCodec.NAME, sessionCodec);
        channel.pipeline().addAfter(RakSessionCodec.NAME, RakClientOnlineInitialHandler.NAME, new RakClientOnlineInitialHandler(this.successPromise));
        channel.pipeline().fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (!buf.isReadable()) {
            return; // Empty packet?
        }

        if (this.state == RakOfflineState.HANDSHAKE_COMPLETED) {
            // Forward open connection messages if handshake was completed
            ctx.fireChannelRead(buf.retain());
            return;
        }

        short packetId = buf.readUnsignedByte();

        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        if (!buf.isReadable(magicBuf.readableBytes()) || !ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf)) {
            this.successPromise.tryFailure(new CorruptedFrameException("RakMagic does not match"));
            return;
        }

        switch (packetId) {
            case ID_OPEN_CONNECTION_REPLY_1:
                this.onOpenConnectionReply1(ctx, buf);
                return;
            case ID_OPEN_CONNECTION_REPLY_2:
                this.onOpenConnectionReply2(ctx, buf);
                this.onSuccess(ctx);
                return;
            case ID_INCOMPATIBLE_PROTOCOL_VERSION:
                ctx.fireUserEventTriggered(RakDisconnectReason.INCOMPATIBLE_PROTOCOL_VERSION);
                this.successPromise.tryFailure(new IllegalStateException("Incompatible raknet version"));
                return;
            case ID_ALREADY_CONNECTED:
                ctx.fireUserEventTriggered(RakDisconnectReason.ALREADY_CONNECTED);
                this.successPromise.tryFailure(new ChannelException("Already connected"));
                return;
            case ID_NO_FREE_INCOMING_CONNECTIONS:
                ctx.fireUserEventTriggered(RakDisconnectReason.NO_FREE_INCOMING_CONNECTIONS);
                this.successPromise.tryFailure(new ChannelException("No free incoming connections"));
                return;
            case ID_IP_RECENTLY_CONNECTED:
                ctx.fireUserEventTriggered(RakDisconnectReason.IP_RECENTLY_CONNECTED);
                this.successPromise.tryFailure(new ChannelException("Address recently connected"));
                return;
        }
    }

    private void onOpenConnectionReply1(ChannelHandlerContext ctx, ByteBuf buffer) {
        long serverGuid = buffer.readLong();
        boolean security = buffer.readBoolean();
        int mtu = buffer.readShort();
        if (security) {
            this.successPromise.tryFailure(new SecurityException());
            return;
        }

        ctx.channel().config().setOption(RakChannelOption.RAK_MTU, mtu);
        ctx.channel().config().setOption(RakChannelOption.RAK_REMOTE_GUID, serverGuid);

        this.state = RakOfflineState.HANDSHAKE_2;
        this.sendOpenConnectionRequest2(ctx.channel());
    }

    private void onOpenConnectionReply2(ChannelHandlerContext ctx, ByteBuf buffer) {
        buffer.readLong(); // serverGuid
        RakUtils.readAddress(buffer); // serverAddress
        int mtu = buffer.readShort();
        buffer.readBoolean(); // security

        ctx.channel().config().setOption(RakChannelOption.RAK_MTU, mtu);
        this.state = RakOfflineState.HANDSHAKE_COMPLETED;
    }

    private void sendOpenConnectionRequest1(Channel channel) {
        int mtuDiff = (MAXIMUM_MTU_SIZE - MINIMUM_MTU_SIZE) / 9;
        int mtuSize = channel.config().getOption(RakChannelOption.RAK_MTU) - (this.connectionAttempts * mtuDiff);
        if (mtuSize < MINIMUM_MTU_SIZE) {
            mtuSize = MINIMUM_MTU_SIZE;
        }

        ByteBuf magicBuf = channel.config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        int rakVersion = channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();

        ByteBuf request = channel.alloc().ioBuffer(mtuSize);
        request.writeByte(ID_OPEN_CONNECTION_REQUEST_1);
        request.writeBytes(magicBuf.slice(), magicBuf.readableBytes());
        request.writeByte(rakVersion);
        // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header));
        request.writeZero(mtuSize - 1 - magicBuf.readableBytes() - 1 - (address.getAddress() instanceof Inet6Address ? 40 : 20) - UDP_HEADER_SIZE);
        channel.writeAndFlush(request);
    }

    private void sendOpenConnectionRequest2(Channel channel) {
        int mtuSize = channel.config().getOption(RakChannelOption.RAK_MTU);
        ByteBuf magicBuf = channel.config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);

        ByteBuf request = channel.alloc().ioBuffer(34);
        request.writeByte(ID_OPEN_CONNECTION_REQUEST_2);
        request.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        RakUtils.writeAddress(request, (InetSocketAddress) channel.remoteAddress());
        request.writeShort(mtuSize);
        request.writeLong(channel.config().getOption(RakChannelOption.RAK_GUID));
        channel.writeAndFlush(request);
    }

    private static void safeCancel(ScheduledFuture<?> future, Channel channel) {
        channel.eventLoop().execute(() -> { // Make sure this is not called at two places at the same time
            if (!future.isCancelled()) {
                future.cancel(false);
            }
        });
    }
}
