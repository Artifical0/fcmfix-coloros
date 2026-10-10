package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import com.kooritea.fcmfix.util.InetDiag;
import com.kooritea.fcmfix.util.LogRing;
import com.kooritea.fcmfix.util.SelfCheck;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches GMS's FCM socket (port 5228-5230) from system_server through sock_diag, and keeps a
 * short timeline of what usually explains a drop: screen, deep Doze, network changes, pushes.
 * A socket replaced by a new one between two samples is a reconnect; a sample that finds none
 * after one was up starts an outage, which ends at the first sample that finds one again.
 * Sampling uses an uptime Handler, so it never wakes the device; a reconnect made while the CPU
 * slept still shows up as a changed connection at the next sample.
 */
final class FcmConnectionMonitor {
    private static final long SAMPLE_INTERVAL_MS = 60_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    /** A reconnect after a longer unsampled gap (CPU asleep) also says when the old socket was last seen. */
    private static final long QUICK_RECONNECT_MS = 150_000;
    private static final LogRing EVENTS = new LogRing(300);
    private static final LogRing PUSHES = new LogRing(150);

    private static Handler handler;
    private static int gmsUid = -1;
    private static int sequence;

    private static int state = SelfCheck.UNKNOWN;
    private static String connectionKey;
    private static String remote;
    private static long since;
    private static long lastSeen;
    private static int reconnects;
    private static int outages;
    private static long longestOutage;
    private static long startedAt;

    private FcmConnectionMonitor() {
    }

