package com.rsvc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Offline layout preview only: dummy callbacks, no game/client, network or audio devices. */
public final class VoicePanelPreview
{
    private static void layout(Container parent)
    {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container) layout((Container) child);
    }

    public static void main(String[] args) throws Exception
    {
        VoicePanel[] panels = new VoicePanel[3];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < panels.length; i++) {
                VoicePanel panel = new VoicePanel((channel, password) -> {}, () -> {}, muted -> {}, deaf -> {}, (id, mute) -> {}, () -> {});
                panels[i] = panel;
                panel.status(i == 0 ? "Connected — hold push-to-talk to speak" : "Disconnected — check channel, password and relay; join again");
                panel.peers(Arrays.asList(new VoiceConnection.Peer(1, "LongName1234"), new VoiceConnection.Peer(2, "Bob"),
                    new VoiceConnection.Peer(3, "Charlie"), new VoiceConnection.Peer(4, "David"), new VoiceConnection.Peer(5, "Emma")));
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage image = new BufferedImage(726, 500, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            for (int i = 0; i < panels.length; i++) {
                JPanel wrapped = panels[i].getWrappedPanel(); wrapped.setSize(242, i == 2 ? 400 : 500);
                layout(wrapped); layout(wrapped);
                Graphics2D part = (Graphics2D) graphics.create(i * 242, 0, 242, 500); wrapped.printAll(part); part.dispose();
            }
            graphics.dispose();
            try { ImageIO.write(image, "png", new File(args[0])); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
