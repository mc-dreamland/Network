package com.nukkitx.network.raknet;

import static com.nukkitx.network.raknet.RakNetConstants.*;

public class RakNetSlidingWindow {
    private final int mtu;
    private double cwnd;
    private double ssThresh;
    private double estimatedRTT = -1;
    private double lastRTT = -1;
    private double deviationRTT = -1;
    private long oldestUnsentAck;
    private long nextCongestionControlBlock;
    private boolean backoffThisBlock;
    private boolean isContinuousSend;
    private int expectedNextSequenceNumber;
    private int nextSequenceNumber;

    public RakNetSlidingWindow(int mtu) {
        this.mtu = mtu;
        this.cwnd = mtu;
    }

    public int getRetransmissionBandwidth(int unAckedBytes, boolean isContinuousSend) {
        return unAckedBytes;
    }

    public int getTransmissionBandwidth(int unAckedBytes, boolean isContinuousSend) {
        this.isContinuousSend = isContinuousSend;
        if (unAckedBytes <= this.cwnd) {
            return (int) (this.cwnd - unAckedBytes);
        } else {
            return 0;
        }
    }

    public int onPacketReceived(long curTime, int sequenceNumber) {
        if (this.oldestUnsentAck == 0) {
            this.oldestUnsentAck = curTime;
        }
        int skippedMessageCount = 0;
        if (sequenceNumber == expectedNextSequenceNumber){
            expectedNextSequenceNumber = sequenceNumber + 1;
        } else if (sequenceNumber > expectedNextSequenceNumber){
            skippedMessageCount = sequenceNumber - expectedNextSequenceNumber;
            if (skippedMessageCount > 1000){
                if (skippedMessageCount > 50000){
                    return -1;
                }
                skippedMessageCount = 1000;
            }
            expectedNextSequenceNumber = sequenceNumber + 1;
        }
        return skippedMessageCount;
    }

    public void onResend(long curSequenceIndex) {
        if (this.isContinuousSend && !this.backoffThisBlock && this.cwnd > this.mtu * 2) {
            this.ssThresh = this.cwnd / 2D;

            if (this.ssThresh < this.mtu) {
                this.ssThresh = this.mtu;
            }
            this.cwnd = this.mtu;

            this.nextCongestionControlBlock = curSequenceIndex;
            this.backoffThisBlock = true;
        }
    }

    public void onNak() {
        if (isContinuousSend && !this.backoffThisBlock) {
            this.ssThresh = this.cwnd / 2D;
        }
    }

    public int getAndIncrementNextSequenceNumber(){
        return nextSequenceNumber++;
    }

    public void onAck(long rtt, long sequenceIndex, boolean isContinuousSend) {
        this.lastRTT = rtt;

        if (this.estimatedRTT == -1) {
            this.estimatedRTT = rtt;
            this.deviationRTT = rtt;
        } else {
            double d = 0.05D;
            double difference = rtt - this.estimatedRTT;
            this.estimatedRTT += d * difference;
            this.deviationRTT += d * (Math.abs(difference) - this.deviationRTT);
        }

        this.isContinuousSend = isContinuousSend;
        if (!isContinuousSend) {
            return;
        }

        boolean isNewCongestionControlPeriod = sequenceIndex > this.nextCongestionControlBlock;

        if (isNewCongestionControlPeriod) {
            this.backoffThisBlock = false;
            this.nextCongestionControlBlock = nextSequenceNumber;
        }

        if (this.isInSlowStart()) {
            this.cwnd += this.mtu;

            if (this.cwnd > this.ssThresh && this.ssThresh != 0) {
                this.cwnd = this.ssThresh + this.mtu * this.mtu / this.cwnd;
            }
        } else if (isNewCongestionControlPeriod) {
            this.cwnd += this.mtu * this.mtu / this.cwnd;
        }
    }

    public boolean isInSlowStart() {
        return this.cwnd <= this.ssThresh || this.ssThresh == 0;
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
}
