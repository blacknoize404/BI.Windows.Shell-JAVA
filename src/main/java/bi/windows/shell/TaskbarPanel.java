package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Un panel propio dentro de la barra de tareas de Windows, en el hueco libre junto a la bandeja: el mismo
 * mecanismo que usan los monitores de red que se muestran en la barra.
 * <p>Es una ventana nativa hija de la barra, creada aquí, que solo muestra una imagen con transparencia por
 * píxel, y avisa del clic derecho para que la aplicación abra su menú. No se incrusta la ventana de la aplicación: un toolkit (JavaFX, Swing) recoloca y repinta su ventana
 * con coordenadas de pantalla y, dentro de la barra, eso la hace saltar y parpadear.
 * <p>La imagen se da en píxeles físicos, ARGB premultiplicado, fila a fila de arriba abajo. Los píxeles del todo
 * transparentes no reciben el ratón: para que todo el panel responda, el fondo debe tener una opacidad mínima.
 * Hay que crearla y actualizarla desde un hilo con bucle de mensajes (el de la
 * interfaz de la aplicación).
 */
public final class TaskbarPanel implements AutoCloseable {

    private static final int WS_CHILD = 0x40000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_CLIPSIBLINGS = 0x04000000;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final long HWND_TOP = 0L;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final int ULW_ALPHA = 0x2;
    private static final int AC_SRC_ALPHA = 0x1;
    private static final int GWLP_WNDPROC = -4;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_MOUSEACTIVATE = 0x0021;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_CONTEXTMENU = 0x007B;
    private static final long HTCLIENT = 1;
    private static final long MA_NOACTIVATE = 3;
    /** Paneles vivos por ventana, para el procedimiento de ventana común. */
    private static final Map<Long, TaskbarPanel> PANELS = new ConcurrentHashMap<>();
    /** Procedimiento original de cada panel; vive hasta WM_NCDESTROY, aunque el panel ya se haya cerrado. */
    private static final Map<Long, Long> ORIGINALS = new ConcurrentHashMap<>();
    private static final int WM_NCDESTROY = 0x0082;
    private static MemorySegment wndProcStub;

    /** Separación entre el panel y la bandeja, en píxeles físicos. */
    private static final int MARGIN = 4;

    private long hwnd;
    private long parent;
    private int[] lastRect;
    private long originalProc;
    private volatile BiConsumer<Integer, Integer> onContextMenu;

    private TaskbarPanel() {
    }

    /**
     * Crea el panel, todavía sin imagen.
     *
     * @return el panel; null si no es Windows o no hay barra de tareas
     */
    public static TaskbarPanel create() {
        if (!Native.WINDOWS) return null;
        TaskbarPanel panel = new TaskbarPanel();
        return panel.ensureWindow() ? panel : null;
    }

