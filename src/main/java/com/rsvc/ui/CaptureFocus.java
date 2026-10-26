package com.rsvc.ui;

import java.awt.Component;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Voice activation follows the RuneLite window, including its sidebar and owned dialogs. */
public final class CaptureFocus implements AutoCloseable {
    private final Component canvas;
    private Window owner;
    private final Runnable lostFocus;
    // Accessed only on the Swing event thread.
    private final Set<Window> windows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final WindowAdapter listener =
            new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent event) {
                    refreshLater();
                }

                @Override
                public void windowLostFocus(WindowEvent event) {
                    refreshLater();
                }

                @Override
                public void windowClosed(WindowEvent event) {
                    refreshLater();
                }
            };
    // Owned dialogs can appear while the owner is unfocused, without an owner focus event.
    private final Timer discovery = new Timer(250, event -> refresh());
    private volatile boolean active;
    private volatile boolean closed;

    /** Construct on the Swing event thread; the canvas may attach to its window later. */
    public CaptureFocus(Component canvas, Runnable lostFocus) {
        this.canvas = canvas;
        this.lostFocus = lostFocus;
        refresh();
        discovery.start();
    }

    private void refreshLater() {
        // Evaluate the destination after a focus transfer, including newly opened dialogs.
        SwingUtilities.invokeLater(this::refresh);
    }

    private void collect(Window window, Set<Window> next) {
        if (window == null) {
            return;
        }
        if (window == owner || window.isDisplayable()) {
            next.add(window);
        }
        for (Window child : window.getOwnedWindows()) {
            collect(child, next);
        }
    }

    private void refresh() {
        if (closed) {
            return;
        }
        owner = SwingUtilities.getWindowAncestor(canvas);
        Set<Window> nextWindows = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(owner, nextWindows);
        windows.removeIf(
                window -> {
                    if (nextWindows.contains(window)) {
                        return false;
                    }
                    unlisten(window);
                    return true;
                });
        boolean next = false;
        for (Window window : nextWindows) {
            if (windows.add(window)) {
                window.addWindowFocusListener(listener);
                window.addWindowListener(listener);
            }
            next |= window.isFocused();
        }
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
        if (SwingUtilities.isEventDispatchThread()) {
            detach();
        } else {
            SwingUtilities.invokeLater(this::detach);
        }
    }

    private void unlisten(Window window) {
        window.removeWindowFocusListener(listener);
        window.removeWindowListener(listener);
    }

    private void detach() {
        discovery.stop();
        windows.forEach(this::unlisten);
        windows.clear();
    }
}
