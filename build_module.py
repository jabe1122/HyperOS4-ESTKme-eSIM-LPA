from pathlib import Path
import argparse
import hashlib
import shutil
import zipfile

BASE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description='Build Xiaomi HyperOS 4 removable eSIM module (v0.6.0)')
parser.add_argument('--output-dir', type=Path, default=BASE.parents[1] / 'outputs')
OUT = parser.parse_args().output_dir.resolve()
MOD = BASE / 'module'
PREBUILT_LPA = BASE / 'prebuilt/MIUIEsimLPA.apk'
COMPAT_APK = BASE / 'probe/app/build/outputs/apk/debug/app-debug.apk'
TESTER_APK = BASE / 'probe/tester/build/outputs/apk/debug/tester-debug.apk'

OUT.mkdir(parents=True, exist_ok=True)
if MOD.exists():
    shutil.rmtree(MOD)
MOD.mkdir(parents=True, exist_ok=True)


def put(rel_path: str, content: str) -> None:
    target = MOD / rel_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.replace('\r\n', '\n'), encoding='utf-8', newline='\n')


put('META-INF/com/google/android/updater-script', '#MAGISK\n')

put('META-INF/com/google/android/update-binary', r'''#!/sbin/sh
umask 022
ui_print() { echo "$1"; }
OUTFD=$2
ZIPFILE=$3
if [ ! -f /data/adb/magisk/util_functions.sh ]; then
  ui_print "! Require KernelSU, SukiSU, APatch, or Magisk v20.4+"
  exit 1
fi
. /data/adb/magisk/util_functions.sh
install_module
exit 0
''')

put('system/product/etc/permissions/android.hardware.telephony.euicc.xml', '''<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <feature name="android.hardware.telephony.euicc" />
</permissions>
''')

put('system/product/etc/permissions/privapp-permissions-euicc.xml', '''<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.miui.euicc">
        <permission name="android.permission.MODIFY_PHONE_STATE" />
        <permission name="android.permission.READ_PHONE_STATE" />
        <permission name="android.permission.WRITE_EMBEDDED_SUBSCRIPTIONS" />
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE" />
        <permission name="android.permission.WRITE_SECURE_SETTINGS" />
        <permission name="android.permission.BIND_EUICC_SERVICE" />
        <permission name="android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION" />
        <permission name="android.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER" />
        <permission name="android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND" />
        <permission name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
    </privapp-permissions>
</permissions>
''')

put('module.prop', '''id=XiaomiEsimLPAHyperOS4
name=Xiaomi eSIM LPA - HyperOS 4
version=v0.6.0
versionCode=600
author=Local HyperOS 4 eSIM Adaptation
description=Removable eUICC (ESTKme / Xiaobai / 9eSIM / Xesim) in SIM2 on HyperOS 4. Native eSIM feature, Xiaomi LPA v4.1.0.1, Material 3 control switches, and SukiSU/KernelSU umount immunity.
''')

put('config', 'MODE=off\n')