    static synchronized void start(Context context) {
        if (handler != null || context == null) return;
        try {
            gmsUid = context.getPackageManager().getPackageUid("com.google.android.gms", 0);
        } catch (Throwable e) {
            XposedModule.printLog("FCM monitor: GMS not installed");
            return;
        }
        HandlerThread thread = new HandlerThread("FCMFix-monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        startedAt = System.currentTimeMillis();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) event(SelfCheck.EVENT_SCREEN_ON);
                else if (Intent.ACTION_SCREEN_OFF.equals(action)) event(SelfCheck.EVENT_SCREEN_OFF);
                else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                    PowerManager power = receiverContext.getSystemService(PowerManager.class);
                    event(power != null && power.isDeviceIdleMode()
                            ? SelfCheck.EVENT_DOZE_ENTER : SelfCheck.EVENT_DOZE_EXIT);
                }
                sampleSoon();
            }
        }, filter, null, handler);

        try {
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                private String last;

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    String name = networkName(capabilities);
                    if (!name.equals(last)) {
                        last = name;
                        event(SelfCheck.EVENT_NETWORK + "：" + name);
                        sampleSoon();
                    }
                }

                @Override
                public void onLost(Network network) {
                    last = null;
                    event(SelfCheck.EVENT_NETWORK + "断开");
                }
            }, handler);
        } catch (Throwable e) {
            XposedModule.printLog("FCM monitor: network callback unavailable: " + e);
        }
        handler.post(FcmConnectionMonitor::sampleAndReschedule);
        XposedModule.printLog("FCM connection monitor started");
    }

    private static String networkName(NetworkCapabilities capabilities) {
        String transport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi‑Fi"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动数据"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "以太网" : "其他";
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : transport;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    private static String format(long wallTime) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(wallTime), ZoneId.systemDefault()).format(TIME);
    }

    static void event(String text) {
        EVENTS.add(now() + " " + text);
        XposedModule.printLog("event: " + text);
    }

    /** Not logged: BroadcastFix already logs each delivery. */
    static void push(String packageName) {
        PUSHES.add(now() + " " + SelfCheck.EVENT_PUSH + packageName);
    }

    /** Newest last. */
    static ArrayList<String> events() {
        return EVENTS.snapshot();
    }

    static ArrayList<String> pushes() {
        return PUSHES.snapshot();
    }

    private static void sampleSoon() {
        Handler current = handler;
        if (current != null) current.postDelayed(FcmConnectionMonitor::sample, 3000);
    }

    private static void sampleAndReschedule() {
        sample();
        handler.postDelayed(FcmConnectionMonitor::sampleAndReschedule, SAMPLE_INTERVAL_MS);
    }

    /** Takes a fresh sample; also called from the self-check thread. */
    static synchronized void sample() {
        if (gmsUid < 0) return;
        List<InetDiag.Socket> sockets;
        try {
            sockets = dumpTcp();
        } catch (Throwable e) {
            XposedModule.logOnce("FCM monitor: sock_diag failed: " + e);
            state = SelfCheck.UNKNOWN;
            return;
        }
        InetDiag.Socket established = null;
        InetDiag.Socket connecting = null;
        for (InetDiag.Socket socket : sockets) {
            if (socket.uid != gmsUid || socket.remotePort < 5228 || socket.remotePort > 5230) continue;
            // While GMS reconnects two sockets may coexist; keep following the current one.
            if (socket.state == InetDiag.TCP_ESTABLISHED
                    && (established == null || socket.key().equals(connectionKey))) established = socket;
            else if (socket.state == InetDiag.TCP_SYN_SENT && connecting == null) connecting = socket;
        }
        long now = System.currentTimeMillis();
        String key = established == null ? null : established.key();
        if (key != null && !key.equals(connectionKey)) {
            String address = established.remote.getHostAddress() + ":" + established.remotePort;
            if (connectionKey != null) {
                reconnects++;
                String unseen = now - lastSeen > QUICK_RECONNECT_MS
                        ? "，旧连接最后一次见到于 " + format(lastSeen) : "";
                event(SelfCheck.EVENT_FCM_RECONNECT + " " + address + "（旧连接持续 "
                        + SelfCheck.duration(lastSeen - since) + unseen + "）");
            } else if (lastSeen > 0) {
                // The outage ended somewhere between the previous sample and now.
                long outage = now - lastSeen;
                longestOutage = Math.max(longestOutage, outage);
                event(SelfCheck.EVENT_FCM_RESTORED + " " + address + "（断线约 " + SelfCheck.duration(outage) + "）");
            } else {
                event(SelfCheck.EVENT_FCM_UP + " " + address);
            }
            since = now;
            remote = address;
        } else if (key == null && connectionKey != null) {
            outages++;
            event(SelfCheck.EVENT_FCM_DOWN + "（" + format(lastSeen) + " 时还在线，此前已连接 "
                    + SelfCheck.duration(lastSeen - since) + "）");
        }
        connectionKey = key;
        if (established != null) {
            state = SelfCheck.FCM_CONNECTED;
            lastSeen = now;
        } else {
            state = connecting != null ? SelfCheck.FCM_CONNECTING : SelfCheck.FCM_NONE;
            remote = connecting == null ? null
                    : connecting.remote.getHostAddress() + ":" + connecting.remotePort;
        }
    }

    static synchronized int state() {
        return state;
    }

    static synchronized String remote() {
        return remote;
    }

    /** Wall time the current connection was first seen; 0 when not connected. */
    static synchronized long since() {
        return state == SelfCheck.FCM_CONNECTED ? since : 0;
    }

    static synchronized int reconnects() {
        return reconnects;
    }

    static synchronized int outages() {
        return outages;
    }

    /** Includes the outage still going on, if any. */
    static synchronized long longestOutage() {
        return state != SelfCheck.FCM_CONNECTED && lastSeen > 0
                ? Math.max(longestOutage, System.currentTimeMillis() - lastSeen) : longestOutage;
    }

    static synchronized long lastSeen() {
        return lastSeen;
    }

    static synchronized long startedAt() {
        return startedAt;
    }

    private static List<InetDiag.Socket> dumpTcp() throws Exception {
        List<InetDiag.Socket> sockets = new ArrayList<>();
        int states = (1 << InetDiag.TCP_ESTABLISHED) | (1 << InetDiag.TCP_SYN_SENT);
        for (int family : new int[]{OsConstants.AF_INET, OsConstants.AF_INET6}) {
            FileDescriptor fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM | OsConstants.SOCK_CLOEXEC,
                    InetDiag.NETLINK_INET_DIAG);
            try {
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(2000));
                byte[] request = InetDiag.request(family, states, ++sequence, ByteOrder.nativeOrder());
                // An unconnected netlink socket sends to the kernel (port id 0).
                Os.write(fd, request, 0, request.length);
                byte[] buffer = new byte[32 * 1024];
                boolean done = false;
                while (!done) {
                    int read = Os.read(fd, buffer, 0, buffer.length);
                    if (read <= 0) break;
                    done = InetDiag.parse(ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.nativeOrder()), sockets);
                }
            } finally {
                Os.close(fd);
            }
        }
        return sockets;
    }
}
