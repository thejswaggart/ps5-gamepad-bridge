package com.aipc.gamepadbridge;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** LAN helpers: the phone's IPv4 address and a /24 sweep for one open port. */
public final class NetUtil {
    private static final String TAG = "NetUtil";
    private static final int PROBE_TIMEOUT_MS = 400;

    private NetUtil() {}

    /** The phone's LAN IPv4 address, or null when it isn't on a network. */
    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "lanIp", e);
        }
        return null;
    }

    /** First three octets of an IPv4 address ("192.168.1"), or null. */
    public static String subnetBase(String ip) {
        if (ip == null) return null;
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) : null;
    }

    public static boolean sameSubnet(String a, String b) {
        String ba = subnetBase(a);
        return ba != null && ba.equals(subnetBase(b));
    }

    /** True if a TCP connection to ip:port succeeds within the timeout. */
    public static boolean isOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sweep base.1 - base.254 for an open port. Blocking (a few seconds);
     * call off the main thread. Returns the lowest matching address, or null.
     */
    public static String findFirstOpen(String base, int port) {
        ConcurrentSkipListSet<Integer> found = new ConcurrentSkipListSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i <= 254; i++) {
            final int host = i;
            pool.execute(() -> {
                if (isOpen(base + "." + host, port, PROBE_TIMEOUT_MS)) found.add(host);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return found.isEmpty() ? null : base + "." + found.first();
    }
}
