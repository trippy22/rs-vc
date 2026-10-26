package com.rsvc.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;

/** Small cached vector icons for self-selected controls, independent of local peer mutes. */
final class PeerStatusIcon implements Icon {
    private static final Icon MIC = new PeerStatusIcon(true, false);
    private static final Icon DEAF = new PeerStatusIcon(false, true);
    private static final Icon BOTH = new PeerStatusIcon(true, true);
    private final boolean muted, deaf;

    static Icon of(boolean muted, boolean deaf) {
        return muted ? (deaf ? BOTH : MIC) : deaf ? DEAF : null;
    }

    private PeerStatusIcon(boolean muted, boolean deaf) {
        this.muted = muted;
        this.deaf = deaf;
    }

    public int getIconWidth() {
        return muted && deaf ? 30 : 14;
    }

    public int getIconHeight() {
        return 14;
    }

    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (muted) {
                g.setColor(c.getForeground());
                g.drawRoundRect(5, 1, 4, 7, 3, 3);
                g.drawArc(3, 4, 8, 7, 180, 180);
                g.drawLine(7, 11, 7, 13);
                slash(g);
                if (deaf) {
                    g.translate(16, 0);
                }
            }
            if (deaf) {
                g.setColor(c.getForeground());
                g.drawArc(2, 2, 10, 10, 0, 180);
                g.drawRoundRect(2, 7, 3, 5, 2, 2);
                g.drawRoundRect(9, 7, 3, 5, 2, 2);
                slash(g);
            }
        } finally {
            g.dispose();
        }
    }

    private static void slash(Graphics2D g) {
        g.setColor(new Color(225, 120, 120));
        g.drawLine(1, 1, 13, 13);
    }
}