put('common.sh', r'''#!/system/bin/sh
MODDIR=${MODDIR:-${0%/*}}
STATE=/data/adb/xiaomi-esim-hyperos4
LPA_OVERLAY_APK=/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk
LPA_PRIV_XML=/product/etc/permissions/privapp-permissions-euicc.xml

logmsg() {
  echo "$1"
  log -t XiaomiEsimH4 "$1"
}

read_mode() {
  MODE=$(cat "$MODDIR/config" 2>/dev/null)
  MODE=${MODE#MODE=}
  [ "$MODE" = on ] || MODE=off
}

read_umount_guard() {
  UG=$(cat "$STATE/umount_guard" 2>/dev/null)
  UG=${UG#UMOUNT_GUARD=}
  [ "$UG" = off ] && UMOUNT_GUARD=off || UMOUNT_GUARD=on
}

read_tester_enabled() {
  TE=$(cat "$STATE/tester_enabled" 2>/dev/null)
  TE=${TE#TESTER=}
  [ "$TE" = off ] && TESTER_ENABLED=off || TESTER_ENABLED=on
}

valid_value() {
  [ "$1" = null ] || echo "$1" | grep -Eq '^-?[0-9]+$'
}

is_ksu_env() {
  if [ "$KSU" = true ] || [ "$KSU" = 1 ] || [ "$SUKISU" = true ] || [ "$SUKISU" = 1 ] \
     || [ -n "$KSU_VER_CODE" ] || [ -n "$KSU_KERNEL_VER_CODE" ] || [ -n "$SUKISU_VER_CODE" ]; then
    return 0
  fi
  if [ -x /data/adb/ksud ] && [ -z "$MAGISK_VER_CODE" ] && [ ! -d /data/adb/magisk ] \
     && [ "$APATCH" != true ] && [ "$APATCH" != 1 ] && [ ! -d /data/adb/ap ]; then
    return 0
  fi
  return 1
}

is_ksu_missing_metamodule() {
  is_ksu_env || return 1
  ksu_code=${KSU_VER_CODE:-${SUKISU_VER_CODE:-$KSU_KERNEL_VER_CODE}}
  if [ -n "$ksu_code" ] && [ "$ksu_code" -lt 20000 ] 2>/dev/null; then
    return 1
  fi
  if [ -e /data/adb/metamodule ] \
     && [ ! -f /data/adb/metamodule/disable ] \
     && [ ! -f /data/adb/metamodule/remove ]; then
    return 1
  fi
  if [ -f "$LPA_OVERLAY_APK" ] && [ -f "$LPA_PRIV_XML" ]; then
    return 1
  fi
  return 0
}

is_lpa_system_pkg() {
  pm path com.miui.euicc >/dev/null 2>&1 || return 1
  if pm list packages -s com.miui.euicc 2>/dev/null | grep -qx 'package:com.miui.euicc'; then
    return 0
  fi
  dumpsys package com.miui.euicc 2>/dev/null | grep -Eq '(pkgFlags|flags)=\[[^]]*(^| )SYSTEM( |$|\])'
}

is_lpa_updated_system_pkg() {
  dumpsys package com.miui.euicc 2>/dev/null | grep -Eq '(pkgFlags|flags)=\[[^]]*(^| )UPDATED_SYSTEM_APP( |$|\])'
}

is_lpa_unprivileged_user_pkg() {
  pm path com.miui.euicc >/dev/null 2>&1 || return 1
  if pm list packages -3 com.miui.euicc 2>/dev/null | grep -qx 'package:com.miui.euicc'; then
    return 0
  fi
  pkg_dump=$(dumpsys package com.miui.euicc 2>/dev/null)
  [ -n "$pkg_dump" ] || return 1
  echo "$pkg_dump" | grep -Eq '(pkgFlags|flags)=\[' || return 1
  echo "$pkg_dump" | grep -Eq '(pkgFlags|flags)=\[[^]]*(^| )SYSTEM( |$|\])' && return 1
  return 0
}

cleanup_unprivileged_lpa() {
  if is_lpa_unprivileged_user_pkg; then
    logmsg "Removing unprivileged /data/app com.miui.euicc (missing SYSTEM flag)."
    pm uninstall com.miui.euicc >/dev/null 2>&1
    rm -f "$STATE/lpa.sha256"
    return 0
  fi
  return 1
}

strip_lpa_updates() {
  if is_lpa_updated_system_pkg; then
    pm uninstall-system-updates com.miui.euicc >/dev/null 2>&1 || true
  fi
  rm -f "$STATE/lpa.sha256"
}

sync_lpa_umount_guard() {
  [ -f "$LPA_OVERLAY_APK" ] && [ -f "$LPA_PRIV_XML" ] && is_lpa_system_pkg || return 0
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  read_umount_guard
  if [ "$UMOUNT_GUARD" = off ]; then
    if is_lpa_updated_system_pkg; then
      logmsg "Stripping UPDATED_SYSTEM_APP layer for com.miui.euicc (umount_guard=off)."
      pm uninstall-system-updates com.miui.euicc >/dev/null 2>&1 || true
      rm -f "$STATE/lpa.sha256"
    fi
    return 0
  fi
  src_apk="$MODDIR/system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk"
  [ -f "$src_apk" ] || src_apk="$LPA_OVERLAY_APK"
  [ -f "$src_apk" ] || return 0
  apk_sha=$(sha256sum "$src_apk" 2>/dev/null | cut -d ' ' -f 1)
  if is_lpa_updated_system_pkg && [ -n "$apk_sha" ] && [ "$(cat "$STATE/lpa.sha256" 2>/dev/null)" = "$apk_sha" ]; then
    return 0
  fi
  staged="/data/local/tmp/.esim_h4_com.miui.euicc.apk"
  rm -f "$staged"
  if cp "$src_apk" "$staged" 2>/dev/null; then
    chmod 0644 "$staged"
    restorecon "$staged" >/dev/null 2>&1 || true
    if pm install -r "$staged" >/dev/null 2>&1; then
      [ -n "$apk_sha" ] && echo "$apk_sha" > "$STATE/lpa.sha256"
      logmsg "Synced com.miui.euicc UPDATED_SYSTEM_APP layer (SukiSU/KernelSU umount immunity active)."
    fi
    rm -f "$staged"
  fi
}

check_lpa_mount_ok() {
  if [ -f "$LPA_OVERLAY_APK" ] && [ -f "$LPA_PRIV_XML" ]; then
    try=0
    while [ "$try" -lt 5 ]; do
      if is_lpa_system_pkg; then
        setprop sys.xiaomi_esim_h4.mount_ok 1
        return 0
      fi
      try=$((try + 1))
      sleep 1
    done
  fi
  setprop sys.xiaomi_esim_h4.mount_ok 0
  return 1
}

sim2_get() {
  CLASSPATH="$MODDIR/compat.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control get 2>/dev/null | sed -n 's/^SIM2=//p' | tail -n 1
}

sim2_set() {
  card_key=$(cat "$STATE/sim2.card.original" 2>/dev/null)
  [ -n "$card_key" ] || return 1
  CLASSPATH="$MODDIR/compat.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control set "$1" "$card_key" >/dev/null 2>&1
}

save_sim2_original() {
  card_key=$(CLASSPATH="$MODDIR/compat.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control probe 2>/dev/null | sed -n 's/^CARD_KEY=//p' | tail -n 1)
  echo "$card_key" | grep -Eq '^[0-9a-f]{64}$' || return 1
  saved_card=$(cat "$STATE/sim2.card.original" 2>/dev/null)
  saved_val=$(cat "$STATE/sim2.original" 2>/dev/null)
  if [ "$saved_card" = "$card_key" ] && { [ "$saved_val" = true ] || [ "$saved_val" = false ]; }; then
    return 0
  fi
  cur_val=${1:-$(sim2_get)}
  case "$cur_val" in
    true|false) value=$cur_val ;;
    empty) value=true ;;
    *) return 1 ;;
  esac
  echo "$card_key" > "$STATE/sim2.card.original"
  echo "$value" > "$STATE/sim2.original"
  return 0
}

restore_sim2() {
  [ -f "$STATE/sim2.original" ] || return 0
  value=$(cat "$STATE/sim2.original")
  case "$value" in
    true|false)
      cur=$(sim2_get)
      case "$cur" in
        "$value"|empty|none|null) return 0 ;;
        *) sim2_set "$value" ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

save_originals() {
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  if [ -f "$STATE/originals.saved" ]; then
    valid_value "$(cat "$STATE/secure.original")" && valid_value "$(cat "$STATE/provisioned.original")"
    return $?
  fi
  secure=$(settings --user 0 get secure is_enable_esim_for_user 2>/dev/null) || return 1
  provisioned=$(settings get global euicc_provisioned 2>/dev/null) || return 1
  valid_value "$secure" && valid_value "$provisioned" || return 1
  echo "$secure" > "$STATE/secure.original"
  echo "$provisioned" > "$STATE/provisioned.original"
  getprop ro.vendor.miui.support_esim > "$STATE/vendor-prop.original"
  touch "$STATE/originals.saved"
}

sync_apk_if_needed() {
  pkg=$1
  apk_path=$2
  sha_file=$3
  [ "$pkg" = com.miui.euicc ] && return 0
  [ -f "$apk_path" ] || return 0
  apk_sha=$(sha256sum "$apk_path" | cut -d ' ' -f 1)
  installed_path=$(pm path "$pkg" 2>/dev/null | head -n 1 | cut -d : -f 2)
  if [ -n "$installed_path" ] && [ "$(sha256sum "$installed_path" 2>/dev/null | cut -d ' ' -f 1)" = "$apk_sha" ]; then
    echo "$apk_sha" > "$sha_file"
    return 0
  fi
  if [ "$(cat "$sha_file" 2>/dev/null)" != "$apk_sha" ] || ! pm path "$pkg" >/dev/null 2>&1; then
    staged="/data/local/tmp/.esim_h4_${pkg}.apk"
    rm -f "$staged"
    if cp "$apk_path" "$staged" 2>/dev/null; then
      chmod 0644 "$staged"
      restorecon "$staged" >/dev/null 2>&1 || true
      if pm install -r "$staged" >/dev/null 2>&1; then
        echo "$apk_sha" > "$sha_file"
      fi
      rm -f "$staged"
    fi
  fi
}

cleanup_legacy_probe_pkg() {
  if pm path io.github.local.esimh4probe >/dev/null 2>&1; then
    logmsg "Removing legacy v0.4.0 package io.github.local.esimh4probe."
    pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
  fi
}

refresh_lsposed_index() {
  [ -x /data/adb/modules/zygisk_lsposed/lspctl ] && /data/adb/modules/zygisk_lsposed/lspctl module show --user=0 io.github.jabe1122.esimh4probe >/dev/null 2>&1 || true
  [ -x /data/adb/modules/riru_lsposed/lspctl ] && /data/adb/modules/riru_lsposed/lspctl module show --user=0 io.github.jabe1122.esimh4probe >/dev/null 2>&1 || true
}

sync_tester_on() {
  uri_pref=$(settings --user 0 get secure xiaomi_esim_h4_lpa_uri_bridge 2>/dev/null)
  if [ "$uri_pref" = 0 ]; then
    pm disable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  else
    pm enable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  fi
  read_tester_enabled
  if [ "$TESTER_ENABLED" = on ]; then
    sync_apk_if_needed io.github.local.esimh4tester "$MODDIR/tester.apk" "$STATE/tester.sha256"
  else
    pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
    rm -f "$STATE/tester.sha256"
  fi
}

sync_tester_off() {
  pm disable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
  pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
  rm -f "$STATE/tester.sha256"
}

apply_mode_on() {
  sync_lpa_umount_guard
  sync_tester_on
  requested=$(settings --user 0 get secure is_enable_esim_for_user)
  case "$requested" in
    0|1) ;;
    *) requested=1; settings --user 0 put secure is_enable_esim_for_user 1 ;;
  esac
  if [ "$requested" = 1 ]; then desired=true; else desired=false; fi
  current=$(sim2_get)
  case "$current" in
    true|false)
      settings --user 0 put secure xiaomi_esim_h4_card_type 1 >/dev/null
      if save_sim2_original "$current"; then
        [ "$current" = "$desired" ] || sim2_set "$desired"
      else
        logmsg "SIM2 backup unavailable; activation left unchanged."
      fi
      ;;
    empty|none|null)
      settings --user 0 put secure xiaomi_esim_h4_card_type 1 >/dev/null
      save_sim2_original "$current" >/dev/null 2>&1 || true
      logmsg "Verified empty or inactive eUICC in SIM2; logical eSIM switch ready."
      ;;
    *)
      logmsg "No verified eUICC in SIM2; SIM2 activation left unchanged."
      ;;
  esac
}

restore_settings() {
  strip_lpa_updates
  cleanup_unprivileged_lpa
  sync_tester_off
  [ -f "$STATE/originals.saved" ] || return 0
  value=$(cat "$STATE/secure.original")
  valid_value "$value" || return 1
  if [ "$value" = null ]; then
    settings --user 0 delete secure is_enable_esim_for_user >/dev/null
  else
    settings --user 0 put secure is_enable_esim_for_user "$value"
  fi
  value=$(cat "$STATE/provisioned.original")
  valid_value "$value" || return 1
  if [ "$value" = null ]; then
    settings delete global euicc_provisioned >/dev/null
  else
    settings put global euicc_provisioned "$value"
  fi
  restore_sim2
  for key in xiaomi_esim_h4_sim2_sub_id xiaomi_esim_h4_verified_iccid xiaomi_esim_h4_verified_eid xiaomi_esim_h4_card_type xiaomi_esim_h4_carrier_bridge xiaomi_esim_h4_lpa_uri_bridge xiaomi_esim_h4_rewrite_text; do
    settings --user 0 delete secure "$key" >/dev/null
  done
}

reset_property() {
  value=$(cat "$STATE/vendor-prop.original" 2>/dev/null)
  if [ -x /data/adb/ksud ]; then
    /data/adb/ksud resetprop -n ro.vendor.miui.support_esim "$value"
  else
    resetprop -n ro.vendor.miui.support_esim "$value"
  fi
}
''')