    /**
     * Hueco libre de la barra: {ancho entre la lista de aplicaciones y la bandeja, alto de la barra}, en píxeles
     * físicos.
     *
     * @return el hueco; null si no es Windows o no hay barra
     */
    public static int[] slot() {
        if (!Native.WINDOWS) return null;
        try (Arena arena = Arena.ofConfined()) {
            Layout layout = layout(arena);
            return layout == null ? null : new int[]{Math.max(0, layout.right - layout.left), layout.height};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Si la barra de tareas usa el tema claro (texto oscuro). Sirve para elegir el color de lo que se pinta.
     */
    public static boolean isTaskbarLight() {
        if (!Native.WINDOWS) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(JAVA_INT);
            MemorySegment size = arena.allocate(JAVA_INT);
            size.set(JAVA_INT, 0, 4);
            int status = (int) Native.Win.REG_GET_VALUE.invokeExact(0xFFFFFFFF80000001L,
                    Native.wide(arena, "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
                    Native.wide(arena, "SystemUsesLightTheme"), 0x10, MemorySegment.NULL, data, size);
            return status == 0 && data.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Muestra la imagen en la barra, pegada a la bandeja y centrada en alto. Si la barra se ha recreado (el
     * explorador se reinició) el panel se vuelve a crear. La ventana solo se mueve si cambia su sitio.
     *
     * @param argbPremultiplied píxeles ARGB premultiplicados, {@code width * height}
     * @return true si se mostró
     */
    public boolean show(int[] argbPremultiplied, int width, int height) {
        return show(argbPremultiplied, width, height, MARGIN);
    }

    /**
     * Como {@link #show(int[], int, int)}, con la separación hasta la bandeja que se quiera.
     *
     * @param trayMargin píxeles físicos entre el panel y la bandeja; negativo para meterse en su margen
     */
    public boolean show(int[] argbPremultiplied, int width, int height, int trayMargin) {
        if (width <= 0 || height <= 0 || argbPremultiplied.length < width * height) return false;
        if (!ensureWindow()) return false;
        try (Arena arena = Arena.ofConfined()) {
            Layout layout = layout(arena);
            if (layout == null) return false;
            int h = Math.min(height, layout.height);
            int x = Math.max(layout.left, layout.right - trayMargin - width);
            int y = (layout.height - h) / 2;
            int[] rect = {x, y, width, h};
            if (lastRect == null || !java.util.Arrays.equals(rect, lastRect)) {
                int ok = (int) Native.Win.SET_WINDOW_POS.invokeExact(hwnd, HWND_TOP, x, y, width, h,
                        SWP_NOACTIVATE | SWP_SHOWWINDOW);
                if (ok == 0) return false;
                lastRect = rect;
            } else {
                // La barra reordena sus hijas (la lista de aplicaciones se repinta encima): el panel vuelve arriba.
                // Con capas no se repinta, así que no parpadea.
                int ignored = (int) Native.Win.SET_WINDOW_POS.invokeExact(hwnd, HWND_TOP, 0, 0, 0, 0,
                        SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE);
            }
            return paint(arena, argbPremultiplied, width, height, h);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Lo que se hace con el clic derecho sobre el panel: recibe la posición del ratón en pantalla, en píxeles
     * físicos. Se llama en el hilo que creó el panel.
     */
    public void setOnContextMenu(BiConsumer<Integer, Integer> handler) {
        onContextMenu = handler;
    }

    /** Quita el panel de la barra. Se puede volver a mostrar con {@link #show}. */
    @Override
    public void close() {
        if (hwnd != 0) {
            PANELS.remove(hwnd);
            try {
                int ignored = (int) Native.Win.DESTROY_WINDOW.invokeExact(hwnd);
            } catch (Throwable ignored) {
                // ya no existía
            }
        }
        hwnd = 0;
        parent = 0;
        lastRect = null;
    }

    private boolean ensureWindow() {
        try (Arena arena = Arena.ofConfined()) {
            long tray = (long) Native.Win.FIND_WINDOW_W.invokeExact(Native.wide(arena, "Shell_TrayWnd"), MemorySegment.NULL);
            if (tray == 0) return false;
            if (hwnd != 0 && tray == parent && (int) Native.Win.IS_WINDOW.invokeExact(hwnd) != 0) return true;
            close();
            long instance = (long) Native.Win.GET_MODULE_HANDLE.invokeExact(MemorySegment.NULL);
            // Una ventana STATIC basta: con capas y la imagen dada con UpdateLayeredWindow no hay que pintar nada
            hwnd = (long) Native.Win.CREATE_WINDOW_EX.invokeExact(
                    WS_EX_LAYERED | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
                    Native.wide(arena, "STATIC"), Native.wide(arena, ""),
                    WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS, 0, 0, 1, 1, tray, 0L, instance, MemorySegment.NULL);
            if (hwnd == 0) return false;
            parent = tray;
            // La ventana STATIC no recibe el ratón por sí misma: se le pone un procedimiento propio delante
            MemorySegment proc = wndProc();
            if (proc != null) {
                PANELS.put(hwnd, this);
                originalProc = (long) Native.Win.SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWLP_WNDPROC, proc.address());
                ORIGINALS.put(hwnd, originalProc);
            }
            return true;
        } catch (Throwable t) {
            hwnd = 0;
            return false;
        }
    }

    private static synchronized MemorySegment wndProc() {
        if (wndProcStub == null) {
            try {
                var target = MethodHandles.lookup().findStatic(TaskbarPanel.class, "onMessage",
                        MethodType.methodType(long.class, long.class, int.class, long.class, long.class));
                wndProcStub = Native.Win.LINKER.upcallStub(target,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), Arena.global());
            } catch (Throwable t) {
                return null;
            }
        }
        return wndProcStub;
    }

    /** Procedimiento de ventana del panel: recibe el ratón sin activarse y avisa del clic derecho. */
    private static long onMessage(long hwnd, int message, long wParam, long lParam) {
        TaskbarPanel panel = PANELS.get(hwnd);
        try {
            if (panel != null) {
                switch (message) {
                    case WM_NCHITTEST:
                        return HTCLIENT;
                    case WM_MOUSEACTIVATE:
                        return MA_NOACTIVATE;
                    case WM_CONTEXTMENU:
                        return 0;
                    case WM_RBUTTONUP:
                        BiConsumer<Integer, Integer> handler = panel.onContextMenu;
                        if (handler != null) {
                            try (Arena arena = Arena.ofConfined()) {
                                MemorySegment point = arena.allocate(8, 4);
                                int ok = (int) Native.Win.GET_CURSOR_POS.invokeExact(point);
                                if (ok != 0) handler.accept(point.get(JAVA_INT, 0), point.get(JAVA_INT, 4));
                            }
                        }
                        return 0;
                    default:
                        break;
                }
            }
            Long original = message == WM_NCDESTROY ? ORIGINALS.remove(hwnd) : ORIGINALS.get(hwnd);
            if (original != null && original != 0) {
                return (long) Native.Win.CALL_WINDOW_PROC.invokeExact((long) original, hwnd, message, wParam, lParam);
            }
        } catch (Throwable ignored) {
            // un fallo aquí no puede salir hacia Windows
        }
        return 0;
    }

    /** Copia la imagen (recortada al alto {@code shown}) a un mapa de bits y se lo da a la ventana. */
    private boolean paint(Arena arena, int[] pixels, int width, int height, int shown) throws Throwable {
        long screen = (long) Native.Win.GET_DC.invokeExact(0L);
        long memory = (long) Native.Win.CREATE_COMPATIBLE_DC.invokeExact(screen);
        try {
            MemorySegment info = arena.allocate(44, 4);
            info.set(JAVA_INT, 0, 40);          // biSize
            info.set(JAVA_INT, 4, width);       // biWidth
            info.set(JAVA_INT, 8, -shown);      // biHeight negativo: de arriba abajo
            info.set(JAVA_SHORT, 12, (short) 1);  // biPlanes
            info.set(JAVA_SHORT, 14, (short) 32); // biBitCount
            MemorySegment bitsOut = arena.allocate(ADDRESS);
            long bitmap = (long) Native.Win.CREATE_DIB_SECTION.invokeExact(memory, info, 0, bitsOut, 0L, 0);
            if (bitmap == 0) return false;
            try {
                // Si la imagen es más alta que la barra se recorta por igual arriba y abajo
                int top = (height - shown) / 2;
                MemorySegment bits = bitsOut.get(ADDRESS, 0).reinterpret(4L * width * shown);
                MemorySegment.copy(pixels, top * width, bits, JAVA_INT, 0, width * shown);
                long old = (long) Native.Win.SELECT_OBJECT.invokeExact(memory, bitmap);

                MemorySegment size = arena.allocate(8, 4);
                size.set(JAVA_INT, 0, width);
                size.set(JAVA_INT, 4, shown);
                MemorySegment origin = arena.allocate(8, 4);
                MemorySegment blend = arena.allocate(4, 1);
                blend.set(JAVA_BYTE, 0, (byte) 0);           // AC_SRC_OVER
                blend.set(JAVA_BYTE, 1, (byte) 0);
                blend.set(JAVA_BYTE, 2, (byte) 255);         // opacidad global
                blend.set(JAVA_BYTE, 3, (byte) AC_SRC_ALPHA);
                int ok = (int) Native.Win.UPDATE_LAYERED_WINDOW.invokeExact(hwnd, screen, MemorySegment.NULL,
                        size, memory, origin, 0, blend, ULW_ALPHA);
                long restored = (long) Native.Win.SELECT_OBJECT.invokeExact(memory, old);
                return ok != 0;
            } finally {
                int ignored = (int) Native.Win.DELETE_OBJECT.invokeExact(bitmap);
            }
        } finally {
            int a = (int) Native.Win.DELETE_DC.invokeExact(memory);
            int b = (int) Native.Win.RELEASE_DC.invokeExact(0L, screen);
        }
    }

    /** Hueco de la barra en coordenadas relativas a ella: de {@code left} a {@code right}, alto {@code height}. */
    private record Layout(int left, int right, int height) {
    }

    private static Layout layout(Arena arena) throws Throwable {
        long tray = (long) Native.Win.FIND_WINDOW_W.invokeExact(Native.wide(arena, "Shell_TrayWnd"), MemorySegment.NULL);
        int[] trayRect = rectOf(arena, tray);
        if (trayRect == null) return null;
        int[] notify = rectOf(arena, child(arena, tray, "TrayNotifyWnd"));
        int[] tasks = rectOf(arena, child(arena, tray, "ReBarWindow32"));
        int left = (tasks != null ? tasks[2] : trayRect[0]) - trayRect[0];
        int right = (notify != null ? notify[0] : trayRect[2]) - trayRect[0];
        return new Layout(left, right, trayRect[3] - trayRect[1]);
    }

    private static long child(Arena arena, long parent, String className) throws Throwable {
        if (parent == 0) return 0;
        return (long) Native.Win.FIND_WINDOW_EX_W.invokeExact(parent, 0L, Native.wide(arena, className), MemorySegment.NULL);
    }

    private static int[] rectOf(Arena arena, long hwnd) throws Throwable {
        if (hwnd == 0) return null;
        MemorySegment rect = arena.allocate(16, 4);
        int ok = (int) Native.Win.GET_WINDOW_RECT.invokeExact(hwnd, rect);
        if (ok == 0) return null;
        return new int[]{rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12)};
    }

}
