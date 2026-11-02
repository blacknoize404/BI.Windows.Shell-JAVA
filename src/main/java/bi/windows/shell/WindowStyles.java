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
