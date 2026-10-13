#!/system/bin/sh
# FCMFix 问题报告收集脚本。需要 Root：su -c sh /sdcard/Download/collect-report.sh [输出文件名] [目标应用包名…]
# 输出文件名为 - 时写到标准输出（FCMFix 应用内导出报告用）。只读取系统状态和日志，不修改任何设置。

if [ "$1" = "-" ]; then
  OUT=-
else
  OUT="/sdcard/Download/${1:-fcmfix-report.txt}"
fi
[ $# -gt 0 ] && shift
GMS=com.google.android.gms
MODULE=io.github.artifical0.fcmfix.coloros

section() { echo; echo "== $1"; }

# pm first; the data directory's owner still works when cmd cannot be reached.
uid_of() {
  uid=$(pm list packages -U "$1" 2>/dev/null | grep "^package:$1 " | grep -o 'uid:[0-9]*' | cut -d: -f2)
  [ -n "$uid" ] || uid=$(stat -c %u "/data/data/$1" 2>/dev/null)
  echo "$uid"
}

report() {
  echo "FCMFix report $(date '+%Y-%m-%d %H:%M:%S %z')"

  section "系统版本"
  echo "display=$(getprop ro.build.display.id)"
  echo "oplusrom=$(getprop ro.build.version.oplusrom.display)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "model=$(getprop ro.product.model)"

  section "模块版本"
  # grep -m1 would close the pipe early and make dumpsys print "Broken pipe".
  version=$(dumpsys package "$MODULE" | grep versionName)
  if [ -n "$version" ]; then echo "$version" | sed -n 1p; else echo "未安装 $MODULE"; fi

  # ColorOS keeps these in its own networking_control service (/data/oplus/common/networkingcontrolpolicy.xml),
  # not in AOSP netpolicy. Transaction 2 is getUidPolicy(uid).
  section "Google 核心包联网策略（0 不限制，1 禁移动数据，2 禁 Wi-Fi，4 全部禁止）"
  for p in com.google.android.gms com.google.android.gsf com.android.vending com.google.android.configupdater; do
    uid=$(uid_of "$p")
    if [ -z "$uid" ]; then echo "$p 未安装"; continue; fi
    raw=$(service call networking_control 2 i32 "$uid" 2>&1)
    # Some builds print a tab after "Parcel(": Result: Parcel(<tab>00000000 00000004   '........')
    hex=$(echo "$raw" | sed -n 's/.*Parcel([[:space:]]*\([0-9a-f]\{8\}\)[[:space:]]\{1,\}\([0-9a-f]\{8\}\).*/\1 \2/p')
    if [ "${hex%% *}" = "00000000" ]; then
      echo "$p uid=$uid policy=$(printf '%d' "0x${hex##* }")"
    else
      echo "$p uid=$uid policy=查询失败: $raw"
    fi
  done
  echo "oplus_user_change_gms_network_control=$(settings get global oplus_user_change_gms_network_control)"

  section "GMS 待机分组（10/20/30 正常，40 为 RARE 受限）"
  am get-standby-bucket "$GMS"

  section "google_restric_info（1 表示系统判定 Google 受限）"
  settings get secure google_restric_info

  # Lines are "<list>,<package>,<uid>"; FCMFix keeps GMS/GSF on the Doze whitelist.
  section "Google 核心包 Doze 白名单"
  dumpsys deviceidle whitelist | grep -E ",(com\.google\.android\.gms|com\.google\.android\.gsf)," || echo "GMS/GSF 不在 Doze 白名单"

  # Battery reads Settings.Secure deepsleep_switch_state (1 = deep sleep may cut the network) and
  # logs every cut/restore to deepsleepRcd.txt, alternating with deepsleepRcdAnother.txt.
  section "睡眠待机优化（deepsleep_switch_state：1 允许深度睡眠断网，null 为系统默认）"
  echo "deepsleep_switch_state=$(settings get secure deepsleep_switch_state)"
  rcd=$(ls -t /data/oplus/os/battery/deepsleepRcd*.txt 2>/dev/null | head -n 1)
  if [ -n "$rcd" ]; then
    echo "-- $rcd（最近 40 行）"
    tail -n 40 "$rcd"
  else
    echo "没有深度睡眠记录"
  fi

  # ColorOS 17 liboplusNetd hangs its per-UID blocks on fw_INPUT/fw_OUTPUT as REJECT/DROP rules that
  # match pinned BPF programs; the UIDs live in BPF maps. The pkts column shows which block is firing.
  section "防火墙链（reject_wlan_uid / drop_cell_uid / reject_qcom_uid 为流量管理联网策略，netdisable 为深度睡眠断网，hans 为冻结断网）"
  cleaner=/data/adb/modules/google-services-firewall-cleaner
  if [ -d "$cleaner" ]; then
    if [ -f "$cleaner/disable" ]; then echo "拦截规则清理模块：已安装，已停用"; else echo "拦截规则清理模块：已安装，已启用（下列规则可能已被删除）"; fi
  fi
  # Newer builds (17.0.0.102) move the rules into oplus_fw_INPUT / oplus_fw_OUTPUT / fw_OUTPUT_oplus_dns,
  # so also list every oplus chain that fw_INPUT / fw_OUTPUT jump to.
  for cmd in iptables ip6tables; do
    sub=$({ $cmd -w -t filter -nL fw_INPUT; $cmd -w -t filter -nL fw_OUTPUT; } 2>/dev/null \
      | awk '$1 != "Chain" && $1 != "target" {print $1}' | grep -i oplus | sort -u)
    for chain in fw_INPUT fw_OUTPUT $sub; do
      echo "-- $cmd $chain"
      $cmd -w -t filter -nvxL "$chain" 2>&1
    done
  done

  # The kernel can keep GMS's socket ESTABLISHED while GMS itself sits in "Connecting" (issue #6):
  # unacked + retrans or a large lastrcv show a dead socket, and the default network vs the one
  # GMS follows (FCM Diagnostics "GcmNetwork{netId ...}") shows whether it uses the wrong network.
  section "GMS 的 FCM 连接（unacked/retrans 不为 0、lastrcv 很大说明连接无响应）"
  gms_uid=$(uid_of "$GMS")
  echo "GMS uid=$gms_uid"
  if command -v ss >/dev/null 2>&1; then
    sockets=$(ss -tnie state established state syn-sent \
      '( dport = :5228 or dport = :5229 or dport = :5230 or dport = :443 )' | grep -A1 "uid:$gms_uid ")
    if [ -n "$sockets" ]; then echo "$sockets" | head -n 40; else echo "GMS 没有到 5228-5230 / 443 的连接"; fi
  else
    echo "-- 没有 ss，/proc/net/tcp*（端口 146C-146E 即 5228-5230）"
    cat /proc/net/tcp /proc/net/tcp6 | awk -v u="$gms_uid" '$8 == u && $3 ~ /:146[CDE]$/'
  fi
  echo "-- 网络（GMS 使用的 netId 见 FCM Diagnostics 的 GcmNetwork{…}）"
  dumpsys connectivity | grep -E "Active default network|^ *NetworkAgentInfo\{" | cut -c1-240 | head -n 12
  dumpsys netpolicy | grep -E "UID=$gms_uid " | head -n 5
  # A local proxy's TUN (Clash/Surfing use 198.18.0.0/15) can hold GMS's socket open after the
  # proxy stops or its upstream dies; the socket's local address above then shows 198.18.x.
  echo "-- TUN 接口（本机代理）"
  ip -o addr show 2>/dev/null | grep -iE " tun|198\.18\.|198\.19\." | awk '{print $2, $3, $4}' || true
  echo "-- GMS 连接日志（按 GMS uid，FCM 所在进程名因版本而异）"
  gms_log=$(logcat -d -b main,system --uid="$gms_uid" 2>/dev/null)
  if [ -z "$gms_log" ]; then
    # Older logcat without --uid: every process of the GMS uid.
    for pid in $(pgrep -u "$gms_uid" 2>/dev/null); do
      logcat -d -b main,system --pid="$pid" 2>/dev/null
    done | sort
  else
    echo "$gms_log"
  fi | grep -iE "gcm|mcs|heartbeat|connect" | tail -n 150

  section "FCMFix / ColorOS Google 限制日志"
  # Match the module's "[fcmfix]" prefix, not the package name io.github.artifical0.fcmfix.coloros,
  # which window-manager logs mention all the time.
  logcat -d | grep -E "\[fcmfix\]|GoogleController|OplusGoogle"

  # For "GMS sent the broadcast but no notification": whether the target was started, frozen or
  # killed, and whether its notifications or channels are turned off.
  for PKG in "$@"; do
    section "目标应用 $PKG"
    dumpsys package "$PKG" | grep -E "versionName|User 0:|POST_NOTIFICATIONS" || echo "未安装 $PKG"
    echo "standby-bucket=$(am get-standby-bucket "$PKG")"
    echo "pid=$(pidof "$PKG")"
    echo "-- 通知设置（importance=0 或 NONE 表示已关闭）"
    dumpsys notification | awk -v p="AppSettings: $PKG (" '
      index($0, p) { on = 1; print; next }
      on && /AppSettings: / { exit }
      on { print }' | head -n 80

    section "目标应用相关系统日志"
    logcat -d -b main,system,events,crash | grep -F "$PKG" | tail -n 300
  done
}

# pm / settings / am go through cmd, which hands its stdin/stdout/stderr to system_server. A
# terminal app's pty or a file on /sdcard cannot be passed there ("Failed transaction"), so run
# with stdin from /dev/null and stdout/stderr on a pipe, as the in-app export does.
if [ "$OUT" = "-" ]; then
  report "$@" < /dev/null 2>&1 | cat
else
  report "$@" < /dev/null 2>&1 | cat > "$OUT"
  echo "已保存到 $OUT"
fi
