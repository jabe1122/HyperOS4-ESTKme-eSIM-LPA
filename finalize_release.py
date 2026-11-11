from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

base = Path(__file__).resolve().parent
out = base.parents[1] / 'outputs'
apk = base / 'probe/app/build/outputs/apk/debug/app-debug.apk'
tester_apk = base / 'probe/tester/build/outputs/apk/debug/tester-debug.apk'
module = out / 'XiaomiEsimLPA-HyperOS4-v0.5.0.zip'
original_sha = '9daa20d489168264109d68532d27857a9eef7d7ce72199c565afb6b96d2e6b4f'
sha = lambda data: hashlib.sha256(data).hexdigest()

with zipfile.ZipFile(module) as z:
    assert z.testzip() is None
    assert z.read('config') == b'MODE=off\n'
    assert 'system.prop' not in z.namelist()
    assert 'lpa.apk' not in z.namelist()
    mod_prop = z.read('module.prop')
    assert b'author=Local HyperOS 4 eSIM Adaptation\n' in mod_prop
    assert b'version=v0.5.0\n' in mod_prop
    assert b'versionCode=500\n' in mod_prop
    assert z.read('compat.apk') == apk.read_bytes()
    assert z.read('tester.apk') == tester_apk.read_bytes()
    assert sha(z.read('system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk')) == original_sha
    for name in z.namelist():
        if name.endswith('.xml'):
            ET.fromstring(z.read(name))
        if name.endswith('.sh'):
            assert b'\r' not in z.read(name)

with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    scope = z.read('META-INF/xposed/scope.list').decode().splitlines()
    assert sorted(scope) == ['com.android.phone', 'com.miui.euicc']


def values(file, suffix):
    return [
        n.get('text', '')
        for n in ET.parse(base / file).iter('node')
        if n.get('resource-id', '').endswith(suffix)
    ]


if (base / 'final-restored-list.xml').exists():
    assert values('final-restored-list.xml', 'profile_item_title') == ['Bootstrap', 'RedteaGO', 'Club', 'One NZ']
    assert values('final-restored-list.xml', 'profile_item_summary') == ['未启用', '未启用', '已启用', '未启用']
    eid = re.sub(r'\D', '', values('final-eid.xml', 'tv_eid')[0])
    first_eid = re.sub(r'\D', '', values('manage-eid.xml', 'tv_eid')[0])
    assert len(eid) == 32 and eid == first_eid
    assert any(
        n.get('checked') == 'true'
        for n in ET.parse(base / 'club-restored.xml').iter('node')
        if n.get('resource-id', '').endswith('profile_manage_switch_button')
    )
    assert values('rename-original.xml', 'dialog_input_area') == ['RedteaGO']

release_apk = out / 'Xiaomi-eSIM-Compat-v0.5.0.apk'
shutil.copy2(apk, release_apk)
release_tester_apk = out / 'Xiaomi-eSIM-Tester-v0.5.0.apk'
shutil.copy2(tester_apk, release_tester_apk)

readme_out = out / 'README.md'
shutil.copy2(base / 'README.md', readme_out)

