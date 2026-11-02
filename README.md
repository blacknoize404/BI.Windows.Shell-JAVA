# BI.Windows.Shell

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

Windows shell integration from Java, with no native code of its own (it uses the Foreign Function & Memory API of Java 27):

- **Dark mode** for the title bar and for the classic menus the shell draws.
- **Click-through windows**, for widgets and overlays.
- **The native context menu of Explorer** for any file, with every system verb and every third-party extension (7-Zip and
  friends), plus your own items at the end.

The .NET edition lives at [BI.Windows.Shell-NET](https://github.com/blacknoize404/BI.Windows.Shell-NET) —
same feature set, same name; only the repository suffix differs.

> **Documentación en español**: [README.es.md](README.es.md)

## Install

Published through [JitPack](https://jitpack.io/#blacknoize404/BI.Windows.Shell-JAVA) from each release tag:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.blacknoize404</groupId>
  <artifactId>BI.Windows.Shell-JAVA</artifactId>
  <version>v0.4.0</version>
</dependency>
```

It is the Java module `bi.windows.shell` and needs Java 27. Run with `--enable-native-access=bi.windows.shell` (or
`ALL-UNNAMED` on the class path) to avoid the restricted-method warning.

## Dark mode

```java
DarkModeResult result = WindowsDarkMode.apply(hwnd);      // title bar and menus
WindowsDarkMode.set(hwnd, false, true);                   // back to light
```

The result says what happened (`applied`, the previous mode, the ordinal used, the error) instead of promising. It is best
effort: on a Windows that does not obey, everything stays as it was.

## Explorer context menu

```java
ShellMenuResult result = ShellContextMenu.showAtCursor("C:\\Users\\me\\picture.png", hwnd, List.of(
        new ShellMenuExtra("Copy path", () -> copy(path))));
```

Call it from a single-threaded-apartment thread (the JavaFX thread and the AWT event thread are). No failure of the
interop reaches the host as an exception: `ShellMenuResult` is `SHOWN`, `NO_FILE`, `NOT_STA`, `INTEROP_FAILED` or
`UNSUPPORTED` (not Windows). While the menu is open the window is subclassed so the extensions can paint their items;
pass `subclass = false` and forward messages with `ShellContextMenu.tryHandleMenuMessage` if the host already hooks its
window.

Java windows do not expose their handle: `WindowHandles.findOwnWindow(title)` finds the top-level window of the current
process by its title.

`setTelemetry(Consumer<String>)` on both classes receives one JSON line per call, if you want evidence of what ran.

## Click-through windows

Neither JavaFX nor Swing lets a window ignore the mouse and let it through to whatever is underneath. `WindowStyles`
asks Windows for it (`WS_EX_TRANSPARENT`, plus `WS_EX_LAYERED` and `WS_EX_NOACTIVATE` so it does not steal focus):

```java
long hwnd = WindowHandles.findOwnWindow("My widget");        // the HWND of a window of this process
ClickThroughResult result = WindowStyles.setClickThrough(hwnd, true);
// result.applied(): the state read back is the one requested; result.error(): why not, or null

WindowStyles.setClickThrough(hwnd, false);                   // receives the mouse again
boolean passing = WindowStyles.isClickThrough(hwnd);
WindowStyles.setHiddenFromTaskbar(hwnd, true);               // no taskbar button, no Alt+Tab entry
boolean hidden = WindowStyles.isHiddenFromTaskbar(hwnd);
WindowStyles.setHiddenFromTaskbar(hwnd, false);              // back to being listed
long foreground = WindowHandles.foreground();                // the system's foreground window
```

A translucent window only receives the mouse where its pixels are not fully transparent (alpha 0), so a window with
no background or shadow in some area will not get clicks there even when it is not in this mode. Windows only reads
the taskbar style when the window is shown, so a visible window blinks once while `setHiddenFromTaskbar` flips it.
If the window is closed and shown again it is a different native window: call it again.

## License

This project is distributed under the [Apache License 2.0](LICENSE).
