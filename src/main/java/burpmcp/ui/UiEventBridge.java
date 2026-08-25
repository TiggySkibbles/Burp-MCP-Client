package burpmcp.ui;

import javax.swing.SwingUtilities;
import java.util.function.Consumer;

/** Centralizes the "always cross to the EDT" rule so individual panels never need to remember it. */
public final class UiEventBridge {

    private UiEventBridge() {
    }

    public static void post(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    public static <T> void post(T value, Consumer<T> consumer) {
        post(() -> consumer.accept(value));
    }
}
