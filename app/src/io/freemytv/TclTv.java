package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A TCL TV on the network (the ones that aren't Android, with TCL's nScreen remote), for a box plugged into it whose
 * HDMI-CEC can't reach the TV: the phone remote sends it keys over TCP 4123, the protocol of TCL's own phone app
 * (as in github.com/popeen/TCL-TV-Remote, MIT). The TV is found with SSDP (a media renderer with 4123 open) and
 * remembered. Turned off, the TV leaves the network: it turns back on with its own button or remote.
 */
final class TclTv {
    private TclTv() {}

    static final int PORT = 4123;

    /** The phone remote's key names → the TV's. */
    static final Map<String, String> KEYS = new HashMap<>();
    static {
        String[][] pairs = {{"DPAD_UP", "TR_KEY_UP"}, {"DPAD_DOWN", "TR_KEY_DOWN"}, {"DPAD_LEFT", "TR_KEY_LEFT"},
                {"DPAD_RIGHT", "TR_KEY_RIGHT"}, {"DPAD_CENTER", "TR_KEY_OK"}, {"BACK", "TR_KEY_BACK"},
                {"HOME", "TR_KEY_HOME"}, {"MENU", "TR_KEY_MAINMENU"}, {"VOLUME_UP", "TR_KEY_VOL_UP"},
                {"VOLUME_DOWN", "TR_KEY_VOL_DOWN"}, {"VOLUME_MUTE", "TR_KEY_MUTE"}, {"MEDIA_PLAY_PAUSE", "TR_KEY_PLAYPAUSE"},
                {"MEDIA_REWIND", "TR_KEY_REW"}, {"MEDIA_FAST_FORWARD", "TR_KEY_FF"}, {"POWER", "TR_KEY_POWER"},
                {"SOURCE", "TR_KEY_SOURCE"}};
        for (String[] p : pairs) KEYS.put(p[0], p[1]);
    }

    /** The TV's address, once found (kept across restarts: the TV is off, and so unseen, much of the time). */
    static String address(Context c) {
        try (FileInputStream in = new FileInputStream(file(c))) {
            byte[] b = new byte[64];
            int n = in.read(b);
            String ip = n > 0 ? new String(b, 0, n, StandardCharsets.US_ASCII).trim() : "";
            return ip.matches("[0-9.]+") ? ip : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Sends a key (a phone remote name); true if the TV took it. Finds the TV again when it moved. */
    static boolean key(Context c, String name) throws IOException {
        String key = KEYS.get(name);
        if (key == null) throw new IOException("unknown TV key " + name);
        String ip = address(c);
        if (ip != null) {
            try {
                return send(ip, key);
            } catch (IOException e) {
                String found = find(c);
                if (found == null || found.equals(ip)) throw e;   // off (it leaves the network), or nowhere else
                return send(found, key);
            }
        }
        String found = find(c);
        return found != null && send(found, key);
    }

    /** Turns the TV off if it's on (on = it answers: off, it's gone from the network). Quick when it's off. */
    static void off(Context c) {
        String ip = address(c);
        if (ip == null) return;
        try {
            send(ip, KEYS.get("POWER"));
        } catch (IOException e) {
            // already off
        }
    }

    private static boolean send(String ip, String key) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PORT), 1500);
            s.setSoTimeout(2000);
            s.getOutputStream().write(("<?xml version=\"1.0\" encoding=\"utf-8\"?><root><action name=\"setKey\" "
                    + "eventAction=\"TR_DOWN\" keyCode=\"" + key + "\" /></root>").getBytes(StandardCharsets.UTF_8));
            byte[] reply = new byte[256];
            try {
                int n = s.getInputStream().read(reply);
                return n > 0 && new String(reply, 0, n, StandardCharsets.UTF_8).contains("true");
            } catch (SocketTimeoutException e) {
                return true;   // some sets don't answer
            }
        }
    }

    private static long lastSearch;

    /**
     * Looks for the TV (SSDP: media renderers that answer, then the one with the remote port open) and remembers it.
     * At most once a minute.
     */
    static synchronized String find(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastSearch < 60_000) return address(c);
        lastSearch = now;
        Set<String> hosts = new LinkedHashSet<>();
        byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            s.send(new DatagramPacket(search, search.length, InetAddress.getByName("239.255.255.250"), 1900));
            byte[] buffer = new byte[2048];
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                hosts.add(p.getAddress().getHostAddress());
            }
        } catch (IOException e) {
            AppLog.w("TV search: " + e.getMessage());
        }
        for (String ip : hosts) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, PORT), 800);
                save(c, ip);
                AppLog.w("TCL TV found at " + ip);
                return ip;
            } catch (IOException ignored) {
            }
        }
        return address(c);
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "tcl-tv");
    }

    private static void save(Context c, String ip) {
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(ip.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("TV address: " + e.getMessage());
        }
    }
}
