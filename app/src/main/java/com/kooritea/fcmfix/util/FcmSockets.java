package com.kooritea.fcmfix.util;

import java.util.List;
import java.util.Set;

/**
 * Picks GMS's FCM socket out of a sock_diag dump. FCM normally uses 5228-5230; where those are
 * blocked GMS falls back to 443 on the same mtalk.google.com host. GMS keeps many other 443
 * connections, so a 443 socket only counts when its remote is a known mtalk address: one seen
 * on 5228-5230 since boot, or one mtalk.google.com resolves to.
 */
public final class FcmSockets {
    public static final int FALLBACK_PORT = 443;

    private FcmSockets() {
    }

    public static boolean isFcmPort(int port) {
        return port >= 5228 && port <= 5230;
    }

    /** The established FCM socket and, failing that, one still connecting on an FCM port. */
    public static final class Pick {
        public InetDiag.Socket established;
        public InetDiag.Socket connecting;
        /** GMS has established 443 sockets, none to a known mtalk address. */
        public boolean unknownFallback;
    }

    /** Also records every remote seen on an FCM port into mtalk. */
    public static Pick pick(List<InetDiag.Socket> sockets, int uid, String currentKey, Set<String> mtalk) {
        Pick pick = new Pick();
        for (InetDiag.Socket socket : sockets) {
            if (socket.uid != uid || !isFcmPort(socket.remotePort)) continue;
            mtalk.add(socket.remote.getHostAddress());
            // While GMS reconnects two sockets may coexist; keep following the current one.
            if (socket.state == InetDiag.TCP_ESTABLISHED
                    && (pick.established == null || socket.key().equals(currentKey))) pick.established = socket;
            else if (socket.state == InetDiag.TCP_SYN_SENT && pick.connecting == null) pick.connecting = socket;
        }
        if (pick.established != null) return pick;
        for (InetDiag.Socket socket : sockets) {
            if (socket.uid != uid || socket.remotePort != FALLBACK_PORT || socket.state != InetDiag.TCP_ESTABLISHED) {
                continue;
            }
            if (!mtalk.contains(socket.remote.getHostAddress())) {
                pick.unknownFallback = true;
            } else if (pick.established == null || socket.key().equals(currentKey)) {
                pick.established = socket;
            }
        }
        if (pick.established != null) pick.unknownFallback = false;
        return pick;
    }
}
