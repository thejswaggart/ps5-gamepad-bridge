package com.aipc.gamepadbridge;

import android.os.Handler;
import android.os.Looper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sweeps the /24 segment containing the given base (e.g. "192.168.1")
 * for one open TCP port. Runs off the main thread; callbacks are posted
 * back on the main thread.
 */
public class NetworkScanner {

    public interface Listener {
        void onOpen(String ip, int port);
        void onDone(int openCount);
    }

    private final String base;      // e.g. "192.168.1"
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger openCount = new AtomicInteger();

    public NetworkScanner(String base, int port, Listener listener) {
        this.base = base;
        this.port = port;
        this.listener = listener;
    }

    /** Probe .1-.254 of the segment (skipping .0 and .255). */
    public void start() {
        final int n = 254;
        final ExecutorService pool = Executors.newFixedThreadPool(32);
        final AtomicInteger done = new AtomicInteger();
        for (int i = 1; i < n; i++) {
            final String ip = base + "." + i;
            pool.execute(() -> {
                if (probe(ip)) {
                    openCount.incrementAndGet();
                    main.post(() -> listener.onOpen(ip, port));
                }
                if (done.incrementAndGet() == n - 1) {
                    pool.shutdown();
                    main.post(() -> listener.onDone(openCount.get()));
                }
            });
        }
    }

    private boolean probe(String ip) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 400);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
