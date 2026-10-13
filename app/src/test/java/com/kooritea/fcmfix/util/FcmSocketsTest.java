package com.kooritea.fcmfix.util;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class FcmSocketsTest {
    private static final int GMS = 10123;

    private static InetDiag.Socket socket(int state, int localPort, String remote, int remotePort, int uid)
            throws Exception {
        return new InetDiag.Socket(state, localPort, remotePort, InetAddress.getByName(remote), uid);
    }

    @Test public void fcmPortWinsAndRecordsMtalk() throws Exception {
        Set<String> mtalk = new HashSet<>();
        FcmSockets.Pick pick = FcmSockets.pick(Arrays.asList(
                socket(InetDiag.TCP_ESTABLISHED, 40000, "142.250.1.2", 443, GMS),
                socket(InetDiag.TCP_ESTABLISHED, 40001, "108.177.97.188", 5228, GMS)), GMS, null, mtalk);
        assertEquals(5228, pick.established.remotePort);
        assertFalse(pick.unknownFallback);
        assertTrue(mtalk.contains("108.177.97.188"));
    }

    @Test public void fallback443OnlyToKnownMtalk() throws Exception {
        Set<String> mtalk = new HashSet<>(Arrays.asList("108.177.97.188"));
        FcmSockets.Pick pick = FcmSockets.pick(Arrays.asList(
                socket(InetDiag.TCP_ESTABLISHED, 40000, "142.250.1.2", 443, GMS),
                socket(InetDiag.TCP_ESTABLISHED, 40002, "108.177.97.188", 443, GMS)), GMS, null, mtalk);
        assertEquals("108.177.97.188:443/40002", pick.established.key());
        assertFalse(pick.unknownFallback);
    }

    @Test public void unknown443AsksForResolution() throws Exception {
        FcmSockets.Pick pick = FcmSockets.pick(Arrays.asList(
                socket(InetDiag.TCP_ESTABLISHED, 40000, "142.250.1.2", 443, GMS),
                socket(InetDiag.TCP_ESTABLISHED, 40003, "108.177.97.188", 443, 10200)), GMS, null, new HashSet<>());
        assertNull(pick.established);
        assertTrue(pick.unknownFallback);
    }

    @Test public void keepsFollowingCurrentSocketAndReportsConnecting() throws Exception {
        Set<String> mtalk = new HashSet<>();
        FcmSockets.Pick pick = FcmSockets.pick(Arrays.asList(
                socket(InetDiag.TCP_ESTABLISHED, 40010, "108.177.97.188", 5228, GMS),
                socket(InetDiag.TCP_ESTABLISHED, 40011, "108.177.97.189", 5228, GMS)),
                GMS, "108.177.97.189:5228/40011", mtalk);
        assertEquals(40011, pick.established.localPort);

        pick = FcmSockets.pick(Arrays.asList(socket(InetDiag.TCP_SYN_SENT, 40012, "108.177.97.188", 5229, GMS)),
                GMS, null, mtalk);
        assertNull(pick.established);
        assertEquals(5229, pick.connecting.remotePort);
    }

    @Test public void stallNeedsRetransmitsOrLongSilence() throws Exception {
        InetDiag.Socket socket = socket(InetDiag.TCP_ESTABLISHED, 40000, "108.177.97.188", 5228, GMS);
        assertNull(FcmSockets.stall(socket));
        socket.unacked = 1;
        socket.retransmits = 1;
        socket.lastDataRecv = 5 * 60_000;
        assertNull(FcmSockets.stall(socket));
        socket.retransmits = 3;
        assertTrue(FcmSockets.stall(socket).contains("重传 3 次"));
        socket.unacked = 0;
        socket.lastDataRecv = 45 * 60_000;
        assertTrue(FcmSockets.stall(socket).contains("45 分钟"));
    }
}
