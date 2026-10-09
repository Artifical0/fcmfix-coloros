package com.kooritea.fcmfix.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * NETLINK_SOCK_DIAG (inet_diag) request and reply layout, used by system_server to see GMS's
 * FCM socket. system_server may not read /proc/net/tcp on Android 10+, but may use sock_diag.
 * Netlink headers are host byte order; ports in inet_diag_sockid are network byte order.
 */
public final class InetDiag {
    public static final int NETLINK_INET_DIAG = 4;
    public static final int TCP_ESTABLISHED = 1;
    public static final int TCP_SYN_SENT = 2;

    private static final int AF_INET = 2;
    private static final int SOCK_DIAG_BY_FAMILY = 20;
    private static final int NLMSG_ERROR = 2;
    private static final int NLMSG_DONE = 3;
    private static final short NLM_F_REQUEST = 0x1;
    private static final short NLM_F_DUMP = 0x300;
    private static final int IPPROTO_TCP = 6;
    private static final int NLMSG_HEADER = 16;
    private static final int REQUEST_LENGTH = NLMSG_HEADER + 56;
    private static final int MESSAGE_LENGTH = 72;

    public static final class Socket {
        public final int state;
        public final int localPort;
        public final int remotePort;
        public final InetAddress remote;
        public final int uid;

        Socket(int state, int localPort, int remotePort, InetAddress remote, int uid) {
            this.state = state;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.remote = remote;
            this.uid = uid;
        }

        /** Stable identity of one connection: a reconnect gets a new local port. */
        public String key() {
            return remote.getHostAddress() + ":" + remotePort + "/" + localPort;
        }
    }

    private InetDiag() {
    }

    /** Dump request for every TCP socket of one family whose state is in the states bitmask. */
    public static byte[] request(int family, int states, int sequence, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(REQUEST_LENGTH).order(order);
        buffer.putInt(REQUEST_LENGTH);
        buffer.putShort((short) SOCK_DIAG_BY_FAMILY);
        buffer.putShort((short) (NLM_F_REQUEST | NLM_F_DUMP));
        buffer.putInt(sequence);
        buffer.putInt(0);
        buffer.put((byte) family);
        buffer.put((byte) IPPROTO_TCP);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.putInt(states);
        // inet_diag_sockid stays zero: no address or port filter, the caller filters replies.
        return buffer.array();
    }

    /**
     * Parses one datagram of replies into out. Returns true once the dump is finished
     * (NLMSG_DONE) and throws on NLMSG_ERROR.
     */
    public static boolean parse(ByteBuffer buffer, List<Socket> out) {
        while (buffer.remaining() >= NLMSG_HEADER) {
            int start = buffer.position();
            int length = buffer.getInt();
            int type = buffer.getShort() & 0xffff;
            buffer.getShort();
            buffer.getInt();
            buffer.getInt();
            if (length < NLMSG_HEADER || start + length > buffer.limit()) {
                throw new IllegalStateException("bad netlink length " + length);
            }
            if (type == NLMSG_DONE) return true;
            if (type == NLMSG_ERROR) {
                int error = buffer.getInt();
                throw new IllegalStateException("netlink error " + error);
            }
            if (type == SOCK_DIAG_BY_FAMILY && length >= NLMSG_HEADER + MESSAGE_LENGTH) {
                out.add(readMessage(buffer));
            }
            buffer.position(start + ((length + 3) & ~3));
        }
        return false;
    }

    private static Socket readMessage(ByteBuffer buffer) {
        int base = buffer.position();
        int family = buffer.get(base) & 0xff;
        int state = buffer.get(base + 1) & 0xff;
        int localPort = ((buffer.get(base + 4) & 0xff) << 8) | (buffer.get(base + 5) & 0xff);
        int remotePort = ((buffer.get(base + 6) & 0xff) << 8) | (buffer.get(base + 7) & 0xff);
        byte[] address = new byte[family == AF_INET ? 4 : 16];
        for (int i = 0; i < address.length; i++) address[i] = buffer.get(base + 24 + i);
        int uid = buffer.getInt(base + 64);
        InetAddress remote;
        try {
            remote = InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        return new Socket(state, localPort, remotePort, remote, uid);
    }
}
