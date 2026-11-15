package com.aipc.gamepadbridge;

import android.os.PowerManager;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
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

    /** Called on the reader thread for each RumbleFrame the PS5 sends. */
    public interface RumbleListener {
        void onRumble(int large, int small, int ttlMs);
    }

    /** Called on the reader thread when a console sends its HelloFrame. */
    public interface HelloListener {
        void onHello(HelloFrame hello, String fromIp);
    }

    /** How long a provisionally-accepted dialer (IP changed, MAC known) has
     *  to prove its identity with a Hello before it's dropped. */
    private static final int HELLO_VALIDATE_MS = 1500;

    private final PadMapper mapper;
    private final PowerManager.WakeLock wakeLock;
    private volatile StatusListener listener;
    private volatile RumbleListener rumbleListener;
    private volatile HelloListener helloListener;

    private volatile boolean running = false;
    private volatile boolean clientConnected = false;
    private volatile String clientIp = "";
    /** When set, this console's IP is the fast-path match; others are refused
     *  unless a MAC identifies them (see {@link #allowedClientMac}). */
    private volatile String allowedClientIp;
    /** The active console's durable MAC once learned. When set, a dialer from
     *  an unexpected IP is accepted provisionally and must prove this MAC via
     *  its Hello (DHCP-move tolerant); a different MAC is refused. */
    private volatile String allowedClientMac;
    /** Per-connection: true once the current client is confirmed to be the
     *  active console (IP matched, or Hello MAC matched / learned). */
    private volatile boolean clientValidated;
    /** Per-connection: accepted on IP mismatch pending Hello validation. */
    private volatile boolean clientProvisional;
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

    public void setRumbleListener(RumbleListener l) {
        this.rumbleListener = l;
    }

    public void setHelloListener(HelloListener l) {
        this.helloListener = l;
    }

    /**
     * Restrict the server to one console's IP. null accepts any dialer (the
     * behaviour when no console has been chosen yet). Setting this does not
     * drop a console already connected — call {@link #disconnectClient()}
     * for that when switching.
     */
    public void setAllowedClientIp(String ip) {
        this.allowedClientIp = ip;
    }

    public String getAllowedClientIp() {
        return allowedClientIp;
    }

    /**
     * Set the active console's durable MAC (lowercase "aa:bb:..."), or null
     * when none is known. With a MAC set, a dialer whose IP no longer matches
     * is accepted provisionally and must present this MAC in its Hello — so a
     * DHCP address change no longer loses the console — while a different MAC
     * is still refused. With no MAC, gating is IP-only (phase-1 behaviour).
     */
    public void setAllowedClientMac(String mac) {
        this.allowedClientMac = mac;
    }

    public String getAllowedClientMac() {
        return allowedClientMac;
    }

    /** Drop the current console so a switch to another takes effect now. */
    public void disconnectClient() {
        Socket c = clientSocket;
        if (c != null) closeQuietly(c);
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
                String ip = remoteIp(c);
                String allowed = allowedClientIp;
                String allowedMac = allowedClientMac;
                boolean provisional = false;
                if (allowed != null && !allowed.equals(ip)) {
                    if (allowedMac == null) {
                        // Phase-1 behaviour: no durable identity, so a dialer
                        // from the wrong IP is a different console. Refuse it.
                        Log.i(TAG, "refused " + ip + " (active console is " + allowed + ")");
                        closeQuietly(c);
                        continue;
                    }
                    // The active console has a MAC and may have changed IP.
                    // Accept provisionally; its Hello must prove the MAC or
                    // the reader drops it (serveClient holds frames until then).
                    provisional = true;
                    Log.i(TAG, "accepting " + ip + " provisionally (expect MAC " + allowedMac + ")");
                }
                clientValidated = !provisional;
                clientProvisional = provisional;
                clientSocket = c;
                clientConnected = true;
                clientIp = ip;
                Log.i(TAG, "client connected: " + clientIp);
                notifyStatus();
                Thread reader = new Thread(() -> readClient(c), "PadServer-rx");
                reader.start();
                try {
                    serveClient(c);
                } finally {
                    clientConnected = false;
                    clientIp = "";
                    closeQuietly(c);
                    clientSocket = null;
                    RumbleListener rl = rumbleListener;
                    if (rl != null) rl.onRumble(0, 0, 0); // never leave a motor running
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
            notifyStatus(); // report the final stopped state
        }
    }

    private void serveClient(Socket c) {
        try {
            // A provisionally-accepted dialer (its IP didn't match, but the
            // active console has a MAC) must prove its identity via its Hello
            // before we stream any input to it — otherwise a different console
            // could receive a few frames. The reader sets clientValidated on a
            // MAC match, or closes the socket on a mismatch.
            if (clientProvisional) {
                long deadline = System.currentTimeMillis() + HELLO_VALIDATE_MS;
                while (running && !clientValidated && !c.isClosed()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                if (!clientValidated) {
                    Log.i(TAG, "dropping " + clientIp + " (no matching Hello)");
                    return; // finally{} in run() closes the socket and cleans up
                }
            }
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

    /**
     * Read the console's inbound frames: a one-shot {@link HelloFrame} (60 B)
     * and a stream of {@link RumbleFrame}s (8 B), interleaved on one socket.
     * The frame type is chosen by magic; bytes that start neither frame are
     * skipped one at a time, so a stray byte can't desync the stream, and an
     * older payload that sends no Hello still works unchanged.
     *
     * On a Hello the reader also runs the MAC gate: if the active console has
     * a known MAC, a matching Hello validates this (possibly moved) console
     * and the Hello is surfaced; a different MAC closes the socket. With no
     * known MAC the Hello is surfaced so the app can learn it.
     * Closing the socket (disconnect or stop) ends the loop.
     */
    private void readClient(Socket c) {
        final int MAXF = Math.max(RumbleFrame.SIZE, HelloFrame.SIZE);
        final String ip = remoteIp(c);
        byte[] buf = new byte[MAXF];
        int have = 0;
        try {
            InputStream in = c.getInputStream();
            while (running) {
                if (have < 4) { // need a magic to know the frame length
                    int n = in.read(buf, have, MAXF - have);
                    if (n < 0) break;
                    have += n;
                    continue;
                }
                int magic = PadFrame.getU32(buf, 0);
                int need;
                if (magic == HelloFrame.MAGIC) need = HelloFrame.SIZE;
                else if (magic == RumbleFrame.MAGIC) need = RumbleFrame.SIZE;
                else {
                    // Resync: drop one byte and look for a magic again.
                    System.arraycopy(buf, 1, buf, 0, have - 1);
                    have--;
                    continue;
                }
                if (have < need) {
                    int n = in.read(buf, have, MAXF - have);
                    if (n < 0) break;
                    have += n;
                    continue;
                }
                if (need == HelloFrame.SIZE) {
                    HelloFrame hf = HelloFrame.decode(buf, 0);
                    if (hf != null && !onHello(hf, ip, c)) break; // MAC mismatch
                } else {
                    RumbleFrame rf = RumbleFrame.decode(buf, 0);
                    if (rf != null) {
                        RumbleListener rl = rumbleListener;
                        if (rl != null) rl.onRumble(rf.large, rf.small, rf.ttlMs);
                    }
                }
                System.arraycopy(buf, need, buf, 0, have - need);
                have -= need;
            }
        } catch (IOException e) {
            // socket closed
        }
        // The PS5 closed its side: drop the link so the writer notices too.
        closeQuietly(c);
    }

    /**
     * Handle a decoded Hello: run the MAC gate, mark the client validated, and
     * surface it. Returns false (and closes the socket) when the Hello's MAC
     * contradicts the known active MAC — a different console reusing the IP.
     */
    private boolean onHello(HelloFrame hf, String ip, Socket c) {
        String mac = hf.macString();
        String want = allowedClientMac;
        if (want != null && mac != null && !want.equalsIgnoreCase(mac)) {
            Log.i(TAG, "refused " + ip + " via Hello (MAC " + mac
                    + " != active " + want + ")");
            closeQuietly(c);
            return false;
        }
        if (want != null && mac == null && clientProvisional) {
            // Provisional dialer from a new IP that can't prove its MAC: don't
            // promote it. serveClient will time out and drop it.
            Log.i(TAG, "Hello from " + ip + " carries no MAC; staying unvalidated");
            return true;
        }
        clientValidated = true; // IP matched the active console, or MAC matched
        HelloListener hl = helloListener;
        if (hl != null) hl.onHello(hf, ip);
        return true;
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