put('customize.sh', r'''#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "请从已开机的 KernelSU / SukiSU / Magisk / APatch 管理器中刷入。 / Install from KernelSU/SukiSU/Magisk/APatch while Android is running."
SDK=$(getprop ro.build.version.sdk)
[ -n "$SDK" ] && [ "$SDK" -ge 35 ] 2>/dev/null || abort "本模块仅支持小米澎湃 HyperOS 4 (Android 15+ / SDK 35+)。 / This module requires Xiaomi HyperOS 4 (Android 15+ / SDK 35+)."
[ -d /data/adb/modules/XiaomiEsimLPA ] && abort "检测到冲突的旧版 XiaomiEsimLPA 模块，请先移除并重启后再安装。 / Remove conflicting XiaomiEsimLPA module first."
OLD=/data/adb/modules/XiaomiEsimLPAHyperOS4
[ -f "$OLD/config" ] && cp "$OLD/config" "$MODPATH/config"
MODDIR=$MODPATH
. "$MODPATH/common.sh"
if is_ksu_missing_metamodule; then
  abort "检测到 KernelSU / SukiSU 未启用系统挂载元模块！请先安装并启用系统挂载元模块（推荐 Hybrid Mount 或 meta-overlayfs，不建议使用 Magic Mount-rs）并重启手机后再刷入本模块。 / KernelSU or SukiSU requires an active system-mount metamodule (Hybrid Mount or meta-overlayfs recommended) first."
fi
if pm path com.miui.euicc >/dev/null 2>&1; then
  if is_lpa_unprivileged_user_pkg; then
    ui_print "- 检测到无系统特权的 /data/app 小米 LPA 残留，正在自动卸载修复... / Repairing unprivileged /data/app com.miui.euicc install..." 2>/dev/null || true
    pm uninstall com.miui.euicc >/dev/null 2>&1
    rm -f "$STATE/lpa.sha256"
  elif [ ! -f "$STATE/originals.saved" ] && [ ! -d "$OLD" ] && is_lpa_system_pkg; then
    abort "检测到系统自带原生小米 LPA (com.miui.euicc)，无需刷入本适配模块。 / Existing stock system LPA detected; aborting."
  fi
fi
save_originals || abort "系统原始设置备份校验失败，已中止安装（未修改任何系统状态）。 / Settings backup failed validation. Nothing will be changed."
cp "$MODPATH/compat.apk" "$STATE/restore-helper.apk"
chmod 600 "$STATE/restore-helper.apk"
rm -f "$STATE/lpa.sha256"
if pm path io.github.local.esimh4probe >/dev/null 2>&1; then
  ui_print "- 检测到 v0.4.0 旧包名 io.github.local.esimh4probe，正在自动卸载清理... / Removing legacy io.github.local.esimh4probe..." 2>/dev/null || true
  pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
fi
sync_apk_if_needed io.github.jabe1122.esimh4probe "$MODPATH/compat.apk" "$STATE/compat.sha256"
refresh_lsposed_index
read_mode
if [ "$MODE" = on ]; then
  sync_lpa_umount_guard
  sync_tester_on
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in common.sh action.sh post-fs-data.sh service.sh uninstall.sh diagnostics.sh restore.sh restore-guard.sh; do
  set_perm "$MODPATH/$script" 0 0 0755
done
mkdir -p /data/adb/service.d
cp "$MODPATH/restore-guard.sh" /data/adb/service.d/xiaomi-esim-h4-restore.sh
set_perm /data/adb/service.d/xiaomi-esim-h4-restore.sh 0 0 0755
ui_print "- 版本 / Version: v0.6.0 (600)" 2>/dev/null || true
ui_print "- 适用范围 / Scope: HyperOS 4 (SDK 35+) / SIM2 可插拔实体 eSIM (ESTKme, 小白卡, 9eSIM, Xesim 等)" 2>/dev/null || true
ui_print "- 重要提示 / Reminder: 请在 LSPosed 中确认已勾选启用 HYPEROS4实体ESIM桥接 (io.github.jabe1122.esimh4probe)！" 2>/dev/null || true
ui_print "- SukiSU/KernelSU 提示: 推荐使用 Hybrid Mount 或 meta-overlayfs 元模块，内置内核 umount 自动免疫。" 2>/dev/null || true
ui_print "- 原始设置已备份，不修改卡内任何现有 eSIM 配置。 / Original settings saved; no eSIM profile is changed." 2>/dev/null || true
''')

