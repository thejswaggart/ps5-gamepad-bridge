package com.aipc.gamepadbridge;

import android.os.PowerManager;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * TCP server on 0.0.0.0:8471. Accepts ONE client (the PS5) and streams
 * PadFrame @~60Hz (16ms). Holds a PARTIAL_WAKE_LOCK while running so the
 * CPU (and the socket) stay alive with the screen off.
 */
public final class PadServer {
    private static final String TAG = "PadServer";
    public static final int PORT = 8471;
    private static final int FRAME_INTERVAL_MS = 16; // ~60 Hz

    /** Called on the server thread whenever status changes; query the getters. */
    public interface StatusListener {
        void onStatus();
    }

    private final PadMapper mapper;
    private final PowerManager.WakeLock wakeLock;
    private volatile StatusListener listener;

    private volatile boolean running = false;
    private volatile boolean clientConnected = false;
    private volatile String clientIp = "";
    private Thread thread;
    private Socket clientSocket;

    public PadServer(PadMapper mapper, PowerManager.WakeLock wakeLock, StatusListener listener) {
        this.mapper = mapper;
        this.wakeLock = wakeLock;
        this.listener = listener;
    }

    public void setListener(StatusListener l) {
        this.listener = l;
    }

    private void notifyStatus() {
        StatusListener l = listener;
        if (l != null) l.onStatus();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            wakeLock.acquire();
        } catch (Exception e) {
            Log.e(TAG, "wakelock", e);
        }
        thread = new Thread(this::run, "PadServer");
        thread.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        Socket c = clientSocket;
        if (c != null) closeQuietly(c);
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        Log.i(TAG, "stopped");
        notifyStatus();
    }

    private void run() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.bind(new InetSocketAddress("0.0.0.0", PORT));
            ss.setSoTimeout(500);
            Log.i(TAG, "listening on 0.0.0.0:" + PORT);
            notifyStatus();
            while (running) {
                Socket c;
                try {
                    c = ss.accept();
                } catch (SocketTimeoutException e) {
                    continue; // poll the running flag
                }
                if (!running) {
                    closeQuietly(c);
                    break;
                }
                clientSocket = c;
                clientConnected = true;
                clientIp = remoteIp(c);
                Log.i(TAG, "client connected: " + clientIp);
                notifyStatus();
                try {
                    serveClient(c);
                } finally {
                    clientConnected = false;
                    clientIp = "";
                    closeQuietly(c);
                    clientSocket = null;
                    Log.i(TAG, "client disconnected");
                    notifyStatus();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "server error: " + e);
            notifyStatus();
        } finally {
            closeQuietly(ss);
            running = false;
        }
    }

    private void serveClient(Socket c) {
        try {
            OutputStream out = c.getOutputStream();
            byte[] buf = new byte[PadFrame.SIZE];
            PadState snap = new PadState();
            int seq = 0;
            long next = System.currentTimeMillis() + FRAME_INTERVAL_MS;
            while (running && !c.isClosed() && c.isConnected()) {
                long now = System.currentTimeMillis();
                long sleep = next - now;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                } else if (sleep < -1000) {
                    next = now + FRAME_INTERVAL_MS; // too far behind; resync
                }
                next += FRAME_INTERVAL_MS;
                mapper.getState(snap);
                encodeFrame(buf, snap, ++seq);
                synchronized (out) {
                    out.write(buf, 0, PadFrame.SIZE);
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "client socket closed: " + e);
        } catch (InterruptedException e) {
            // stop()
        }
    }

    private static void encodeFrame(byte[] buf, PadState s, int seq) {
        PadFrame f = new PadFrame();
        f.magic = PadFrame.MAGIC;
        f.seq = seq;
        f.buttons = s.buttons;
        f.lx = s.lx;
        f.ly = s.ly;
        f.rx = s.rx;
        f.ry = s.ry;
        f.l2 = s.l2;
        f.r2 = s.r2;
        f.rsvd = s.connected; // byte 0 of rsvd = connected 0/1
        f.ts = System.currentTimeMillis();
        f.pad = 0;
        f.encode(buf, 0);
    }

    private static String remoteIp(Socket c) {
        try {
            java.net.InetAddress a = c.getInetAddress();
            if (a != null) return a.getHostAddress();
        } catch (Exception ignored) {
        }
        return "?";
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
