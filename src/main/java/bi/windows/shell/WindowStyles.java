package bi.windows.shell;

/**
 * Estilos de ventana de Windows que ni JavaFX ni Swing exponen. De momento, hacer que una ventana deje
 * pasar el ratón a lo que hay debajo (un widget sobre el escritorio, una superposición).
 * <p>El HWND de una ventana de Java se obtiene con {@link WindowHandles#findOwnWindow(String)}.
 */
public final class WindowStyles {

    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TRANSPARENT = 0x00000020L;
    private static final long WS_EX_LAYERED = 0x00080000L;
    private static final long WS_EX_NOACTIVATE = 0x08000000L;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L;
    private static final long WS_EX_APPWINDOW = 0x00040000L;
    private static final int SW_HIDE = 0;
    private static final int SW_SHOWNA = 8;

    private WindowStyles() {
    }

    /**
     * Hace que la ventana deje pasar el ratón ({@code WS_EX_TRANSPARENT}) o que vuelva a recibirlo.
     * <p>Al activarlo también se marca como capa ({@code WS_EX_LAYERED}, que ya tienen las ventanas
     * translúcidas de JavaFX) y como «sin activar» ({@code WS_EX_NOACTIVATE}) para que no robe el foco; al
     * desactivarlo se quitan el primero y el último y la capa se deja como estaba.
     * <p>Una ventana translúcida solo recibe el ratón donde sus píxeles no son del todo transparentes
     * (alfa 0): es normal que una ventana sin sombra ni fondo en una zona no reciba clics en ella aunque no
     * esté en este modo.
     *
     * @param hwnd    la ventana; 0 si todavía no existe
     * @param enabled true para que los clics la atraviesen
     */
    public static ClickThroughResult setClickThrough(long hwnd, boolean enabled) {
        if (!Native.WINDOWS) return new ClickThroughResult(false, false, "no es Windows");
        if (hwnd == 0) return new ClickThroughResult(false, false, "no hay ventana (¿todavía no se mostró?)");
        try {
            long style = (long) Native.Win.GET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE);
            long wanted = enabled
                    ? style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE
                    : style & ~(WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
            if (wanted != style) {
                // invokeExact exige recoger el valor devuelto con su tipo exacto, aunque no interese
                long previous = (long) Native.Win.SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE, wanted);
            }
            boolean now = isClickThrough(hwnd);
            Native.emit("WindowStyles.ClickThrough", "requested", enabled, "applied", now == enabled, "styleBefore", style, "styleAfter", wanted);
            return new ClickThroughResult(now == enabled, now, null);
        } catch (Throwable t) {
            return new ClickThroughResult(false, false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Quita la ventana de la barra de tareas y de Alt+Tab ({@code WS_EX_TOOLWINDOW}) o la devuelve a ellas.
     * <p>Windows solo lee ese estilo al mostrar la ventana, así que si está visible se oculta un instante y
     * se vuelve a mostrar sin activarla; no hace nada si el estilo ya es el pedido.
     *
     * @param hwnd   la ventana; 0 si todavía no existe
     * @param hidden true para que no aparezca como aplicación
     */
    public static ClickThroughResult setHiddenFromTaskbar(long hwnd, boolean hidden) {
        if (!Native.WINDOWS) return new ClickThroughResult(false, false, "no es Windows");
        if (hwnd == 0) return new ClickThroughResult(false, false, "no hay ventana (¿todavía no se mostró?)");
        try {
            long style = (long) Native.Win.GET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE);
            long wanted = hidden
                    ? (style | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW
                    : style & ~WS_EX_TOOLWINDOW;
            if (wanted != style) {
                boolean visible = (int) Native.Win.IS_WINDOW_VISIBLE.invokeExact(hwnd) != 0;
                if (visible) {
                    int ignored = (int) Native.Win.SHOW_WINDOW.invokeExact(hwnd, SW_HIDE);
                }
                long previous = (long) Native.Win.SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE, wanted);
                if (visible) {
                    int ignored = (int) Native.Win.SHOW_WINDOW.invokeExact(hwnd, SW_SHOWNA);
                }
            }
            boolean now = isHiddenFromTaskbar(hwnd);
            Native.emit("WindowStyles.HiddenFromTaskbar", "requested", hidden, "applied", now == hidden, "styleBefore", style, "styleAfter", wanted);
            return new ClickThroughResult(now == hidden, now, null);
        } catch (Throwable t) {
            return new ClickThroughResult(false, false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Si la ventana está marcada como de herramientas (sin botón en la barra de tareas). */
    public static boolean isHiddenFromTaskbar(long hwnd) {
        if (!Native.WINDOWS || hwnd == 0) return false;
        try {
            long style = (long) Native.Win.GET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE);
            return (style & WS_EX_TOOLWINDOW) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Si la ventana deja pasar el ratón ahora mismo; {@code false} si no existe o no es Windows. */
    public static boolean isClickThrough(long hwnd) {
        if (!Native.WINDOWS || hwnd == 0) return false;
        try {
            long style = (long) Native.Win.GET_WINDOW_LONG_PTR.invokeExact(hwnd, GWL_EXSTYLE);
            return (style & WS_EX_TRANSPARENT) != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