put('post-fs-data.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
read_mode
if [ "$MODE" = on ]; then
  rm -f "$MODDIR/skip_mount"
  if [ -x /data/adb/ksud ]; then
    /data/adb/ksud resetprop -n ro.vendor.miui.support_esim true
  else
    resetprop -n ro.vendor.miui.support_esim true
  fi
  setprop sys.xiaomi_esim_h4.active 1
else
  touch "$MODDIR/skip_mount"
  setprop sys.xiaomi_esim_h4.active 0
  setprop sys.xiaomi_esim_h4.mount_ok 0
fi
''')

put('service.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
count=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 180 ]; do
  sleep 1
  count=$((count + 1))
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
cleanup_legacy_probe_pkg
sync_apk_if_needed io.github.jabe1122.esimh4probe "$MODDIR/compat.apk" "$STATE/compat.sha256"
refresh_lsposed_index
cleanup_unprivileged_lpa
read_mode
if [ "$MODE" = on ] && [ "$(getprop sys.xiaomi_esim_h4.active)" = 1 ]; then
  if check_lpa_mount_ok; then
    apply_mode_on
    logmsg "ON: LPA system privilege mount & umount guard verified; eSIM management enabled; SIM2 activation synced."
  else
    setprop sys.xiaomi_esim_h4.active 0
    sync_tester_on
    logmsg "ERROR: MODE=on but LPA system overlay is NOT mounted (mount_ok=0). Install/enable Hybrid Mount or meta-overlayfs and reboot."
  fi
else
  restore_settings
  setprop sys.xiaomi_esim_h4.active 0
  setprop sys.xiaomi_esim_h4.mount_ok 0
  logmsg "OFF: saved settings restored."
fi
''')

