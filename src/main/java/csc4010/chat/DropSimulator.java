package csc4010.chat;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Probabilistically drops packets to mimic unreliable links.
 */
public final class DropSimulator {
    private final AtomicReference<Double> inbound = new AtomicReference<>(0.0d);
    private final AtomicReference<Double> outbound = new AtomicReference<>(0.0d);
    private final Random random = new SecureRandom();

    public DropSimulator(double inboundRate, double outboundRate) {
        inbound.set(clamp(inboundRate));
        outbound.set(clamp(outboundRate));
    }

    public boolean shouldDropInbound() {
        return random.nextDouble() < inbound.get();
    }

    public boolean shouldDropOutbound() {
        return random.nextDouble() < outbound.get();
    }

    public void setInbound(double rate) {
        inbound.set(clamp(rate));
    }

    public void setOutbound(double rate) {
        outbound.set(clamp(rate));
    }

    public double inboundRate() {
        return inbound.get();
    }

    public double outboundRate() {
        return outbound.get();
    }

    private static double clamp(double rate) {
        if (rate < 0.0d) {
            return 0.0d;
        }
        if (rate > 1.0d) {
            return 1.0d;
        }
        return rate;
    }
}
