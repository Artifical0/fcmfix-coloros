package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import com.kooritea.fcmfix.util.FcmSockets;
import com.kooritea.fcmfix.util.InetDiag;
import com.kooritea.fcmfix.util.LogRing;
import com.kooritea.fcmfix.util.SelfCheck;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches GMS's FCM socket (port 5228-5230, or 443 to mtalk) from system_server through sock_diag, and keeps a
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
    private static final String MTALK_HOST = "mtalk.google.com";
    private static final long MTALK_RESOLVE_INTERVAL_MS = 10 * 60_000;

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
    /** Addresses known to be mtalk: seen on 5228-5230, or resolved from MTALK_HOST. */
    private static final Set<String> mtalkAddresses = new HashSet<>();
    private static long mtalkResolvedAt;
    /** Why the current socket looks dead (FcmSockets.stall), and since when. */
    private static String stall;
    private static long stallSince;
    /** netId -> "Wi‑Fi" / "移动数据" ..., to say which network GMS's socket is on. */
    private static final Map<Integer, String> networkNames = new ConcurrentHashMap<>();
    private static volatile int defaultNetId = -1;

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
                    defaultNetId = netId(network);
                    if (!name.equals(last)) {
                        last = name;
                        event(SelfCheck.EVENT_NETWORK + "：" + name);
                        sampleSoon();
                    }
                }

                @Override
                public void onLost(Network network) {
                    last = null;
                    defaultNetId = -1;
                    event(SelfCheck.EVENT_NETWORK + "断开");
                }
            }, handler);
            // Every network, not only the default: GMS may keep its socket on another one (issue #6).
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                            networkNames.put(netId(network), networkName(capabilities));
                        }

                        @Override
                        public void onLost(Network network) {
                            networkNames.remove(netId(network));
                        }
                    }, handler);
        } catch (Throwable e) {
            XposedModule.printLog("FCM monitor: network callback unavailable: " + e);
        }
        handler.post(FcmConnectionMonitor::sampleAndReschedule);
        XposedModule.printLog("FCM connection monitor started");
    }

    /** Network.getNetworkHandle() is the netId shifted left by 32 bits. */
    private static int netId(Network network) {
        return (int) (network.getNetworkHandle() >>> 32);
    }

    /** "1.2.3.4:5228（Wi‑Fi）"; says so when the socket is not on the default network. */
    private static String describe(InetDiag.Socket socket) {
        String address = socket.remote.getHostAddress() + ":" + socket.remotePort;
        if (socket.mark < 0) return address;
        int netId = socket.mark & 0xffff;
        String name = networkNames.get(netId);
        if (name == null) name = "netId " + netId;
        int current = defaultNetId;
        return address + "（" + name + (current > 0 && current != netId ? "，不是当前默认网络" : "") + "）";
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
        FcmSockets.Pick pick = FcmSockets.pick(sockets, gmsUid, connectionKey, mtalkAddresses);
        InetDiag.Socket established = pick.established;
        InetDiag.Socket connecting = pick.connecting;
        long now = System.currentTimeMillis();
        if (pick.unknownFallback && now - mtalkResolvedAt > MTALK_RESOLVE_INTERVAL_MS) {
            // Never resolve on the caller's thread: the self-check may sample from a system_server looper.
            mtalkResolvedAt = now;
            handler.post(FcmConnectionMonitor::resolveMtalk);
        }
        String key = established == null ? null : established.key();
        String address = established == null ? null : describe(established);
        String newStall = established == null ? null : FcmSockets.stall(established);
        if (key != null && !key.equals(connectionKey)) {
            stall = null;
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
        } else if (key == null && connectionKey != null) {
            outages++;
            event(SelfCheck.EVENT_FCM_DOWN + "（" + format(lastSeen) + " 时还在线，此前已连接 "
                    + SelfCheck.duration(lastSeen - since) + "）");
        }
        if (key != null && newStall != null && stall == null) {
            stallSince = now;
            event(SelfCheck.EVENT_FCM_STALL + " " + address + "：" + newStall);
        } else if (key != null && newStall == null && stall != null) {
            event(SelfCheck.EVENT_FCM_RESTORED + " " + address + "（无响应约 " + SelfCheck.duration(now - stallSince) + "）");
        }
        stall = key == null ? null : newStall;
        connectionKey = key;
        if (established != null) {
            state = SelfCheck.FCM_CONNECTED;
            remote = address;
            lastSeen = now;
        } else {
            state = connecting != null ? SelfCheck.FCM_CONNECTING : SelfCheck.FCM_NONE;
            remote = connecting == null ? null
                    : connecting.remote.getHostAddress() + ":" + connecting.remotePort;
        }
    }

    /** Learns mtalk's addresses for GMS's 443 fallback, then samples again. */
    private static void resolveMtalk() {
        List<String> addresses = new ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(MTALK_HOST)) addresses.add(address.getHostAddress());
        } catch (Throwable e) {
            XposedModule.logOnce("FCM monitor: cannot resolve " + MTALK_HOST + ": " + e);
            return;
        }
        synchronized (FcmConnectionMonitor.class) {
            if (!mtalkAddresses.addAll(addresses)) return;
        }
        sample();
    }

    static synchronized int state() {
        return state;
    }

    static synchronized String remote() {
        return remote;
    }

    static synchronized String stall() {
        return state == SelfCheck.FCM_CONNECTED ? stall : null;
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
