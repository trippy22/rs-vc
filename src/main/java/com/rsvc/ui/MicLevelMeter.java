package com.rsvc.ui;

import com.rsvc.audio.NoiseGate;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.JComponent;

/** Microphone level bar with a marker where voice activation opens. Painted on the EDT. */
final class MicLevelMeter extends JComponent {
    static final double MIN_DB = -70;
    static final double MAX_DB = 0;
    private static final Color TRACK = new Color(45, 45, 45);
    private static final Color OPEN = new Color(85, 200, 130);
    private static final Color CLOSED = new Color(120, 120, 120);
    private static final Color MARKER = new Color(240, 200, 90);
    private NoiseGate.Level level;

    MicLevelMeter() {
        setPreferredSize(new Dimension(0, 8));
    }

    void level(NoiseGate.Level value) {
        level = value;
        repaint();
    }

    NoiseGate.Level level() {
        return level;
    }

    static int position(double db, int width) {
        double fraction = (Math.max(MIN_DB, Math.min(MAX_DB, db)) - MIN_DB) / (MAX_DB - MIN_DB);
        return (int) Math.round(fraction * width);
    }

    @Override
    protected void paintComponent(Graphics g) {
        int width = getWidth(), height = getHeight();
        g.setColor(TRACK);
        g.fillRect(0, 0, width, height);
        NoiseGate.Level current = level;
        if (current == null) {
            return;
        }
        g.setColor(current.open ? OPEN : CLOSED);
        g.fillRect(0, 0, position(current.levelDb, width), height);
        if (!Double.isNaN(current.thresholdDb)) {
            int x = Math.min(width - 2, position(current.thresholdDb, width));
            g.setColor(MARKER);
            g.fillRect(x, 0, 2, height);
        }
    }

    /** One-line description beside the meter. */
    static String describe(NoiseGate.Level value) {
        if (value == null) {
            return "Mic idle";
        }
        String level = "Mic " + Math.round(Math.max(-99, value.levelDb)) + " dB";
        if (Double.isNaN(value.thresholdDb)) {
            return level;
        }
        return level + " · sends above " + Math.round(value.thresholdDb) + " dB";
    }
}
