package com.afs.guard;

/** Analytic damped spring. Retargeting preserves velocity, including during a drag. */
final class DockSpring {
    double value, velocity, target;
    double stiffness, damping;

    DockSpring(double value, double stiffness, double damping) {
        this.value = this.target = value;
        tune(stiffness, damping);
    }

    void tune(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    void snap(double value) {
        this.value = this.target = value;
        velocity = 0;
    }

    boolean advance(double seconds) {
        if (seconds <= 0) return moving();
        double w = Math.sqrt(stiffness), decay = w * damping;
        double x = value - target, v = velocity;
        if (damping < .999) {
            double frequency = w * Math.sqrt(1 - damping * damping);
            double b = (v + decay * x) / frequency;
            double cos = Math.cos(frequency * seconds), sin = Math.sin(frequency * seconds);
            double envelope = Math.exp(-decay * seconds);
            value = target + envelope * (x * cos + b * sin);
            velocity = envelope * ((-decay * x + b * frequency) * cos
                    + (-decay * b - x * frequency) * sin);
        } else {
            double b = v + w * x, envelope = Math.exp(-w * seconds);
            value = target + (x + b * seconds) * envelope;
            velocity = (v - w * b * seconds) * envelope;
        }
        if (!moving()) snap(target);
        return moving();
    }

    boolean moving() {
        return Math.abs(value - target) > .0005 || Math.abs(velocity) > .003;
    }
}
