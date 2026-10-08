package com.kooritea.fcmfix.util;

import java.util.ArrayList;
import java.util.List;

/**
 * OAppNetControlService.networkDisableWhiteList entries are "uid" or "uid:..." strings.
 */
public final class NightNetworkWhitelist {
    private NightNetworkWhitelist() {
    }

    /** A copy of the list with the UID appended, or null when the UID is already listed. */
    public static List<Object> withUid(List<?> original, int uid) {
        String entry = String.valueOf(uid);
        for (Object item : original) {
            if (item != null && (entry.equals(item) || item.toString().startsWith(entry + ":"))) return null;
        }
        List<Object> whitelist = new ArrayList<>(original);
        whitelist.add(entry);
        return whitelist;
    }
}
