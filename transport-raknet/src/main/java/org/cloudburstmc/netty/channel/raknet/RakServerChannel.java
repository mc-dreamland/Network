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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import org.cloudburstmc.netty.channel.proxy.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPongEncoder;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOfflineHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerTailHandler;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RakServerChannel extends ProxyChannel<DatagramChannel> implements ServerChannel {

    private final RakServerChannelConfig config;
    private final Map<SocketAddress, RakChildChannel> childChannelMap = new ConcurrentHashMap<>();
    private final Consumer<RakChannel> childConsumer;

    public RakServerChannel(DatagramChannel channel) {
        this(channel, null);
    }

    public RakServerChannel(DatagramChannel channel, Consumer<RakChannel> childConsumer) {
        super(channel);
        this.childConsumer = childConsumer;
        this.config = new DefaultRakServerConfig(this);
        // Default common handler of offline phase. Handles only raknet packets, forwards rest.
        this.pipeline().addLast(UnconnectedPongEncoder.NAME, UnconnectedPongEncoder.INSTANCE);
        this.pipeline().addLast(RakServerRateLimiter.NAME, new RakServerRateLimiter(this));
        this.pipeline().addLast(RakServerOfflineHandler.NAME, new RakServerOfflineHandler(this));
        this.pipeline().addLast(RakServerRouteHandler.NAME, new RakServerRouteHandler(this));
        this.pipeline().addLast(RakServerTailHandler.NAME, RakServerTailHandler.INSTANCE);
    }

    /**
     * Create new child channel assigned to remote address.
     *
     * @param address remote address of new connection.
     * @return RakChildChannel instance of new channel.
     */
    public RakChildChannel createChildChannel(InetSocketAddress address, long clientGuid, int protocolVersion, int mtu) {
        if (this.childChannelMap.containsKey(address)) {
            return null;
        }

        RakChildChannel channel = new RakChildChannel(address, this, clientGuid, protocolVersion, mtu, childConsumer);
        channel.closeFuture().addListener((GenericFutureListener<ChannelFuture>) this::onChildClosed);
        // Fire channel thought ServerBootstrap,
        // register to eventLoop, assign default options and attributes
        this.pipeline().fireChannelRead(channel).fireChannelReadComplete();
        this.childChannelMap.put(address, channel);

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelOpen(address);
        }
        return channel;
    }

    public RakChildChannel getChildChannel(SocketAddress address) {
        return this.childChannelMap.get(address);
    }

    private void onChildClosed(ChannelFuture channelFuture) {
        RakChildChannel channel = (RakChildChannel) channelFuture.channel();
        this.childChannelMap.remove(channel.remoteAddress());

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelClose(channel.remoteAddress());
        }

        channel.rakPipeline().fireChannelInactive();
        channel.rakPipeline().fireChannelUnregistered();
        // Need to use reflection to destroy pipeline because
        // DefaultChannelPipeline.destroy() is only called when channel.isOpen() is false,
        // but the method is called on parent channel, and there is no other way to destroy pipeline.
        RakUtils.destroyChannelPipeline(channel.rakPipeline());
    }

    @Override
    public void onCloseTriggered(ChannelPromise promise) {
        PromiseCombiner combiner = new PromiseCombiner(this.eventLoop());
        this.childChannelMap.values().forEach(channel -> combiner.add(channel.close()));

        ChannelPromise combinedPromise = this.newPromise();
        combinedPromise.addListener(future -> super.onCloseTriggered(promise));
        combiner.finish(combinedPromise);
    }

    public boolean tryBlockAddress(InetAddress address, long time, TimeUnit unit) {
        RakServerRateLimiter rateLimiter = this.pipeline().get(RakServerRateLimiter.class);
        if (rateLimiter != null) {
            return rateLimiter.blockAddress(address, time, unit);
        }
        return false;
    }

    @Override
    public RakServerChannelConfig config() {
        return this.config;
    }
}
