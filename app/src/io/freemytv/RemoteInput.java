package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone remote's keys and text, through the TV's own ADB (main process). A key goes straight to the input device
 * of the TV's own remote, with the code its key layout gives that key: the raw events in one echo (shell group `input`
 * may write there), else sendevent; a key that device lacks goes through `input keyevent` (1 to 2 s on a slow box).
 * One connection, closed after a few idle seconds:
 * some boxes' adbd serves one connection at a time, and the switches (HomeMode) need it too.
 */
final class RemoteInput {
    private RemoteInput() {}

    /** The keys the remote page sends, by their Android names, with their key codes (for `input keyevent`). */
    static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        String[] names = {"DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "BACK", "HOME", "MENU",
                "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "MEDIA_REWIND", "MEDIA_FAST_FORWARD",
                "DEL", "SLEEP", "WAKEUP"};
        int[] codes = {19, 20, 21, 22, 23, 4, 3, 82, 24, 25, 164, 85, 89, 90, 67, 223, 224};
        for (int i = 0; i < names.length; i++) KEYS.put(names[i], codes[i]);
    }

    private static final long IDLE_MS = 5_000;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Adb adb;
    private static String device;                       // the remote's input device ("/dev/input/event0"), or ""
    private static Map<String, Integer> linuxCodes;     // Android key name → that device's Linux key code
    private static boolean rawEcho;                     // the shell's echo writes octal escapes as bytes (checked)

    /** Key down, sync, key up, sync, as struct input_event (time left 0: the kernel stamps it), in echo's octal. */
    private static String events(int code) {
        boolean wide = android.os.Build.SUPPORTED_ABIS[0].contains("64");   // 64-bit timeval
        int[][] list = {{1, code, 1}, {0, 0, 0}, {1, code, 0}, {0, 0, 0}};
        StringBuilder s = new StringBuilder();
        for (int[] e : list) {
            for (int i = 0; i < (wide ? 16 : 8); i++) s.append("\\0000");
            int[] bytes = {e[0] & 0xFF, e[0] >> 8, e[1] & 0xFF, e[1] >> 8, e[2] & 0xFF, 0, 0, 0};
            for (int b : bytes) s.append(String.format(java.util.Locale.ROOT, "\\0%03o", b));
        }
        return s.toString();
    }

    private static final Runnable CLOSE = () -> {
        synchronized (RemoteInput.class) {
            if (adb != null) adb.close();
            adb = null;
        }
    };

    static void key(Context c, String name) throws IOException {
        Integer code = KEYS.get(name);
        if (code == null) throw new IOException("unknown key " + name);
        Integer linux = deviceCodes(c).get(name);
        if (linux != null && rawEcho) {
            run(c, "echo -ne '" + events(linux) + "' > " + device);   // one write, no process: ~5× faster
        } else if (linux != null) {
            run(c, "sendevent " + device + " 1 " + linux + " 1; sendevent " + device + " 0 0 0; "
                    + "sendevent " + device + " 1 " + linux + " 0; sendevent " + device + " 0 0 0");
        } else {
            run(c, "input keyevent " + code);
        }
    }

    /** Types the text in the focused field (accents dropped: `input text` only types ASCII on older Android). */
    static void text(Context c, String text) throws IOException {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "").trim();
        if (plain.isEmpty()) return;
        if (plain.length() > 200) plain = plain.substring(0, 200);
        run(c, "input text '" + plain.replace("%", "").replace(" ", "%s").replace("'", "'\\''") + "'");
    }

    /** Opens an app (the launcher activity, through the shell: background services can't on Android 10 and newer). */
    static void open(Context c, String component) throws IOException {
        if (!component.matches("[\\w.]+/[\\w.$]+")) throw new IOException("bad component");
        run(c, "am start -n " + component);
    }

    /**
     * Puts the TV to sleep. A box with HDMI-CEC also turns the TV off: the setting is switched on here (first use), so
     * the box's standby takes the TV with it.
     */
    static void sleep(Context c) throws IOException {
        run(c, "[ \"$(settings get global hdmi_control_auto_device_off_enabled)\" = 1 ] || "
                + "settings put global hdmi_control_auto_device_off_enabled 1; input keyevent 223");
    }

    private static synchronized String run(Context c, String command) throws IOException {
        HANDLER.removeCallbacks(CLOSE);
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    if (adb == null) adb = Adb.connect(c, 10_000, null);
                    return adb.shell(command);
                } catch (IOException e) {
                    if (adb != null) adb.close();
                    adb = null;
                    if (attempt > 0) throw e;   // a connection gone stale while idle: once more, fresh
                }
            }
        } finally {
            HANDLER.postDelayed(CLOSE, IDLE_MS);
        }
    }

    // MARK: - The TV remote's own input device

    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (\\S+)");
    private static final Pattern NAME = Pattern.compile("^\\s*name:\\s*\"(.*)\"");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*key\\s+(\\d+)\\s+(\\w+)");

    /** Finds the input device that sends DPAD_CENTER (the TV's own remote) and what each of its keys is, once. */
    private static synchronized Map<String, Integer> deviceCodes(Context c) throws IOException {
        if (linuxCodes != null) return linuxCodes;
        Map<String, Integer> best = new HashMap<>();
        String bestDevice = "";
        String path = null, name = null;
        StringBuilder keys = new StringBuilder();
        String listing = run(c, "getevent -p 2>/dev/null") + "\nadd device 0: end\n";
        for (String line : listing.split("\n")) {
            Matcher d = DEVICE.matcher(line);
            if (d.find()) {
                if (path != null && name != null) {
                    Map<String, Integer> found = layout(c, name, supported(keys.toString()));
                    if (found.containsKey("DPAD_CENTER") && found.size() > best.size()) {
                        best = found;
                        bestDevice = path;
                    }
                }
                path = d.group(1);
                name = null;
                keys.setLength(0);
                continue;
            }
            Matcher n = NAME.matcher(line);
            if (n.find()) name = n.group(1); else keys.append(line).append('\n');
        }
        device = bestDevice;
        linuxCodes = best;
        rawEcho = !device.isEmpty() && run(c, "echo -ne '\\0101\\0000\\0102' | od -An -tx1").trim().equals("41 00 42");
        AppLog.w("phone remote: " + (device.isEmpty() ? "no remote input device, using input keyevent"
                : device + " with " + best.size() + " keys"));
        return linuxCodes;
    }

    /** The key codes a device lists under KEY in `getevent -p` (hex). */
    private static Set<Integer> supported(String block) {
        Set<Integer> out = new HashSet<>();
        int at = block.indexOf("KEY (0001):");
        if (at < 0) return out;
        int end = block.indexOf(" (", at + 11);   // the next event type ("ABS (0003):")
        String codes = block.substring(at + 11, end < 0 ? block.length() : block.lastIndexOf('\n', end));
        for (String hex : codes.trim().split("\\s+")) {
            try {
                out.add(Integer.parseInt(hex, 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The device's key layout (/vendor or /system usr/keylayout/<name>.kl): Android key name → Linux code it has. */
    private static Map<String, Integer> layout(Context c, String name, Set<Integer> has) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        if (has.isEmpty()) return out;
        String file = name.replaceAll("[^A-Za-z0-9_-]", "_") + ".kl";
        String kl = run(c, "cat /vendor/usr/keylayout/" + file + " 2>/dev/null || cat /system/usr/keylayout/" + file + " 2>/dev/null");
        for (String line : kl.split("\n")) {
            Matcher m = KEY_LINE.matcher(line);
            if (!m.find()) continue;
            int code = Integer.parseInt(m.group(1));
            String key = m.group(2);
            if (KEYS.containsKey(key) && has.contains(code) && !out.containsKey(key)) out.put(key, code);
        }
        return out;
    }
}
