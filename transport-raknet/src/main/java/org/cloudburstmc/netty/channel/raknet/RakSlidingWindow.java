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

import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RakSlidingWindow {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakSlidingWindow.class);

    private final int mtu;
    private double cwnd;
    private double ssThresh;
    private double estimatedRTT = -1;
    private double lastRTT = -1;
    private double deviationRTT = -1;
    private long oldestUnsentAck;
    private long nextCongestionControlBlock;
    private boolean backoffThisBlock;
    private int unackedBytes;

    // BBR-like variables
    private long minRtt = -1;
    private long minRttTimestamp = 0;
    private double maxBw = 0;
    private long totalBytesAcked = 0;

    // Window tracking for BBR
    // 10 seconds minRTT window
    private static final long MIN_RTT_WINDOW = 10000;
    // Max BW window - simplistic approach: decay or sliding window
    // We will use a time-decaying max filter for simplicity and lower memory
    // overhead
    private static final long BW_WINDOW = 10000;
    private long maxBwTimestamp = 0;

    // Ring Buffer for BBR state tracking (Size must be power of 2)
    private static final int STATE_WINDOW_SIZE = 4096;
    private static final int STATE_WINDOW_MASK = STATE_WINDOW_SIZE - 1;

    // Arrays to store state without object allocation overhead
    private final int[] stateSequenceIds = new int[STATE_WINDOW_SIZE];
    private final long[] stateBytesAcked = new long[STATE_WINDOW_SIZE];
    private final Object stateLock = new Object();

    public RakSlidingWindow(int mtu) {
        this.mtu = mtu;
        this.cwnd = mtu * 2; // Initial CWND

        // Initialize sequence ids to -1 as 0 is a valid sequence
        java.util.Arrays.fill(this.stateSequenceIds, -1);
    }

    public int getRetransmissionBandwidth() {
        return unackedBytes;
    }

    public int getTransmissionBandwidth() {
        if (this.unackedBytes <= this.cwnd) {
            return (int) (this.cwnd - this.unackedBytes);
        } else {
            return 0;
        }
    }

    public void onPacketReceived(long curTime) {
        if (this.oldestUnsentAck == 0) {
            this.oldestUnsentAck = curTime;
        }
    }

    public void onResend(long curSequenceIndex) {
        // In BBR, we don't necessarily cut CWND on loss/resend, but we might want to
        // ensure we aren't flooding.
        // For mobile/lossy networks, ignore loss as congestion signal.
        if (log.isDebugEnabled()) {
            log.debug("[RakOne] Resend packet {}, unacked: {}, cwnd: {}", curSequenceIndex, unackedBytes, (int) cwnd);
        }
    }

    public void onNak() {
        // Normal TCP (Reno/CUBIC) reduces window here.
        // BBR ignores this as congestion signal, assuming it's random loss.
        // We log it for debugging.
        if (log.isDebugEnabled()) {
            log.debug("[RakOne] NAK received. Ignoring for congestion control (Wireless/Lossy optimization).");
        }
    }

    public void onAck(long curTime, RakDatagramPacket datagram, long curSequenceIndex) {
        long rtt = curTime - datagram.getSendTime();
        this.lastRTT = rtt;
        this.unackedBytes -= datagram.getSize();
        if (this.unackedBytes < 0)
            this.unackedBytes = 0;

        // Update total bytes acked (approximated contribution)
        this.totalBytesAcked += datagram.getSize();

        // Update RTT stats (Standard TCP parts for RTO calculation)
        if (this.estimatedRTT == -1) {
            this.estimatedRTT = rtt;
            this.deviationRTT = rtt;
        } else {
            double d = 0.05D;
            double difference = rtt - this.estimatedRTT;
            this.estimatedRTT += d * difference;
            this.deviationRTT += d * (Math.abs(difference) - this.deviationRTT);
        }

        // --- BBR Logic ---

        // 1. Update MinRTT
        if (this.minRtt == -1 || rtt < this.minRtt || (curTime - this.minRttTimestamp > MIN_RTT_WINDOW)) {
            this.minRtt = rtt;
            this.minRttTimestamp = curTime;
        }

        // 2. Estimate Bandwidth
        Long ackedAtSend = null;
        int seq = datagram.getSequenceIndex();
        int idx = seq & STATE_WINDOW_MASK;

        synchronized (stateLock) {
            if (this.stateSequenceIds[idx] == seq) {
                ackedAtSend = this.stateBytesAcked[idx];
                this.stateSequenceIds[idx] = -1; // Mark as consumed
            }
        }

        if (ackedAtSend != null) {
            long delivered = this.totalBytesAcked - ackedAtSend;
            // Avoid division by zero
            long interval = rtt;
            if (interval < 1)
                interval = 1;

            double deliveryRate = (double) delivered / interval; // bytes per ms

            // Update Max BW
            if (this.maxBw == 0 || deliveryRate > this.maxBw) {
                this.maxBw = deliveryRate;
                this.maxBwTimestamp = curTime;
            } else if (curTime - this.maxBwTimestamp > BW_WINDOW) {
                // Decay instead of hard reset to avoid sudden BW drops
                // caused by application-limited periods (e.g. standing still)
                this.maxBw = Math.max(deliveryRate, this.maxBw * 0.85);
                this.maxBwTimestamp = curTime;
            }
        }

        // 3. Update CWND
        // BDP = Bandwidth * Delay
        // CWND = Gain * BDP.
        // Standard BBR Gain is ~2.0 for startup, 1.0-1.25 for cruise.
        // We stick to 2.0 to be safe and responsive.
        if (this.maxBw > 0 && this.minRtt > 0) {
            double targetCwnd = this.maxBw * this.minRtt * 2.0D;
            // Ensure minimal window (at least MSS)
            if (targetCwnd < this.mtu)
                targetCwnd = this.mtu;

            // Smooth CWND update? Or jump? BBR model says jump to target.
            this.cwnd = targetCwnd;
        }

        if (log.isDebugEnabled()) {
            log.debug("[RakOne] ACK: RTT={}, MinRTT={}, BW={} (kB/s), CWND={}, Unacked={}",
                    rtt, minRtt, String.format("%.4f", maxBw), String.format("%.2f", cwnd), unackedBytes);
        }
    }

    public void onReliableSend(RakDatagramPacket datagram) {
        this.unackedBytes += datagram.getSize();
        // Record state at send time for BBR using Ring Buffer
        int seq = datagram.getSequenceIndex();
        int idx = seq & STATE_WINDOW_MASK;

        synchronized (stateLock) {
            this.stateSequenceIds[idx] = seq;
            this.stateBytesAcked[idx] = this.totalBytesAcked;
        }
    }

    public boolean isInSlowStart() {
        return false;
    }

    public void onSendAck() {
        this.oldestUnsentAck = 0;
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    public long getRtoForRetransmission() {
        if (this.estimatedRTT == -1) {
            return CC_MAXIMUM_THRESHOLD;
        }

        long threshold = (long) ((2.0D * this.estimatedRTT + 4.0D * this.deviationRTT) + CC_ADDITIONAL_VARIANCE);

        return threshold > CC_MAXIMUM_THRESHOLD ? CC_MAXIMUM_THRESHOLD : threshold;
    }

    public double getRTT() {
        return this.estimatedRTT;
    }

    public boolean shouldSendAcks(long curTime) {
        long rto = this.getSenderRtoForAck();

        return rto == -1 || curTime >= this.oldestUnsentAck + CC_SYN;
    }

    public long getSenderRtoForAck() {
        if (this.lastRTT == -1) {
            return -1;
        } else {
            return (long) (this.lastRTT + CC_SYN);
        }
    }

    public int getUnackedBytes() {
        return unackedBytes;
    }

}