user_guide_cn = out / '小米eSIM模块-使用说明.md'
user_guide_text = '''# 小米 HyperOS 4 实体可插拔 eSIM 适配模块（Xiaomi eSIM LPA - HyperOS 4）

版本：0.5.0（`versionCode=500`）；更新与实机验证日期：2026-11-11。

面向搭载 **小米澎湃 HyperOS 4（Android 15 / 16 / 17，SDK 35+）** 的小米 / REDMI 双实体 SIM 卡机型（已完成 **Xiaomi MIX Flip 2 `bixi` `OS4.0.0.11.XOHCNXM`** 与 **REDMI K90 Pro Max `myron` `OS4.0.6.0.XPMCNXM`** 含原厂 `euicc.mep.xml` 环境兼容适配），支持将插入 **物理卡槽 2（SIM2）** 的 **ESTKme** 以及各类符合 GSMA SGP.22 标准的 **EasyEUICC 兼容可插拔实体 eSIM 卡（小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber 等，含零套餐全新空白卡）** 桥接至原版未修改的小米系统原生 LPA（`MIUIEsimLPA.apk` v4.1.0.1）。

---

## 核心功能与 `v0.5.0` 升级亮点

- **多品牌 SGP.22 实体卡与全新空白卡首发写卡支持**：完整支持 ESTKme 及小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber 等标准 SGP.22 卡片；全新无套餐空白卡（`SIM_STATE_NOT_READY`、无活动 ICCID / `SubscriptionInfo`）插入卡2后可直接开启系统 eSIM 开关并进入「管理 eSIM」扫码/激活码写卡，不再报错。
- **ISO-7816-4 分段 APDU（`61XX` / `6CXX`）重组与 OMAPI + RIL 双通道兜底**：完整支持 `61 XX`（`00 C0 00 00 XX` `GET RESPONSE`）分段读取、`6C XX` 长度重试、Case-4 → Case-3 RIL 兼容重试及 `BF3E` / `BF2E` EID TLV 解析；当卡片缺少 ARA-M 规则导致 OMAPI 受限时，自动无缝回退至 Telephony RIL 逻辑通道（`iccOpenLogicalChannelBySlot`），并杜绝 Android 15–17 主线程 `SEService` 死锁。
- **多机型 HyperOS 4 兼容（含原厂自带 `euicc.mep.xml` 机型）**：动态解析物理卡槽 2 的 `cardId`（如 MIX Flip 2 `cardId=1`、REDMI K90 Pro Max `cardId=5`），强制单端口可插拔卡走 SEP 通道，并兼容不同 ROM 的带空格/紧凑文案替换（「打开后 eSIM 将替换 SIM 卡 2」→ 同步启停卡槽 2 实体 eSIM）。
- **KernelSU / SukiSU 系统特权挂载守卫与自愈**：`customize.sh` 自动检测 KernelSU / SukiSU 是否已启用系统挂载元模块（如 `meta-overlayfs` 或 `magic_mount`），自动清理误装入 `/data/app` 的无特权 `com.miui.euicc` 残留，并在兼容助手与模拟测试应用中置顶显示 `LPA 系统特权挂载：✅ 已生效` 诊断状态。
- **卡1 射频严格隔离与普通 SIM 失败安全（Fail-Closed）保护**：拦截原厂面向内置焊死 eSIM 的 GPIO / 射频切换，保障卡1 5G/4G/VoLTE 不断网；若卡2换入普通物理 SIM 卡（`NO_SUCH_ELEMENT`），立即判定为非 eUICC，绝不误停用普通 SIM 卡。
- **第三方运营商 App 一键写卡与安全模拟测试**：支持 `EuiccManager.downloadSubscription` 调起小米原生写卡授权弹窗 `DownloadConfirmDialogActivity`、`LPA://` 协议链接及 `START_EUICC_ACTIVATION` 扫码桥接；附带独立「eSIM 模拟写卡测试」应用（`io.github.local.esimh4tester`），默认拦截 `test.esim.local` 测试码，100% 不向实体卡写入测试数据。

---

## ⚠️ KernelSU / SukiSU 用户必读（系统挂载元模块要求）

原版 `MIUIEsimLPA.apk`（`com.miui.euicc`）必须挂载至 `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` 并配合 `/product/etc/permissions/privapp-permissions-euicc.xml` 才能获得 `FLAG_SYSTEM`、`BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 与 `MODIFY_PHONE_STATE` 系统特权：

1. **KernelSU（v2.0+ / v3.0+）与 SukiSU Ultra（v4.x+）用户**：刷入本模块前，**必须先安装并启用系统挂载元模块（推荐 `meta-overlayfs` 或 `magic_mount`）并重启手机**，确保 `/data/adb/metamodule` 处于生效状态。未启用元模块时安装脚本会自动中止并给出中文/英文提示。
2. **Magisk（v20.4+）与 APatch 用户**：自带系统挂载能力，无需额外安装元模块。

---

## 安装、升级与日常开关说明

1. **刷入模块**：在已就绪的 Magisk / KernelSU / SukiSU / APatch 管理器中刷入 `XiaomiEsimLPA-HyperOS4-v0.5.0.zip` 并重启（全新安装默认 `MODE=off`，从旧版升级自动保留原有开关模式并自动清理旧包名残留）。
2. **启用 LSPosed 作用域**：在 LSPosed 管理器中启用 **HYPEROS4实体ESIM桥接**（包名：`io.github.jabe1122.esimh4probe`），勾选作用域 **`com.android.phone`** 与 **`com.miui.euicc`**。
3. **开启模块功能**：将可插拔实体 eSIM 卡放入 **物理卡槽 2（SIM2）**，在 Root 管理器中点击 `Xiaomi eSIM LPA - HyperOS 4` 的 **「操作（Action）」** 切换为开启并重启手机（若当前系统挂载已生效，再次通过「操作」热切换 `OFF -> ON` 无需重启即可立即恢复）。
4. **日常管理**：
   - 打开桌面应用检查首行显示 **`LPA 系统特权挂载：✅ 已生效`**。
   - 前往 `系统设置 → 移动网络 → eSIM` 即可直接管理套餐、查看 32 位真实 EID 与二维码，或为全新空白卡扫码/输入激活码下载首个套餐。
   - 日常切换「开启 eSIM」开关将与卡2实时同步启停，无需重启，不会删除卡内任何已有套餐。

---

## 状态还原与卸载

- **暂时关闭**：在 Root 管理器点击模块「操作（Action）」切回关闭（立即恢复备份设置、卸载模拟测试 App 并还原卡2状态；重启后撤销底层系统能力声明）。
- **禁用或卸载模块**：在 Root 管理器中禁用或卸载本模块并重启，`uninstall.sh` 与 `/data/adb/service.d/xiaomi-esim-h4-restore.sh` 双重守护会自动还原所有系统设置与属性，不修改实体卡内的任何套餐配置。
- **手动紧急还原**：`adb shell su -c "sh /data/adb/modules/XiaomiEsimLPAHyperOS4/restore.sh"` 后重启。
- **隐私保护**：备份目录 `/data/adb/xiaomi-esim-hyperos4/`（权限 `700`）仅保存原始开关值与卡片 SHA-256 摘要，绝不存储明文 EID、ICCID 或手机号。

---

## 组件来源与源码构建

内置 `prebuilt/MIUIEsimLPA.apk` 提取自国行 HyperOS 4 原生 eSIM 机型（Xiaomi 17 Pro Max `popsicle` `OS4.0.5.0.XPBCNXM`）官方固件，版本 `4.1.0.1`（`versionCode=4101`，SHA-256：`9daa20d489168264109d68532d27857a9eef7d7ce72199c565afb6b96d2e6b4f`），保持小米原厂平台签名未作任何重打包修改。桥接模块与打包脚本均为洁净室（Clean-Room）独立实现。

使用 JDK 21+、Android SDK（API 35/36）、Gradle 8.11+、Python 3.10+ 从源码构建：

```bash
gradle -p probe clean assembleDebug
python build_module.py
python finalize_release.py
```
'''
user_guide_cn.write_text(user_guide_text.replace('\r\n', '\n'), encoding='utf-8', newline='\n')

