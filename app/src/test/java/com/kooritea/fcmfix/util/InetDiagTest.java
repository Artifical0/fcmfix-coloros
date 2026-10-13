package com.kooritea.fcmfix.util;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class InetDiagTest {
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    @Test public void requestIsTcpDumpOfOneFamily() {
        ByteBuffer request = ByteBuffer.wrap(InetDiag.request(10, 6, 7, LE)).order(LE);
        assertEquals(72, request.getInt(0));
        assertEquals(20, request.getShort(4));
        assertEquals(0x301, request.getShort(6));
        assertEquals(7, request.getInt(8));
        assertEquals(10, request.get(16));
        assertEquals(6, request.get(17));
        assertEquals(6, request.getInt(20));
        assertEquals(2, request.get(18));
    }

    @Test public void readsMarkAndTcpInfo() {
        ByteBuffer buffer = ByteBuffer.allocate(512).order(LE);
        int start = buffer.position();
        putMessage(buffer, 2, InetDiag.TCP_ESTABLISHED, 40000, 5228, new byte[]{(byte) 192, (byte) 178, (byte) 230, (byte) 188}, 10123);
        buffer.position(start + 16 + 72);
        buffer.putShort((short) 8).putShort((short) 15).putInt(0x30083);        // INET_DIAG_MARK, netId 131
        int info = buffer.position();
        buffer.putShort((short) (4 + 104)).putShort((short) 2);                   // INET_DIAG_INFO
        buffer.put(info + 4 + 2, (byte) 5);                                       // retransmits
        buffer.putInt(info + 4 + 24, 3);                                          // unacked
        buffer.putInt(info + 4 + 52, 45 * 60_000);                                // last_data_recv
        buffer.position(info + 4 + 104);
        buffer.putInt(start, buffer.position() - start);
        buffer.putInt(20).putShort((short) 3).putShort((short) 2).putInt(1).putInt(0).putInt(0);
        buffer.flip();
        List<InetDiag.Socket> sockets = new ArrayList<>();
        assertTrue(InetDiag.parse(buffer, sockets));
        InetDiag.Socket socket = sockets.get(0);
        assertEquals(131, socket.mark & 0xffff);
        assertEquals(5, socket.retransmits);
        assertEquals(3, socket.unacked);
        assertEquals(45 * 60_000L, socket.lastDataRecv);
    }

    @Test public void withoutAttributesInfoIsUnknown() {
        ByteBuffer buffer = ByteBuffer.allocate(256).order(LE);
        putMessage(buffer, 2, InetDiag.TCP_ESTABLISHED, 40000, 5228, new byte[]{1, 2, 3, 4}, 10123);
        buffer.flip();
        List<InetDiag.Socket> sockets = new ArrayList<>();
        InetDiag.parse(buffer, sockets);
        assertEquals(-1, sockets.get(0).mark);
        assertEquals(-1, sockets.get(0).lastDataRecv);
    }

    private static void putMessage(ByteBuffer buffer, int family, int state, int localPort,
                                   int remotePort, byte[] remote, int uid) {
        int start = buffer.position();
        buffer.putInt(16 + 72).putShort((short) 20).putShort((short) 2).putInt(1).putInt(0);
        buffer.put((byte) family).put((byte) state).put((byte) 0).put((byte) 0);
        buffer.put((byte) (localPort >> 8)).put((byte) localPort);
        buffer.put((byte) (remotePort >> 8)).put((byte) remotePort);
        buffer.position(start + 16 + 24);
        buffer.put(remote);
        buffer.position(start + 16 + 64);
        buffer.putInt(uid).putInt(0);
    }

    @Test public void parsesRepliesUntilDone() {
        ByteBuffer buffer = ByteBuffer.allocate(512).order(LE);
        putMessage(buffer, 2, InetDiag.TCP_ESTABLISHED, 40000, 5228, new byte[]{(byte) 142, (byte) 250, 1, 2}, 10123);
        putMessage(buffer, 2, InetDiag.TCP_SYN_SENT, 40001, 443, new byte[]{1, 1, 1, 1}, 10200);
        buffer.putInt(20).putShort((short) 3).putShort((short) 2).putInt(1).putInt(0).putInt(0);
        buffer.flip();
        List<InetDiag.Socket> sockets = new ArrayList<>();
        assertTrue(InetDiag.parse(buffer, sockets));
        assertEquals(2, sockets.size());
        InetDiag.Socket first = sockets.get(0);
        assertEquals(InetDiag.TCP_ESTABLISHED, first.state);
        assertEquals(40000, first.localPort);
        assertEquals(5228, first.remotePort);
        assertEquals(10123, first.uid);
        assertEquals("142.250.1.2:5228/40000", first.key());
        assertEquals(443, sockets.get(1).remotePort);
    }

    @Test public void partialDumpAsksForMore() {
        ByteBuffer buffer = ByteBuffer.allocate(256).order(LE);
        byte[] v6 = new byte[16];
        v6[0] = 0x24;
        v6[1] = 0x04;
        putMessage(buffer, 10, InetDiag.TCP_ESTABLISHED, 50000, 5228, v6, 10123);
        buffer.flip();
        List<InetDiag.Socket> sockets = new ArrayList<>();
        assertFalse(InetDiag.parse(buffer, sockets));
        assertEquals(1, sockets.size());
        assertTrue(sockets.get(0).remote.getHostAddress().startsWith("2404:"));
    }

    @Test(expected = IllegalStateException.class)
    public void netlinkErrorIsReported() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(LE);
        buffer.putInt(36).putShort((short) 2).putShort((short) 0).putInt(1).putInt(0).putInt(-1);
        buffer.position(36);
        buffer.flip();
        InetDiag.parse(buffer, new ArrayList<>());
    }
}