put('action.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
mkdir -p "$STATE"
chmod 700 "$STATE"

set_mode_off() {
  echo MODE=off > "$MODDIR/config"
  touch "$MODDIR/skip_mount"
  restore_settings
  setprop sys.xiaomi_esim_h4.active 0
  setprop sys.xiaomi_esim_h4.mount_ok 0
  echo "RESULT=OFF_APPLIED"
  logmsg "已切换为关闭。模拟测试应用与运行状态已清理还原，请重启手机以撤销底层能力声明。"
}

set_mode_on() {
  echo MODE=on > "$MODDIR/config"
  rm -f "$MODDIR/skip_mount"
  cleanup_legacy_probe_pkg
  cleanup_unprivileged_lpa
  if [ -f /product/etc/permissions/privapp-permissions-euicc.xml ] \
     && [ -f /product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk ] \
     && is_lpa_system_pkg; then
    setprop sys.xiaomi_esim_h4.mount_ok 1
    setprop sys.xiaomi_esim_h4.active 1
    if [ -x /data/adb/ksud ]; then
      /data/adb/ksud resetprop -n ro.vendor.miui.support_esim true
    else
      resetprop -n ro.vendor.miui.support_esim true
    fi
    apply_mode_on
    echo "RESULT=ACTIVE_NOW"
    logmsg "已恢复为开启状态（当前 LPA 系统特权已挂载生效，无需重启）。"
  else
    setprop sys.xiaomi_esim_h4.mount_ok 0
    if is_ksu_missing_metamodule; then
      echo "RESULT=MISSING_METAMODULE"
      logmsg "⚠️ 警告：检测到 KernelSU/SukiSU 未启用系统挂载元模块（推荐 Hybrid Mount 或 meta-overlayfs）！请先安装并启用元模块后再重启手机。"
    else
      echo "RESULT=NEED_REBOOT"
      logmsg "已切换为开启。请重启手机，加载系统 eSIM 特权能力声明和小米 LPA。"
    fi
  fi
}

case "$1" in
  on)
    set_mode_on
    ;;
  off)
    set_mode_off
    ;;
  umount_guard)
    if [ "$2" = off ]; then
      echo UMOUNT_GUARD=off > "$STATE/umount_guard"
    else
      echo UMOUNT_GUARD=on > "$STATE/umount_guard"
    fi
    sync_lpa_umount_guard
    echo "RESULT=OK"
    ;;
  tester)
    if [ "$2" = off ]; then
      echo TESTER=off > "$STATE/tester_enabled"
      pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
      rm -f "$STATE/tester.sha256"
    else
      echo TESTER=on > "$STATE/tester_enabled"
      sync_apk_if_needed io.github.local.esimh4tester "$MODDIR/tester.apk" "$STATE/tester.sha256"
    fi
    echo "RESULT=OK"
    ;;
  *)
    read_mode
    if [ "$MODE" = on ]; then
      set_mode_off
    else
      set_mode_on
    fi
    ;;
