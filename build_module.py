from pathlib import Path
import argparse
import hashlib
import shutil
import zipfile

BASE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description='Build Xiaomi MIX Flip 2 HyperOS 4 eSIM module')
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

put('META-INF/com/google/android/update-binary', '''#!/sbin/sh
umask 022
ui_print() { echo "$1"; }
OUTFD=$2
ZIPFILE=$3
if [ ! -f /data/adb/magisk/util_functions.sh ]; then
  ui_print "! Require KernelSU or Magisk v20.4+"
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
version=v0.4.0
versionCode=400
author=Local HyperOS 4 eSIM Adaptation
description=ESTKme in SIM2. Native eSIM feature, Xiaomi HyperOS 4 LPA v4.1.0.1, and one-click carrier install bridge. Action toggles mode; uninstall/disable restores all state.
''')

put('config', 'MODE=off\n')

put('common.sh', '''#!/system/bin/sh
MODDIR=${MODDIR:-${0%/*}}
STATE=/data/adb/xiaomi-esim-hyperos4

logmsg() {
  echo "$1"
  log -t XiaomiEsimH4 "$1"
}

read_mode() {
  MODE=$(cat "$MODDIR/config" 2>/dev/null)
  MODE=${MODE#MODE=}
  [ "$MODE" = on ] || MODE=off
}

valid_value() {
  [ "$1" = null ] || echo "$1" | grep -Eq '^-?[0-9]+$'
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
  if [ ! -f "$STATE/sim2.card.original" ]; then
    card_key=$(CLASSPATH="$MODDIR/compat.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control probe 2>/dev/null | sed -n 's/^CARD_KEY=//p' | tail -n 1)
    echo "$card_key" | grep -Eq '^[0-9a-f]{64}$' || return 1
    echo "$card_key" > "$STATE/sim2.card.original"
  fi
  if [ -f "$STATE/sim2.original" ]; then
    value=$(cat "$STATE/sim2.original")
  else
    value=$(sim2_get)
    case "$value" in
      true|false) echo "$value" > "$STATE/sim2.original" ;;
      *) return 1 ;;
    esac
  fi
  [ "$value" = true ] || [ "$value" = false ]
}

restore_sim2() {
  [ -f "$STATE/sim2.original" ] || return 0
  value=$(cat "$STATE/sim2.original")
  case "$value" in
    true|false) [ "$(sim2_get)" = "$value" ] || sim2_set "$value" ;;
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
  [ -f "$apk_path" ] || return 0
  apk_sha=$(sha256sum "$apk_path" | cut -d ' ' -f 1)
  installed_path=$(pm path "$pkg" 2>/dev/null | head -n 1 | cut -d : -f 2)
  if [ -n "$installed_path" ] && [ "$(sha256sum "$installed_path" 2>/dev/null | cut -d ' ' -f 1)" = "$apk_sha" ]; then
    echo "$apk_sha" > "$sha_file"
    return 0
  fi
  if [ "$(cat "$sha_file" 2>/dev/null)" != "$apk_sha" ] || ! pm path "$pkg" >/dev/null 2>&1; then
    pm install -r "$apk_path" >/dev/null 2>&1 && echo "$apk_sha" > "$sha_file"
  fi
}

sync_tester_on() {
  pm enable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  pm enable io.github.local.esimh4probe/.LpaBridgeActivity >/dev/null 2>&1
  sync_apk_if_needed io.github.local.esimh4tester "$MODDIR/tester.apk" "$STATE/tester.sha256"
}

sync_tester_off() {
  pm disable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  pm disable io.github.local.esimh4probe/.LpaBridgeActivity >/dev/null 2>&1
  pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
  rm -f "$STATE/tester.sha256"
}

apply_mode_on() {
  sync_tester_on
  requested=$(settings --user 0 get secure is_enable_esim_for_user)
  case "$requested" in
    0|1) ;;
    *) requested=1; settings --user 0 put secure is_enable_esim_for_user 1 ;;
  esac
  if [ "$requested" = 1 ]; then desired=true; else desired=false; fi
  current=$(sim2_get)
  if [ "$current" = true ] || [ "$current" = false ]; then
    if save_sim2_original; then
      [ "$current" = "$desired" ] || sim2_set "$desired"
    else
      logmsg "SIM2 backup unavailable; activation left unchanged."
    fi
  else
    logmsg "No verified eUICC in SIM2; SIM2 activation left unchanged."
  fi
}

restore_settings() {
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
  for key in xiaomi_esim_h4_sim2_sub_id xiaomi_esim_h4_verified_iccid xiaomi_esim_h4_verified_eid xiaomi_esim_h4_card_type; do
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

put('customize.sh', '''#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from KernelSU/Magisk while Android is running."
SDK=$(getprop ro.build.version.sdk)
[ -n "$SDK" ] && [ "$SDK" -ge 35 ] 2>/dev/null || abort "This module requires Xiaomi HyperOS (Android 15+ / SDK 35+)."
[ -d /data/adb/modules/XiaomiEsimLPA ] && abort "Remove conflicting XiaomiEsimLPA module first."
if [ "$KSU" = true ] && [ ! -e /data/adb/metamodule ]; then
  abort "KernelSU requires an active system-mount metamodule first."
fi
OLD=/data/adb/modules/XiaomiEsimLPAHyperOS4
[ -f "$OLD/config" ] && cp "$OLD/config" "$MODPATH/config"
MODDIR=$MODPATH
. "$MODPATH/common.sh"
[ ! -f "$STATE/originals.saved" ] && pm path com.miui.euicc >/dev/null 2>&1 && abort "An existing LPA is installed. Preserve/remove it before installing this adaptation."
save_originals || abort "Settings backup failed validation. Nothing will be changed."
cp "$MODPATH/compat.apk" "$STATE/restore-helper.apk"
chmod 600 "$STATE/restore-helper.apk"
sync_apk_if_needed io.github.jabe1122.esimh4probe "$MODPATH/compat.apk" "$STATE/compat.sha256"
sync_apk_if_needed com.miui.euicc "$MODPATH/lpa.apk" "$STATE/lpa.sha256"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in common.sh action.sh post-fs-data.sh service.sh uninstall.sh diagnostics.sh restore.sh; do
  set_perm "$MODPATH/$script" 0 0 0755
done
mkdir -p /data/adb/service.d
cp "$MODPATH/restore-guard.sh" /data/adb/service.d/xiaomi-esim-h4-restore.sh
set_perm /data/adb/service.d/xiaomi-esim-h4-restore.sh 0 0 0755
ui_print "- Scope: HyperOS 4 / ESTKme in SIM2" 2>/dev/null || true
ui_print "- Original settings saved. No eSIM profile is changed." 2>/dev/null || true
ui_print "- Action switches on/off; reboot applies the selected mode." 2>/dev/null || true
''')

put('post-fs-data.sh', '''#!/system/bin/sh
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
fi
''')

put('service.sh', '''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
count=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 180 ]; do
  sleep 1
  count=$((count + 1))
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
sync_apk_if_needed io.github.jabe1122.esimh4probe "$MODDIR/compat.apk" "$STATE/compat.sha256"
sync_apk_if_needed com.miui.euicc "$MODDIR/lpa.apk" "$STATE/lpa.sha256"
read_mode
if [ "$MODE" = on ] && [ "$(getprop sys.xiaomi_esim_h4.active)" = 1 ]; then
  apply_mode_on
  logmsg "ON: eSIM management enabled; SIM2 activation synced."
