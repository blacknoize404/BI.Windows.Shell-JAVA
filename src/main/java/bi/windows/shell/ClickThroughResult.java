package bi.windows.shell;

/**
 * Cómo quedó una ventana tras pedirle que deje pasar el ratón: se lee de vuelta del sistema, no se da por hecho.
 *
 * @param applied      si el estado de la ventana es el que se pidió
 * @param clickThrough si la ventana deja pasar el ratón ahora mismo
 * @param error        por qué no se pudo, o {@code null}
 */
public record ClickThroughResult(boolean applied, boolean clickThrough, String error) {
}