esac
''')

put('restore.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
echo MODE=off > "$MODDIR/config"
touch "$MODDIR/skip_mount"
restore_settings
setprop sys.xiaomi_esim_h4.active 0
setprop sys.xiaomi_esim_h4.mount_ok 0
reset_property
logmsg "原有设置已还原，模拟测试应用已卸载。重启后完成卸载系统能力声明。卡内配置未修改。"
''')

put('restore-guard.sh', r'''#!/system/bin/sh
MODDIR=/data/adb/modules/XiaomiEsimLPAHyperOS4
STATE=/data/adb/xiaomi-esim-hyperos4
count=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 180 ]; do
  sleep 1
  count=$((count + 1))
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
  if [ -f "$STATE/originals.saved" ]; then
    value=$(cat "$STATE/secure.original")
    case "$value" in
      null) settings --user 0 delete secure is_enable_esim_for_user >/dev/null ;;
      *) echo "$value" | grep -Eq '^-?[0-9]+$' && settings --user 0 put secure is_enable_esim_for_user "$value" ;;
    esac
    value=$(cat "$STATE/provisioned.original")
    case "$value" in
      null) settings delete global euicc_provisioned >/dev/null ;;
      *) echo "$value" | grep -Eq '^-?[0-9]+$' && settings put global euicc_provisioned "$value" ;;
    esac
  fi
  if dumpsys package com.miui.euicc 2>/dev/null | grep -Eq '(pkgFlags|flags)=\[[^]]*(^| )UPDATED_SYSTEM_APP( |$|\])'; then
    pm uninstall-system-updates com.miui.euicc >/dev/null 2>&1 || true
  fi
  if pm list packages -3 com.miui.euicc 2>/dev/null | grep -qx 'package:com.miui.euicc'; then
    pm uninstall com.miui.euicc >/dev/null 2>&1
  fi
  rm -f "$STATE/lpa.sha256"
  pm disable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
  pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
  rm -f "$STATE/tester.sha256"
  if [ -f "$STATE/sim2.original" ] && [ -f "$STATE/restore-helper.apk" ]; then
    value=$(cat "$STATE/sim2.original")
    card_key=$(cat "$STATE/sim2.card.original" 2>/dev/null)
    case "$value" in
      true|false) [ -n "$card_key" ] && CLASSPATH="$STATE/restore-helper.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control set "$value" "$card_key" >/dev/null 2>&1 ;;
    esac
  fi
  for key in xiaomi_esim_h4_sim2_sub_id xiaomi_esim_h4_verified_iccid xiaomi_esim_h4_verified_eid xiaomi_esim_h4_card_type xiaomi_esim_h4_carrier_bridge xiaomi_esim_h4_lpa_uri_bridge xiaomi_esim_h4_rewrite_text; do
    settings --user 0 delete secure "$key" >/dev/null
  done
  setprop sys.xiaomi_esim_h4.active 0
  setprop sys.xiaomi_esim_h4.mount_ok 0
fi
''')

