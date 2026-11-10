package com.aipc.gamepadbridge;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "GamepadBridge";
    private static final int MAX_LINES = 200;

    private TextView tvLog;
    private TextView tvDevice;
    private TextView tvLanIp;
    private TextView tvServer;
    private TextView tvClient;
    private TextView tvReadout;
    private ScrollView scrollView;
    private final StringBuilder buffer = new StringBuilder();
    private int lineCount;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PadMapper mapper;
    private PadServer server;
    private InputManager inputManager;
    private InputManager.InputDeviceListener deviceListener;
    private final PadState readoutState = new PadState();

    // Axes we feed to the mapper: sticks + Xbox analog triggers.
    private static final int[] MAP_AXES = {
            MotionEvent.AXIS_X,     // 0 left x
            MotionEvent.AXIS_Y,     // 1 left y
            MotionEvent.AXIS_Z,     // 2 right x
            MotionEvent.AXIS_RZ,   // 5 right y
            MotionEvent.AXIS_HAT_X,  // 3 D-pad x
            MotionEvent.AXIS_HAT_Y,  // 4 D-pad y
            MotionEvent.AXIS_GAS,   // 10 Xbox L2
            MotionEvent.AXIS_BRAKE // 11 Xbox R2
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvLog = findViewById(R.id.tv_log);
        tvDevice = findViewById(R.id.tv_device);
        tvLanIp = findViewById(R.id.tv_lan_ip);
        tvServer = findViewById(R.id.tv_server);
        tvClient = findViewById(R.id.tv_client);
        tvReadout = findViewById(R.id.tv_readout);
        scrollView = findViewById(R.id.scroll);

        String lanIp = findLanIp();
        tvLanIp.setText("LAN IP: " + lanIp);

        GamepadBridgeApp app = (GamepadBridgeApp) getApplication();
        mapper = app.getMapper();
        server = app.getServer();
        server.setListener(() -> mainHandler.post(this::refreshStatus));

        append("Gamepad Bridge started (Android " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT + ")");
        append("PS5 connect to: " + lanIp + ":" + PadServer.PORT);

        // Live connect / disconnect tracking.
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        deviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                onDeviceEvent(id, "CONNECTED");
            }

            @Override
            public void onInputDeviceChanged(int id) {
                onDeviceEvent(id, "CHANGED");
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                onDeviceEvent(id, "DISCONNECTED");
            }
        };
        inputManager.registerInputDeviceListener(deviceListener, mainHandler);
        refreshDeviceHeader();

        // Live readout @10Hz.
        mainHandler.postDelayed(readoutTick, 100);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(readoutTick);
        if (inputManager != null && deviceListener != null) {
            inputManager.unregisterInputDeviceListener(deviceListener);
        }
        super.onDestroy();
    }

    private final Runnable readoutTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            mapper.getState(readoutState);
            tvReadout.setText(formatReadout(readoutState));
            mainHandler.postDelayed(this, 100);
        }
    };

    private static String formatReadout(PadState s) {
        return String.format(Locale.US,
                "buttons=0x%05X  L(%03d,%03d)  R(%03d,%03d)  L2=%03d R2=%03d",
                s.buttons, s.lx, s.ly, s.rx, s.ry, s.l2, s.r2);
    }

    private void refreshStatus() {
        tvServer.setText(server.isRunning() ? "SERVER: UP" : "SERVER: down");
        tvClient.setText(server.isClientConnected()
                ? "CLIENT: " + server.getClientIp()
                : "CLIENT: waiting...");
    }

    private String findLanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
                for (InterfaceAddress ia : addrs) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "lan ip", e);
        }
        return "?";
    }

    private String deviceName(int deviceId) {
        try {
            InputDevice dev = InputDevice.getDevice(deviceId);
            return dev != null ? dev.getName() : "id=" + deviceId;
        } catch (Exception e) {
            return "id=" + deviceId;
        }
    }

    private void onDeviceEvent(int id, String what) {
        String name = deviceName(id);
        String msg = "DEVICE " + what + "  " + name + " (id " + id + ")";
        append(msg);
        Log.i(TAG, msg);
        refreshDeviceHeader();
    }

    private void refreshDeviceHeader() {
        int[] ids;
        try {
            ids = InputDevice.getDeviceIds();
        } catch (Exception e) {
            tvDevice.setText("No controllers yet");
            return;
        }
        if (ids == null || ids.length == 0) {
            tvDevice.setText("No controllers yet");
            return;
        }
        StringBuilder sb = new StringBuilder("Devices: ");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            InputDevice d = InputDevice.getDevice(ids[i]);
            String nm = (d != null) ? d.getName() : ("id=" + ids[i]);
            int src = (d != null) ? d.getSources() : 0;
            sb.append(nm).append(" [").append(sourceName(src)).append("]");
        }
        tvDevice.setText(sb.toString());
    }

    private String sourceName(int sources) {
        if ((sources & InputDevice.SOURCE_GAMEPAD) != 0) return "gamepad";
        if ((sources & InputDevice.SOURCE_JOYSTICK) != 0) return "joystick";
        if ((sources & InputDevice.SOURCE_DPAD) != 0) return "dpad";
        if ((sources & InputDevice.SOURCE_KEYBOARD) != 0) return "keyboard";
        return "sources=0x" + Integer.toHexString(sources);
    }

    private void append(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + line;
        buffer.append(stamped).append('\n');
        lineCount++;
        if (lineCount > MAX_LINES) {
            int drop = lineCount - MAX_LINES / 2;
            int idx = 0;
            for (int i = 0; i < drop; i++) {
                int nl = buffer.indexOf("\n", idx);
                if (nl < 0) break;
                idx = nl + 1;
            }
            buffer.delete(0, idx);
            lineCount -= drop;
        }
        tvLog.setText(buffer);
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.scrollTo(0, tvLog.getBottom());
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        String actionStr;
        if (action == KeyEvent.ACTION_DOWN) actionStr = "DOWN";
        else if (action == KeyEvent.ACTION_UP) actionStr = "UP";
        else if (action == KeyEvent.ACTION_MULTIPLE) actionStr = "MULTIPLE";
        else actionStr = "action=" + action;
        String name = KeyEvent.keyCodeToString(event.getKeyCode());
        String msg = String.format(Locale.US,
                "KEY %s  %s (code %d)  device=%s",
                actionStr, name, event.getKeyCode(), deviceName(event.getDeviceId()));
        append(msg);
        Log.i(TAG, msg);
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            mapper.onKey(event.getKeyCode(), action == KeyEvent.ACTION_DOWN);
        }
        return true; // consume everything (incl. back) so the app never exits mid-test
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            append(String.format(Locale.US,
                    "BTN %s  buttonState=0x%02X  device=%s",
                    MotionEvent.actionToString(action), event.getButtonState(),
                    deviceName(event.getDeviceId())));
        }
        for (int axis : MAP_AXES) {
            float value = event.getAxisValue(axis);
            mapper.onAxis(axis, value); // always apply: 0.0f is a valid release/recenter
            if (value != 0.0f) {
                append(String.format(Locale.US,
                        "AXIS %s = %.3f  device=%s",
                        MotionEvent.axisToString(axis), value,
                        deviceName(event.getDeviceId())));
            }
        }
        return true;
    }
}
