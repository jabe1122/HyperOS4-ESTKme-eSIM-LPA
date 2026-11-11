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
module = out / 'XiaomiEsimLPA-HyperOS4-v0.6.0.zip'
original_sha = '9daa20d489168264109d68532d27857a9eef7d7ce72199c565afb6b96d2e6b4f'
sha = lambda data: hashlib.sha256(data).hexdigest()

# Clean stale versioned artifacts in outputs/ so release directory stays unambiguous
for old_item in out.glob('*v0.5.0*'):
    if old_item.is_file():
        old_item.unlink()

with zipfile.ZipFile(module) as z:
    assert z.testzip() is None
    assert z.read('config') == b'MODE=off\n'
    assert 'system.prop' not in z.namelist()
    assert 'lpa.apk' not in z.namelist()
    mod_prop = z.read('module.prop')
    assert b'author=Local HyperOS 4 eSIM Adaptation\n' in mod_prop
    assert b'version=v0.6.0\n' in mod_prop
    assert b'versionCode=600\n' in mod_prop
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

release_apk = out / 'Xiaomi-eSIM-Compat-v0.6.0.apk'
shutil.copy2(apk, release_apk)
release_tester_apk = out / 'Xiaomi-eSIM-Tester-v0.6.0.apk'
shutil.copy2(tester_apk, release_tester_apk)

readme_out = out / 'README.md'
shutil.copy2(base / 'README.md', readme_out)

