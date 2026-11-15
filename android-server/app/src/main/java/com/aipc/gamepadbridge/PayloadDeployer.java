package com.aipc.gamepadbridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Deploys a bundled console payload to an ELF loader, exactly the way
 * {@code nc <host> <port> < payload.elf} does: open a TCP connection,
 * stream the raw ELF bytes, then close (EOF).
 *
 * The payload is chosen by console type, so a PS4 gets the PS4 build and a
 * PS5 gets the PS5 build. Both ELFs are bundled in the APK assets.
 */
public final class PayloadDeployer {

    public static final String PS5_ASSET = "ps5-client.elf";
    public static final String PS4_ASSET = "ps4-client.elf";
    private static final int CONNECT_TIMEOUT_MS = 8000; // nc -w 8

    private PayloadDeployer() {}

    /** Asset name for a console type. Unknown falls back to the PS5 build. */
    public static String assetFor(int type) {
        return type == Console.TYPE_PS4 ? PS4_ASSET : PS5_ASSET;
    }

    /** Read a bundled payload asset into memory. */
    public static byte[] loadPayload(Context ctx, String asset) throws Exception {
        InputStream in = ctx.getAssets().open(asset);
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