else
  restore_settings
  logmsg "OFF: saved settings restored."
fi
''')

put('action.sh', '''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
read_mode
if [ "$MODE" = on ]; then
  echo MODE=off > "$MODDIR/config"
  touch "$MODDIR/skip_mount"
  setprop sys.xiaomi_esim_h4.active 0
  restore_settings
  logmsg "已切换为关闭。模拟测试应用与运行状态已清理还原，请重启手机以撤销底层能力声明。"
else
  echo MODE=on > "$MODDIR/config"
  rm -f "$MODDIR/skip_mount"
  if [ -f /system/product/etc/permissions/android.hardware.telephony.euicc.xml ]; then
    setprop sys.xiaomi_esim_h4.active 1
    apply_mode_on
    logmsg "已恢复为开启状态（当前系统已挂载生效，无需重启）。"
  else
    logmsg "已切换为开启。请重启手机，加载系统 eSIM 能力声明和 LPA。"
  fi
fi
''')

put('restore.sh', '''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
echo MODE=off > "$MODDIR/config"
touch "$MODDIR/skip_mount"
setprop sys.xiaomi_esim_h4.active 0
restore_settings
reset_property
logmsg "原有设置已还原，模拟测试应用已卸载。重启后完成卸载系统能力声明。卡内配置未修改。"
''')

put('restore-guard.sh', '''#!/system/bin/sh
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
  setprop sys.xiaomi_esim_h4.active 0
  pm disable io.github.jabe1122.esimh4probe/io.github.local.esimh4probe.LpaBridgeActivity >/dev/null 2>&1
  pm disable io.github.local.esimh4probe/.LpaBridgeActivity >/dev/null 2>&1
  pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
  rm -f "$STATE/tester.sha256"
  if [ -f "$STATE/sim2.original" ] && [ -f "$STATE/restore-helper.apk" ]; then
    value=$(cat "$STATE/sim2.original")
    card_key=$(cat "$STATE/sim2.card.original" 2>/dev/null)
    case "$value" in
      true|false) [ -n "$card_key" ] && CLASSPATH="$STATE/restore-helper.apk" app_process /system/bin io.github.local.esimh4probe.Sim2Control set "$value" "$card_key" >/dev/null 2>&1 ;;
    esac
  fi
  for key in xiaomi_esim_h4_sim2_sub_id xiaomi_esim_h4_verified_iccid xiaomi_esim_h4_verified_eid xiaomi_esim_h4_card_type; do
    settings --user 0 delete secure "$key" >/dev/null
  done
