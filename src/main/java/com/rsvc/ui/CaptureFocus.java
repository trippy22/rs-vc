package com.rsvc.ui;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.beans.PropertyChangeListener;

import javax.swing.SwingUtilities;

/** Voice activation follows the RuneLite window, including its sidebar and owned dialogs. */
public final class CaptureFocus implements AutoCloseable {
    private final KeyboardFocusManager manager =
            KeyboardFocusManager.getCurrentKeyboardFocusManager();
    private final Window owner;
    private final Runnable lostFocus;
    private final PropertyChangeListener listener = event -> update();
    private volatile boolean active;
    private volatile boolean closed;

    /** Construct on the Swing event thread once the canvas belongs to the RuneLite window. */
    public CaptureFocus(Component canvas, Runnable lostFocus) {
        this.owner = SwingUtilities.getWindowAncestor(canvas);
        this.lostFocus = lostFocus;
        manager.addPropertyChangeListener("focusedWindow", listener);
        update();
    }

    private void update() {
        Window window = manager.getFocusedWindow();
        while (window != null && window != owner) {
            window = window.getOwner();
        }
        boolean next = !closed && owner != null && window == owner;
        boolean previous = active;
        active = next;
        if (previous && !next) {
            lostFocus.run();
        }
    }

    public boolean isActive() {
        return !closed && active;
    }

    @Override
    public void close() {
        closed = true;
        active = false;
        manager.removePropertyChangeListener("focusedWindow", listener);
    }
}
