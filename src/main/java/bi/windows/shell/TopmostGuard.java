package bi.windows.shell;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Mantiene ventanas «siempre encima» por encima de la barra de tareas y del menú Inicio en el mismo instante
 * en que cambia el foco, en vez de esperar al siguiente sondeo.
 * <p>Al activarse la barra de tareas, el menú Inicio o cualquier otra ventana de la misma clase, Windows las
 * coloca por encima de las «siempre encima» ya existentes. Un gancho {@code EVENT_SYSTEM_FOREGROUND} avisa de
 * ese cambio y entonces la ventana vuelve a su sitio sin activarse ni moverse.
 * <p>El gancho se instala la primera vez que se observa una ventana y se entrega en el hilo que lo instaló,
 * así que hay que llamar a {@link #watch(long)} desde el hilo de la interfaz (en JavaFX, el de aplicación),
 * que ya bombea los mensajes de Windows.
 */
public final class TopmostGuard {

    private static final int EVENT_SYSTEM_FOREGROUND = 0x0003;
    private static final int WINEVENT_OUTOFCONTEXT = 0x0000;
    private static final long HWND_TOPMOST = -1L;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;

    private static final Set<Long> WATCHED = new CopyOnWriteArraySet<>();
    private static volatile long hook;

    private TopmostGuard() {
    }

    /**
     * Empieza a reafirmar la ventana en lo alto de las «siempre encima» cada vez que cambie el foco.
     *
     * @param hwnd la ventana; 0 si todavía no existe
     * @return true si la ventana queda vigilada; false si no es Windows, no existe o el gancho no se pudo instalar
     */
    public static boolean watch(long hwnd) {
        if (!Native.WINDOWS || hwnd == 0) return false;
        if (!ensureHook()) return false;
        WATCHED.add(hwnd);
        return true;
    }

    /**
     * Deja de reafirmar la ventana. No hace nada si no estaba vigilada.
     *
     * @param hwnd la ventana
     */
    public static void unwatch(long hwnd) {
        WATCHED.remove(hwnd);
    }

    private static synchronized boolean ensureHook() {
        if (hook != 0) return true;
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(TopmostGuard.class, "onWinEvent",
                    MethodType.methodType(void.class, long.class, int.class, long.class, int.class, int.class, int.class, int.class));
            FunctionDescriptor callback = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            MemorySegment stub = Native.Win.LINKER.upcallStub(target, callback, Arena.global());
            long installed = (long) Native.Win.SET_WIN_EVENT_HOOK.invokeExact(
                    EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, 0L, stub, 0, 0, WINEVENT_OUTOFCONTEXT);
            hook = installed;
            Native.emit("TopmostGuard.Install", "installed", installed != 0);
            return installed != 0;
        } catch (Throwable t) {
            Native.emit("TopmostGuard.Install", "installed", false, "error", t.getClass().getSimpleName());
            return false;
        }
    }

    /** Llamado por Windows en el hilo de la interfaz cada vez que cambia la ventana en primer plano. */
    @SuppressWarnings("unused")
    private static void onWinEvent(long eventHook, int event, long hwnd, int idObject, int idChild, int thread, int time) {
        if (event != EVENT_SYSTEM_FOREGROUND) return;
        for (long window : WATCHED) {
            raise(window);
        }
    }

    private static void raise(long window) {
        try {
            int ok = (int) Native.Win.SET_WINDOW_POS.invokeExact(window, HWND_TOPMOST, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            // si Windows no la acepta, la ventana ya no existe: se deja de vigilar para no acumular handles muertos
            if (ok == 0) WATCHED.remove(window);
        } catch (Throwable t) {
            // una ventana que ya no existe no debe tumbar la aplicación anfitriona
        }
    }

}