fi
''')

put('diagnostics.sh', '''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
read_mode
echo "mode=$MODE"
echo "build=$(getprop ro.build.version.incremental)"
echo "active=$(getprop sys.xiaomi_esim_h4.active)"
echo "property=$(getprop ro.vendor.miui.support_esim)"
echo "secure=$(settings --user 0 get secure is_enable_esim_for_user)"
pm list features | grep telephony.euicc
pm path com.miui.euicc
pm path io.github.local.esimh4tester
service list | grep -i euicc
echo "sim_state=$(getprop gsm.sim.state)"
''')

put('uninstall.sh', '''#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
restore_settings
reset_property
setprop sys.xiaomi_esim_h4.active 0
rm -f /data/adb/service.d/xiaomi-esim-h4-restore.sh
pm uninstall io.github.local.esimh4tester >/dev/null 2>&1
pm uninstall io.github.jabe1122.esimh4probe >/dev/null 2>&1
pm uninstall io.github.local.esimh4probe >/dev/null 2>&1
pm uninstall com.miui.euicc >/dev/null 2>&1
''')

lpa_target = MOD / 'system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk'
lpa_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(PREBUILT_LPA, lpa_target)
shutil.copy2(PREBUILT_LPA, MOD / 'lpa.apk')
shutil.copy2(COMPAT_APK, MOD / 'compat.apk')
shutil.copy2(TESTER_APK, MOD / 'tester.apk')

zip_path = OUT / 'XiaomiEsimLPA-HyperOS4-bixi-v0.4.0.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for p in sorted(MOD.rglob('*')):
        if p.is_file():
            zf.write(p, p.relative_to(MOD).as_posix())

print('LPA SHA256', hashlib.sha256(lpa_target.read_bytes()).hexdigest())
print(zip_path)
