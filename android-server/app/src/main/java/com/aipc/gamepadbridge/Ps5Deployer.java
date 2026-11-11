package com.aipc.gamepadbridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Deploys the bundled PS5 payload to an ELF loader, exactly the way
 * `nc <host> <port> < ghostpad-net.elf` does: open a TCP connection,
 * stream the raw ELF bytes, then close (EOF).
 */
public class Ps5Deployer {

    public static final String ASSET = "ghostpad-net.elf";
    private static final int CONNECT_TIMEOUT_MS = 8000; // nc -w 8

    /** Read the bundled payload from assets into memory. */
    public static byte[] loadPayload(Context ctx) throws Exception {
        InputStream in = ctx.getAssets().open(ASSET);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Stream the payload bytes to host:port, then close (EOF). */
    public static void deploy(String host, int port, byte[] payload) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            java.io.OutputStream out = s.getOutputStream();
            out.write(payload);
            out.flush();
        } finally {
            s.close();
        }
    }
}