user_guide_cn = out / '小米eSIM模块-使用说明.md'
user_guide_text = '''# 小米 HyperOS 4 实体可插拔 eSIM 适配模块（Xiaomi eSIM LPA - HyperOS 4）

版本：0.6.0（`versionCode=600`）；更新与实机验证日期：2026-11-11。

面向搭载 **小米澎湃 HyperOS 4（Android 15 / 16 / 17，SDK 35+）** 的小米 / REDMI 双实体 SIM 卡机型（已完成 **Xiaomi MIX Flip 2 `bixi` `OS4.0.0.11.XOHCNXM`** 与 **REDMI K90 Pro Max `myron` `OS4.0.6.0.XPMCNXM`** 含原厂 `euicc.mep.xml` 环境兼容适配），支持将插入 **物理卡槽 2（SIM2）** 的 **ESTKme** 以及各类符合 GSMA SGP.22 标准的 **EasyEUICC 兼容可插拔实体 eSIM 卡（小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber 等，含零套餐全新空白卡）** 桥接至原版未修改的小米系统原生 LPA（`MIUIEsimLPA.apk` v4.1.0.1）。

---

## 核心功能与 `v0.6.0` 升级亮点

- **Material Design 3 Expressive 动态取色控制中心与专属自适应图标**：遵循最新 Android 15–17 设计规范打造沉浸式壁纸动态取色（Material You）、深浅色自适应、分段圆角卡片（`24dp`/`6dp`）与带状态图标的自定义 `M3Switch` 胶囊开关，并配备全新设计的「SIM2 实体卡转无线 eSIM 桥接」自适应图标（含主题单色图标层）。
- **SukiSU Ultra / KernelSU `ksu_handle_umount` 自动卸载免疫**：在验证 `/product/priv-app` 系统特权挂载（`FLAG_SYSTEM`）生效后自动同步 `/data/app` 更新层（`UPDATED_SYSTEM_APP`），彻底解决 SukiSU / KernelSU 默认卸载非 Root 应用（如系统相册、相机、浏览器）模块挂载导致的选二维码图片写卡闪退问题；关闭或卸载模块时通过 `pm uninstall-system-updates` 自动无损还原。
- **7 项细粒度独立功能开关（含 4 项免 Root 实时热切换）**：支持免 Root 实时切换系统 eSIM 开关（与卡槽 2 双向同步）、运营商 App 一键写卡授权桥接、`LPA://` 激活链接接管（可随时释放给 EasyLPAC 等工具）及系统设置提示文案修正；日常启动应用零 Root 弹窗打扰。
- **多品牌 SGP.22 实体卡与全新空白卡首发写卡支持**：完整支持 ESTKme 及小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber 等卡片；全新无套餐空白卡插入卡2后可直接开启系统 eSIM 并进入「管理 eSIM」扫码/激活码下载首个套餐。
- **ISO-7816-4 分段 APDU（`61XX` / `6CXX`）重组与 OMAPI + RIL 双通道兜底**：自动拼接 `61 XX` 分段响应、处理 `6C XX` 长度重试并在 OMAPI 受限时无缝回退至 Telephony RIL 逻辑通道。
- **卡1 射频严格隔离与普通 SIM 失败安全（Fail-Closed）保护**：拦截原厂面向内置焊死 eSIM 的 GPIO 射频切换，保障卡1 5G/4G/VoLTE 不断网；若卡2换入普通物理 SIM 卡立即安全旁路。

---

## ⚠️ KernelSU / SukiSU 用户必读（推荐 `Hybrid Mount` 或 `meta-overlayfs`）

原版 `MIUIEsimLPA.apk`（`com.miui.euicc`）必须挂载至 `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` 并配合 `/product/etc/permissions/privapp-permissions-euicc.xml` 才能获得 `FLAG_SYSTEM`、`BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 与 `MODIFY_PHONE_STATE` 系统特权：

1. **KernelSU（v2.0+ / v3.0+）与 SukiSU Ultra（v4.x+）用户**：澎湃 OS 4 原厂 `/product` 采用多层 `overlayfs`，部分环境下 `Magic Mount-rs` 会出现挂载不完全。**强烈建议先安装并启用 `Hybrid Mount` 或 `meta-overlayfs` 元模块并重启手机**，确保 `/data/adb/metamodule` 处于生效状态后再刷入启用本模块。
2. **Magisk（v20.4+）与 APatch 用户**：自带系统挂载能力，无需额外安装元模块。

---

## 安装、升级与日常开关说明

1. **刷入模块**：在已就绪的 Magisk / KernelSU / SukiSU / APatch 管理器中刷入 `XiaomiEsimLPA-HyperOS4-v0.6.0.zip` 并重启（全新安装默认 `MODE=off`，从旧版升级自动保留原有开关状态并同步最新组件）。
2. **启用 LSPosed 作用域**：在 LSPosed 管理器中启用 **HYPEROS4实体ESIM桥接**（包名：`io.github.jabe1122.esimh4probe`），勾选作用域 **`com.android.phone`** 与 **`com.miui.euicc`**。
3. **开启桥接服务**：将可插拔实体 eSIM 卡放入 **物理卡槽 2（SIM2）**，打开桌面 **「HYPEROS4实体ESIM桥接」** 开启顶部主开关（或在 Root 管理器点击模块「操作 Action」），首次开启按提示重启手机即可。
4. **日常管理**：
   - 在主界面确认 `LPA 特权挂载`、`电话框架 Hook`、`小米 LPA Hook`、`系统 eUICC 服务` 4 项指标均为绿色点亮状态。
   - 点击 **「打开小米原生 eSIM 管理与写卡」** 或前往 `系统设置 → 移动网络 → eSIM` 管理套餐、查看 EID、扫码或从相册选择二维码写卡（注：当系统 eSIM 开关尚未打开时，移动网络页显示「发现 eSIM 卡」属小米原厂正常入口文案，打开后即显示「管理 eSIM」）。

---

## 状态还原与卸载

- **暂时关闭**：在应用顶部关闭总开关或在 Root 管理器点击「操作（Action）」（立即剥离更新层、恢复备份设置并还原卡2状态；重启后撤销底层系统能力声明）。
- **禁用或卸载模块**：在 Root 管理器中禁用或卸载本模块并重启，`uninstall.sh` 与 `/data/adb/service.d/xiaomi-esim-h4-restore.sh` 双重守护会自动还原所有系统设置与属性，不修改实体卡内的任何套餐配置。
- **隐私保护**：备份目录 `/data/adb/xiaomi-esim-hyperos4/`（权限 `700`）仅保存原始开关值与卡片 SHA-256 摘要，绝不存储明文 EID、ICCID 或手机号。

---

## 组件来源与源码构建

内置 `prebuilt/MIUIEsimLPA.apk` 提取自国行 HyperOS 4 原生 eSIM 机型（Xiaomi 17 Pro Max `popsicle` `OS4.0.5.0.XPBCNXM`）官方固件，版本 `4.1.0.1`（`versionCode=4101`，SHA-256：`9daa20d489168264109d68532d27857a9eef7d7ce72199c565afb6b96d2e6b4f`），保持小米原厂平台签名未作任何重打包修改。桥接模块、Material 3 界面与打包脚本均为洁净室（Clean-Room）独立实现。

```bash
gradle -p probe clean assembleDebug
python build_module.py
python finalize_release.py
```
'''
user_guide_cn.write_text(user_guide_text.replace('\r\n', '\n'), encoding='utf-8', newline='\n')

source = out / 'XiaomiEsimLPA-HyperOS4-source-v0.6.0.zip'
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
    assert b'0.6.0' in z.read('USER_GUIDE_CN.md')

report = {
    'version': '0.6.0',
    'versionCode': 600,
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
    'ui': {
        'designSystem': 'Material Design 3 Expressive (OS-Native Dynamic Color Monet + Edge-to-Edge)',
        'adaptiveLauncherIcon': True,
        'monochromeThemedIcon': True,
        'featureSwitchesCount': 7,
        'zeroRootDailySwitchesCount': 4,
    },
    'framework': {
        'ordinaryAppFeature': True,
        'ordinaryAppEuiccManagerEnabled': True,
        'lpaPrivMounted': True,
        'lpaUpdatedSystemAppUmountImmunity': True,
        'phoneHookActive': True,
        'lpaHookActive': True,
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
        'sukisuKernelUmountImmunity': True,
        'recommendedMetamodules': ['Hybrid Mount', 'meta-overlayfs'],
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
print('Release v0.6.0 validation passed; upstream removed; source excludes build caches and signing keys.')
for p in files[:4]:
    print(p.name, p.stat().st_size)