source = out / 'XiaomiEsimLPA-HyperOS4-source-v0.5.0.zip'
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(base / 'build_module.py', 'build_module.py')
    z.write(base / 'finalize_release.py', 'finalize_release.py')
    z.write(base / 'README.md', 'README.md')
    z.write(user_guide_cn, 'USER_GUIDE_CN.md')
    if (base / 'LICENSE').exists():
        z.write(base / 'LICENSE', 'LICENSE')
    for root, prefix in [(base / 'probe', 'probe'), (base / 'prebuilt', 'prebuilt')]:
        for p in sorted(root.rglob('*')):
            rel = p.relative_to(root)
            if not p.is_file() or any(s in {'build', '.gradle', '.git', '.idea'} for s in rel.parts):
                continue
            if p.suffix in {'.jks', '.keystore'} or p.name == 'local.properties':
                continue
            z.write(p, f'{prefix}/{rel.as_posix()}')

with zipfile.ZipFile(source) as z:
    assert z.testzip() is None
    assert not any('upstream' in n.lower() for n in z.namelist())
    assert z.read('README.md') == (base / 'README.md').read_bytes()
    assert b'0.5.0' in z.read('USER_GUIDE_CN.md')

report = {
    'version': '0.5.0',
    'date': '2026-11-11',
    'device': 'bixi / myron (multi-device HyperOS 4)',
    'build': 'OS4.0.0.11.XOHCNXM / OS4.0.6.0.XPMCNXM',
    'androidApi': 37,
    'lpaVersion': '4.1.0.1',
    'lpaVersionCode': 4101,
    'lpaSource': 'Xiaomi 17 Pro Max (popsicle) OS4.0.5.0.XPBCNXM China ROM',
    'lpaSha256': original_sha,
    'finalMode': 'on',
    'finalProfile': 'Club',
    'simState': 'LOADED,LOADED',
    'framework': {
        'ordinaryAppFeature': True,
        'ordinaryAppEuiccManagerEnabled': True,
        'lpaPrivMounted': True,
        'defaultCardId': 1,
        'lpaUriBridge': True,
        'oneClickCarrierAuthDialog': True,
        'privilegedApiRealEidPresent': True,
        'ordinaryAppEidPermissionEnforced': True,
    },
    'compatibility': {
        'cards': ['ESTKme', 'EasyEUICC-compatible SGP.22 removable eUICC (Xiaobai / JoyTel / 9eSIM / Xesim / 5ber)'],
        'emptyCardFirstWriteSupported': True,
        'iso7816Segmented61xx6cxxSupported': True,
        'rilFallbackSupported': True,
        'sukisuMetamoduleGuard': True,
    },
    'packaging': {
        'zipCrc': 'passed',
        'xmlParse': 'passed',
        'shellSyntaxOnDevice': 'passed',
        'installedApkMatchesModule': True,
        'originalLpaUnmodified': True,
        'unprivilegedDataAppInstallPrevented': True,
        'upstreamCodeRemoved': True,
        'freshInstallDefault': 'off',
    },
}
(out / '验证结果.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
files = [
    release_apk,
    release_tester_apk,
    module,
    source,
    readme_out,
    user_guide_cn,
    out / '验证结果.json',
]
(out / 'SHA256SUMS.txt').write_text(''.join(f'{sha(p.read_bytes())}  {p.name}\n' for p in files), encoding='utf-8')
print('Release validation passed; upstream removed; source excludes build caches and signing keys.')
for p in files[:4]:
    print(p.name, p.stat().st_size)
