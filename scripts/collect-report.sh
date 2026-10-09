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

report() {
  echo "FCMFix report $(date '+%Y-%m-%d %H:%M:%S %z')"

  section "系统版本"
  echo "display=$(getprop ro.build.display.id)"
  echo "oplusrom=$(getprop ro.build.version.oplusrom.display)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "model=$(getprop ro.product.model)"

  section "模块版本"
  dumpsys package "$MODULE" | grep -m1 versionName || echo "未安装 $MODULE"

  # ColorOS keeps these in its own networking_control service (/data/oplus/common/networkingcontrolpolicy.xml),
  # not in AOSP netpolicy. Transaction 2 is getUidPolicy(uid).
  section "Google 核心包联网策略（0 不限制，1 禁移动数据，2 禁 Wi-Fi，4 全部禁止）"
  for p in com.google.android.gms com.google.android.gsf com.android.vending com.google.android.configupdater; do
    uid=$(pm list packages -U "$p" | grep "^package:$p " | grep -o 'uid:[0-9]*' | cut -d: -f2)
    if [ -z "$uid" ]; then echo "$p 未安装"; continue; fi
    raw=$(service call networking_control 2 i32 "$uid" 2>&1)
    hex=$(echo "$raw" | sed -n 's/.*Parcel(\([0-9a-f]*\) \([0-9a-f]*\).*/\1 \2/p')
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
  for cmd in iptables ip6tables; do
    for chain in fw_INPUT fw_OUTPUT; do
      echo "-- $cmd $chain"
      $cmd -w -t filter -nvxL "$chain" 2>&1
    done
  done

  section "FCMFix / ColorOS Google 限制日志"
  logcat -d | grep -iE "fcmfix|GoogleController|OplusGoogle"

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

if [ "$OUT" = "-" ]; then
  report "$@" 2>&1
else
  report "$@" > "$OUT" 2>&1
  echo "已保存到 $OUT"
fi
