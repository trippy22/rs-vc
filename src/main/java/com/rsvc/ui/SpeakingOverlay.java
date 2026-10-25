package com.rsvc.ui;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;

/** Only projects a short cached list of active speakers; never scans or hashes the scene. */
public final class SpeakingOverlay extends Overlay {
    private static final BufferedImage ICON = createIcon();
    private final Client client;
    private final Supplier<List<Player>> speakers;

    public SpeakingOverlay(Client client, Supplier<List<Player>> speakers) {
        this.client = client;
        this.speakers = speakers;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Player self = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || self == null) {
            return null;
        }
        for (Player player : speakers.get()) {
            if (player.getWorldView() != self.getWorldView()
                    || player.getWorldLocation().getPlane() != self.getWorldLocation().getPlane()) {
                continue;
            }
            Point point = player.getCanvasImageLocation(ICON, player.getLogicalHeight() + 28);
            if (point != null) {
                graphics.drawImage(ICON, point.getX(), point.getY(), null);
            }
        }
        return null;
    }

    static BufferedImage createIcon() {
        BufferedImage icon = new BufferedImage(20, 22, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(15, 35, 24, 230));
            g.fillRoundRect(0, 0, 20, 18, 7, 7);
            g.fillPolygon(new int[] {7, 13, 10}, new int[] {16, 16, 22}, 3);
            g.setColor(new Color(95, 245, 145));
            g.fillRoundRect(4, 6, 3, 6, 2, 2);
            g.fillRoundRect(9, 3, 3, 12, 2, 2);
            g.fillRoundRect(14, 5, 3, 8, 2, 2);
        } finally {
            g.dispose();
        }
        return icon;
    }
}