put('diagnostics.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
read_mode
read_umount_guard
read_tester_enabled
echo "version=v0.6.0"
echo "mode=$MODE"
echo "umount_guard=$UMOUNT_GUARD"
echo "tester_enabled=$TESTER_ENABLED"
echo "build=$(getprop ro.build.version.incremental)"
echo "active=$(getprop sys.xiaomi_esim_h4.active)"
echo "mount_ok=$(getprop sys.xiaomi_esim_h4.mount_ok)"
echo "property=$(getprop ro.vendor.miui.support_esim)"
echo "secure=$(settings --user 0 get secure is_enable_esim_for_user)"
echo "card_type=$(settings --user 0 get secure xiaomi_esim_h4_card_type)"
echo "lpa_overlay_apk=$([ -f /product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk ] && echo true || echo false)"
echo "lpa_priv_xml=$([ -f /product/etc/permissions/privapp-permissions-euicc.xml ] && echo true || echo false)"
echo "lpa_system_pkg=$(is_lpa_system_pkg && echo true || echo false)"
echo "lpa_updated_system_pkg=$(is_lpa_updated_system_pkg && echo true || echo false)"
echo "metamodule=$([ -e /data/adb/metamodule ] && echo present || echo absent)"
pm list features | grep telephony.euicc
pm path com.miui.euicc
pm path io.github.jabe1122.esimh4probe
pm path io.github.local.esimh4tester
service list | grep -i euicc
echo "sim_state=$(getprop gsm.sim.state)"
''')

put('uninstall.sh', r'''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
restore_settings
reset_property
setprop sys.xiaomi_esim_h4.active 0
setprop sys.xiaomi_esim_h4.mount_ok 0
rm -f /data/adb/service.d/xiaomi-esim-h4-restore.sh
rm -f "$STATE/lpa.sha256" "$STATE/umount_guard" "$STATE/tester_enabled"
pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
pm uninstall io.github.jabe1122.esimh4probe >/dev/null 2>&1
pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
pm uninstall-system-updates com.miui.euicc >/dev/null 2>&1 || true
pm uninstall com.miui.euicc >/dev/null 2>&1 || true
''')

lpa_target = MOD / 'system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk'
lpa_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(PREBUILT_LPA, lpa_target)
shutil.copy2(COMPAT_APK, MOD / 'compat.apk')
shutil.copy2(TESTER_APK, MOD / 'tester.apk')

zip_path = OUT / 'XiaomiEsimLPA-HyperOS4-v0.6.0.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for p in sorted(MOD.rglob('*')):
        if p.is_file():
            zf.write(p, p.relative_to(MOD).as_posix())

print('LPA SHA256', hashlib.sha256(lpa_target.read_bytes()).hexdigest())
print(zip_path)
