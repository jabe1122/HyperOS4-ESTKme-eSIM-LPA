# 小米 HyperOS 4 可插拔实体 eSIM（ESTKme / 小白卡 / 9eSIM 等）系统级适配与桥接方案
# HyperOS 4 Removable Physical eSIM (ESTKme / EasyEUICC-Compatible) Adaptation (`v0.6.1`)

[![Version](https://img.shields.io/badge/Version-v0.6.1-brightgreen.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-HyperOS%204%20%7C%20Android%2015--17%20(SDK%2035%2B)-green.svg)]()
[![Hardware](https://img.shields.io/badge/SIM2-ESTKme%20%7C%20Xiaobai%20%7C%209eSIM%20%7C%20Xesim-orange.svg)]()
[![Root](https://img.shields.io/badge/Framework-KernelSU%20%7C%20SukiSU%20%7C%20Magisk%20%7C%20APatch%20%2B%20LSPosed-purple.svg)]()

---

## 目录 / Table of Contents

- [中文说明文档](#中文说明文档)
  - [项目概览与 v0.6.1 更新亮点](#项目概览与-v061-更新亮点)
  - [⚠️ KernelSU / SukiSU 用户必读：元模块选型与 umount 免疫机制](#️-kernelsu--sukisu-用户必读元模块选型与-umount-免疫机制)
  - [Material 3 控制中心与 7 项功能开关说明](#material-3-控制中心与-7-项功能开关说明)
  - [实体 eSIM 卡适配核心原理](#实体-esim-卡适配核心原理)
  - [安全架构与测试红线约束](#安全架构与测试红线约束)
  - [支持设备、系统与实体卡列表](#支持设备系统与实体卡列表)
  - [仓库与模块架构](#仓库与模块架构)
  - [安装与部署指南](#安装与部署指南)
  - [模拟测试框架与非破坏性验证](#模拟测试框架与非破坏性验证)
  - [源码构建指南](#源码构建指南)
  - [安全须知与免责声明](#安全须知与免责声明)
  - [开源协议与参与贡献](#开源协议与参与贡献)
- [English Documentation](#english-documentation)
  - [Overview & What's New in v0.6.1](#overview--whats-new-in-v061)
  - [⚠️ KernelSU / SukiSU Metamodule Selection & umount Immunity](#️-kernelsu--sukisu-metamodule-selection--umount-immunity)
  - [Material Design 3 Control Panel & Feature Switches](#material-design-3-control-panel--feature-switches)
  - [Core Adaptation Principles](#core-adaptation-principles)
  - [Security Architecture & Safety Red Lines](#security-architecture--safety-red-lines)
  - [Supported Devices, OS & Removable eSIM Cards](#supported-devices-os--removable-esim-cards)
  - [Installation & Deployment Guide](#installation--deployment-guide)
  - [Building from Source](#building-from-source)
  - [License](#license)

---

# 中文说明文档

## 项目概览与 `v0.6.1` 更新亮点

**小米 HyperOS 4 可插拔实体 eSIM 适配与桥接方案（`v0.6.1`）** 是一套面向 Xiaomi / REDMI 澎湃 HyperOS 4（Android 15 / 16 / 17，API 35+）的开源系统级兼容层与桥接模块，专门解决各类符合 **GSMA SGP.22 标准的可插拔实体 eUICC 卡**（支持 EasyEUICC 的 **ESTKme、小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber** 等，含零套餐全新空白卡）在国行双实体卡槽小米/红米机型上无法直接使用小米官方系统原生 LPA 写卡与管理套餐的难题。

### `v0.6.1` / `v0.6.0` 核心升级内容
1. **免 Root 一键直达小米原生 eSIM 管理页修复（`v0.6.1`）**：
   - 修复普通应用 UID 调用 `ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS` 被 Android 17 `EuiccUiDispatcherActivity` 拦截 `MODIFY_PHONE_STATE` 权限而误报挂载未生效的问题，改为前台直接拉起小米原生 `ProfileListActivity` 并辅以 `com.android.phone` 特权代理兜底与系统 eSIM 未开启时的一键唤醒确认弹窗。
2. **全新 Material Design 3 Expressive 动态取色界面与专属自适应图标**：
   - 采用最新 Android 15–17 **Material You (`Monet`) 壁纸动态取色**、Edge-to-Edge 沉浸式系统栏、深浅色模式自适应、分段圆角设置卡片（`24dp`/`6dp`）以及带状态指示图标（`✓`/`✕`）的自定义高帧率胶囊开关。
   - 结合软件功能全新设计 **「物理卡槽 SIM2 → 无线 eSIM 桥接」自适应应用图标**，完整支持 Android 13–17 桌面主题单色图标（`monochrome`）。
3. **SukiSU Ultra / KernelSU `ksu_handle_umount` 内核卸载自动免疫（双层驻留架构）**：
   - 彻底解决 SukiSU Ultra / KernelSU 默认对普通应用（如系统相册、相机、浏览器）执行内核挂载卸载（`ksu_handle_umount: unmounting: /product/priv-app`）导致**从相册选择二维码写卡或外部拉起写卡时闪退**的问题。
   - 模块在严格确认 `/product/priv-app/MIUIEsimLPA` 系统特权挂载（`FLAG_SYSTEM`）已生效后，自动同步 `/data/app` 系统更新层（`SYSTEM | UPDATED_SYSTEM_APP`）：既 100% 保留特权权限，又让 APK 实体文件常驻 `/data/app`，无需手动为相册等应用逐一关闭「卸载模块」即可丝滑扫码选图写卡；关闭模块时通过 `pm uninstall-system-updates` 自动无损剥离。
4. **7 项模块化独立功能开关（含 4 项免 Root 实时热切换）**：
   - 日常切换系统 eSIM、开关第三方运营商一键写卡桥接、释放或接管 `LPA://` 激活码链接（方便与 EasyLPAC 等工具共存）、开关系统设置提示文案修正均通过电话框架安全 IPC 实时生效，**日常打开应用与切换核心功能零 Root 弹窗打扰**。
5. **Android 17 预编译框架资源文案修正增强与元模块选型指引**：
   - 同时覆盖 `Resources.getString` 与 `Resources.getText` 钩子，适配 Android 17 `boot-framework.oat` 内联调用；新增针对澎湃 OS 4 原厂多层 `overlayfs` 分区的 **`Hybrid Mount` / `meta-overlayfs`** 元模块选型检测与引导。

---

## ⚠️ KernelSU / SukiSU 用户必读：元模块选型与 `umount` 免疫机制

> **特权挂载要求**：原版未修改的 `MIUIEsimLPA.apk`（`com.miui.euicc`）必须先通过 `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` 及 `/product/etc/permissions/privapp-permissions-euicc.xml` 完成系统特权挂载，才能获得 `FLAG_SYSTEM`、`BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 与 `MODIFY_PHONE_STATE` 权限。

1. **元模块选型建议（强烈推荐 `Hybrid Mount` 或 `meta-overlayfs`）**：
   - 小米澎湃 HyperOS 4 的 `/product` 等系统分区原厂即采用多层 `overlayfs` 结构。实测表明部分环境下 **`Magic Mount-rs` 会出现挂载不完全**（表现为移动网络出现「发现 eSIM 卡」但点击无响应、`lpaPrivMounted=false`）。
   - **KernelSU / SukiSU Ultra 用户请优先安装并启用 `Hybrid Mount` 或 `meta-overlayfs` 元模块**，重启确保 `/data/adb/metamodule` 生效后再启用本模块。
2. **内核 `umount` 卸载免疫说明**：
   - 当 `/product/priv-app` 特权挂载生效后，`v0.6.1` 默认自动开启 **「SukiSU / KernelSU 挂载卸载免疫」**（双层驻留），防止相册选二维码时因内核卸载 `/product/priv-app` 引发闪退。
3. **Magisk（v20.4+）与 APatch 用户**：自带系统挂载能力，直接刷入即可。

---

## Material 3 控制中心与 7 项功能开关说明

打开桌面 **「HYPEROS4实体ESIM桥接」** 即可查看实时硬件健康网格并控制以下功能：

| 分组 | 开关名称 | 是否需要 Root | 功能说明 |
|---|---|---|---|
| **主服务横幅** | **实体 eSIM 桥接总开关** | 需 Root 授权 | 一键启停底层模块模式（与 Root 管理器 `Action` 按钮等效同步）。 |
| **核心功能开关** | **开启系统 eSIM（同步卡槽 2）** | 免 Root 实时生效 | 与系统「移动网络 → 开启 eSIM」双向实时同步，直接控制物理卡槽 2 供电与射频唤醒。 |
| **核心功能开关** | **运营商 App 一键写卡授权桥接** | 免 Root 实时生效 | 允许无系统特权签名的漫游/运营商 App 调用 `EuiccManager` 拉起小米原生写卡确认弹窗。 |
| **核心功能开关** | **接管 LPA:// 激活链接与扫码动作** | 免 Root 实时生效 | 开启时点击 `LPA:1$...` 链接自动拉起小米写卡页；关闭后释放拦截，可交由 EasyLPAC / NekokoLPA 处理。 |
| **核心功能开关** | **系统设置 eSIM 提示文案修正** | 免 Root 实时生效 | 将澎湃 OS 原厂“打开后 eSIM 将替换 SIM 卡 2”修正为符合实体 eSIM 卡逻辑的同步说明（注：未开启 eSIM 时系统显示“发现 eSIM 卡”为小米原厂正常设计，开启后即变为“管理 eSIM”）。 |
| **内核与组件** | **SukiSU / KernelSU 挂载卸载免疫** | 需 Root 授权 | 在特权挂载之上同步 `/data/app` 更新层，彻底免疫 `ksu_handle_umount` 导致的选图写卡闪退。 |
| **内核与组件** | **桌面保留「模拟写卡测试」工具** | 需 Root 授权 | 按需在桌面保留或隐藏独立的「eSIM 模拟写卡测试」验证工具图标，保持桌面整洁。 |

---

## 实体 eSIM 卡适配核心原理

### 1. OMAPI `SEService` + Telephony RIL 双通道 SIM2 桥接
- 优先连接 Android Open Mobile API (`android.se.omapi.SEService`) **`SIM2`** 读卡器，分配从 **`12000+`** 起步的独立虚拟逻辑通道池，严格同步生命周期、杜绝句柄泄漏；
- 完整支持 ISO-7816-4 `61 XX`（`00 C0 00 00 XX` `GET RESPONSE` 分段拼接）、`6C XX`（期望长度 `Le` 自动重试）及 Case-4 → Case-3 RIL 兼容重试；
- 当卡片缺少 ARA-M 规则导致 OMAPI 受限时，自动无缝回退至 `TelephonyManager.iccOpenLogicalChannelBySlot(1, ISDR_AID, 0)` RIL 逻辑通道完成探测与 EID 解析。

### 2. SGP.22 ISD-R AID 探测与全新空白卡生命周期支持
- 针对 GSMA SGP.22 根安全域 AID `A0000005591010FFFFFFFF8900000100`，支持 `BF3E` 与 `BF2E` 响应提取 32 位数字 EID（Tag `0x5A`）；
- 全新零套餐空白卡（`SIM_STATE_NOT_READY`、无活跃 ICCID、`SubscriptionInfo` 为空）通过真卡校验后，系统 eSIM 开关与「管理 eSIM」入口始终可用，可直接扫码写入首个套餐。

### 3. Fail-Closed 普通物理 SIM 隔离与 SIM1 主卡不断流保护
- 普通物理 SIM 卡返回 `NO_SUCH_ELEMENT` 时立即判定非 eUICC（`cardType=0`），绝不干扰或停用普通手机卡；
- 拦截原厂板载 eSIM 的底层 GPIO 切换（`setSimType`、`switchSlot`），确保 **SIM1 主卡 5G/4G/VoLTE 全程不断网**；
- `Settings.Secure` 仅存 SHA-256 哈希摘要，绝不落盘明文 EID 或 ICCID。

---

## 安全架构与测试红线约束

| 红线规则 | 实现约束 |
|---|---|
| **严禁测试态写卡/重置** | 针对 `test.esim.local` 模拟测试流程，在授权确认后底层直接安全拦截，绝不向物理实体卡发送任何写卡或重置 APDU。 |
| **杜绝无特权 `/data/app` 污染** | 仅在验证 `/product/priv-app` `FLAG_SYSTEM` 特权挂载已生效的前提下才允许同步 `UPDATED_SYSTEM_APP` 更新层；若特权挂载未生效则自动剥离异常安装。 |
| **状态完整可逆还原** | 模块关闭、禁用或卸载时，自动执行 `pm uninstall-system-updates` 并还原全部备份设置与系统属性，卡内真实套餐 100% 原样保留。 |

---

## 支持设备、系统与实体卡列表

- **已验证机型**：
  - **Xiaomi MIX Flip 2**（`bixi`，HyperOS 4 `OS4.0.0.11.XOHCNXM` / Android 17 API 37）
  - **REDMI K90 Pro Max**（`myron`，HyperOS 4 `OS4.0.6.0.XPMCNXM` / Android 17 API 37）
  - **通用支持**：无硬编码机型限制，适配所有运行 **Xiaomi HyperOS 4（Android 15 / 16 / 17，SDK 35+）** 的双物理卡槽小米 / REDMI 机型。
- **支持的可插拔实体 eSIM 卡（放入物理 SIM 卡槽 2）**：
  - **ESTKme** 全系列可插拔实体 eUICC
  - **小白卡**、**携程 / JoyTel 实体白卡**、**9eSIM**、**Xesim**、**5ber** 及其他符合 **GSMA SGP.22 标准（支持 EasyEUICC）** 的可插拔实体 eSIM 卡（含已写卡与**全新空白卡**）。

---

## 仓库与模块架构

```
work/esim-adapt/
├── LICENSE                         # Apache 2.0 开源协议
├── README.md                       # 中英双语完整技术文档（中文置顶）
├── build_module.py                 # KernelSU / SukiSU / Magisk 模块自动化打包脚本 (v0.6.1)
├── finalize_release.py             # 发布产物完整性校验与 SHA256 生成脚本
├── prebuilt/
│   └── MIUIEsimLPA.apk             # 提取自官方 HyperOS 4 固件的未修改原版小米 LPA (v4.1.0.1)
├── probe/                          # Android Gradle 工程（零外部臃肿 AAR 依赖）
│   ├── app/                        # LSPosed 核心桥接与 Material 3 控制中心 (io.github.jabe1122.esimh4probe)
│   │   └── src/main/java/io/github/local/esimh4probe/
│   │       ├── CompatModule.java       # LibXposed 钩子、APDU 双通道桥接与安全控制 IPC
│   │       ├── CardTypeGuard.java      # Fail-closed ISD-R 探测与功能开关状态缓存
│   │       ├── EuiccCardReader.java    # 双通道探测与 EID 摘要工具
│   │       ├── ProbeInspector.java     # 纯净 SDK 脱敏诊断引擎与 CLI 入口
│   │       ├── M3Ui.java               # 原生 Material Design 3 动态取色与自定义胶囊开关组件库
│   │       ├── MainActivity.java       # Material 3 控制中心面板
│   │       ├── LpaBridgeActivity.java  # LPA:// 链接与扫码激活 Intent 桥接
│   │       ├── Sim2Subscription.java   # 卡2订阅解析与空白卡兼容
│   │       └── Sim2Control.java        # Root CLI 状态探测与同步工具
│   └── tester/                     # 独立 Material 3 模拟写卡测试工具 (io.github.local.esimh4tester)
└── outputs/                        # 构建发布产物目录
```

---

## 安装与部署指南

1. **（KernelSU / SukiSU 用户）启用挂载元模块**：请先安装并启用 **`Hybrid Mount`** 或 **`meta-overlayfs`** 元模块并重启手机（Magisk / APatch 用户跳过此步）。
2. **刷入模块 ZIP**：在 Root 管理器中刷入 `XiaomiEsimLPA-HyperOS4-v0.6.1.zip` 并重启（全新安装默认关闭，覆盖升级自动保留原状态）。
3. **配置 LSPosed 作用域**：在 LSPosed 管理器中启用 **「HYPEROS4实体ESIM桥接」**（`io.github.jabe1122.esimh4probe`），勾选 **`com.android.phone`** 与 **`com.miui.euicc`**。
4. **开启桥接服务**：将实体 eSIM 卡插入 **卡槽 2**，打开桌面 **「HYPEROS4实体ESIM桥接」** 开启顶部总开关（或在 Root 管理器点击模块「操作 Action」），按提示重启一次即可生效。
5. **日常写卡与管理**：在应用内直接点击 **「打开小米原生 eSIM 管理与写卡」**，或前往 `系统设置 → 移动网络 → eSIM` 管理套餐、查看 EID、扫码或从相册选二维码写卡。

---

## 模拟测试框架与非破坏性验证

模块提供独立的 **「eSIM 模拟写卡测试」** 应用（可在主界面随时隐藏或恢复安装）：
- 实时展示 **LPA 特权挂载**、`EUICC` 特性声明、`EuiccManager.isEnabled()`、`defaultCardId` 与 `LPA://` 接管状态；
- 默认使用安全模拟码 `LPA:1$test.esim.local$MOCK-TEST-001` 验证小米原生授权弹窗 `DownloadConfirmDialogActivity`、深色控制台回调与 `LPA://` 跳转，底层自动拦截测试码，**零写卡风险**。

---

## 源码构建指南

```bash
cd work/esim-adapt
gradle -p probe clean assembleDebug
python build_module.py
python finalize_release.py
```

---

## 安全须知与免责声明

1. **Root 风险声明**：本模块需要 Root 权限与 LSPosed 框架支持，刷机前请做好重要数据备份。
2. **商标与独立性声明**：Xiaomi、REDMI、HyperOS、MIUI、ESTKme、9eSIM、Xesim、GSMA 等商标归各自权利人所有。本项目为独立洁净室（Clean-Room）开源实现，与上述厂商无隶属关系。
3. **免责条款**：本项目按 Apache License 2.0 协议「原样」提供，不对因不当操作造成的任何损失承担责任。

---

## 开源协议与参与贡献

本项目基于 **Apache License 2.0** 开源，详见 [LICENSE](LICENSE)。欢迎提交 Issue 与 Pull Request 交流反馈！

---
---

# English Documentation

## Overview & What's New in `v0.6.1`

**HyperOS 4 Removable Physical eSIM Adaptation (`v0.6.1`)** is a clean-room system compatibility and bridging module for Xiaomi and REDMI smartphones running **Xiaomi HyperOS 4 (Android 15 / 16 / 17, SDK 35+)**. It bridges **GSMA SGP.22 removable eUICC cards** in **physical SIM Slot 2** (**ESTKme, Xiaobai, Ctrip/JoyTel, 9eSIM, Xesim, 5ber**, including brand-new zero-profile cards) directly to Xiaomi's unmodified stock LPA (`MIUIEsimLPA.apk` v4.1.0.1).

### Highlights in `v0.6.1` / `v0.6.0`
1. **Direct Zero-Root Launch of Xiaomi Native eSIM Manager (`v0.6.1`)**:
   - Fixes `SecurityException` (`MODIFY_PHONE_STATE` denial on `EuiccUiDispatcherActivity` for normal app UIDs) when tapping "Open Xiaomi Native eSIM Manager" in the control center by foreground-launching `ProfileListActivity` directly with a privileged `com.android.phone` dispatcher fallback and a one-tap SIM2 wake dialog when System eSIM is toggled off.
2. **Material Design 3 Expressive UI & Bespoke Adaptive Icon**:
   - Native Android 14–17 Material You (`Monet`) wallpaper dynamic colors, edge-to-edge system bar insets, dark/light themes, asymmetric `24dp`/`6dp` segmented settings cards, animated `M3Switch` pills with `✓`/`✕` thumb icons, and a custom SIM2-to-eSIM bridge adaptive icon (with `monochrome` themed icon layer).
3. **Automatic SukiSU / KernelSU `ksu_handle_umount` Immunity**:
   - Prevents crashes when picking QR code images from Gallery or launching LPA flows from non-root apps whose namespaces unmount `/product/priv-app`. Once privileged `/product/priv-app` mounting (`FLAG_SYSTEM`) is verified, `v0.6.1` automatically syncs a `/data/app` `UPDATED_SYSTEM_APP` layer so APK code paths stay accessible across `ksu_handle_umount`, and strips updates cleanly via `pm uninstall-system-updates` when turned off.
4. **7 Granular Feature Switches (4 Zero-Root Instant Toggles)**:
   - Toggle System eSIM (SIM2 power sync), Carrier App One-Click Auth Bridge, `LPA://` URI & Activation Interception, and Settings Text Rewrite instantaneously via caller-verified `ServiceStateProvider` IPC without triggering `su` root toasts on startup or daily use.
5. **Enhanced Android 17 `Resources.getString` Hook & Metamodule Guidance**:
   - Hooks both `Resources.getString` and `Resources.getText` for Android 17 `boot-framework.oat` compatibility, and recommends **`Hybrid Mount`** or **`meta-overlayfs`** over `Magic Mount-rs` on HyperOS 4 multi-layer `overlayfs` partitions.

---

## ⚠️ KernelSU / SukiSU Metamodule Selection & `umount` Immunity

- **Recommended Metamodules**: On Xiaomi HyperOS 4 (whose stock `/product` uses multi-layer `overlayfs`), **`Hybrid Mount`** and **`meta-overlayfs`** mount `/product/priv-app/MIUIEsimLPA` reliably, whereas `Magic Mount-rs` may leave priv-app overlays incomplete. Install and enable **`Hybrid Mount`** or **`meta-overlayfs`** and reboot before enabling this module.
- **Automatic `umount` Guard**: Enabled automatically once `lpaPrivMounted` is verified, eliminating Gallery QR-picker crashes on SukiSU Ultra / KernelSU without manually disabling "Umount modules" per app.
- **Magisk & APatch**: Supported out of the box without extra metamodules.

---

## Material Design 3 Control Panel & Feature Switches

- **Master Bridge Switch** (Root): Enables or disables the module (`action.sh on|off`).
- **System eSIM & SIM2 Sync** (Zero-Root): Bidirectional real-time toggle synced with Xiaomi Mobile Network eSIM settings.
- **Carrier App One-Click Auth Bridge** (Zero-Root): Allows third-party carrier/roaming apps to invoke Xiaomi's native `DownloadConfirmDialogActivity`.
- **`LPA://` URI & Activation Scanner Bridge** (Zero-Root): Intercepts `LPA:1$...` links for Xiaomi LPA, or releases them cleanly to third-party LPAs such as EasyLPAC when switched off.
- **Settings eSIM Prompt Rewrite** (Zero-Root): Updates stock "replaces SIM 2" wording to accurately describe SIM2 removable eSIM synchronization.
- **SukiSU / KernelSU `umount` Immunity** (Root): Controls dual-layer `/product/priv-app` + `/data/app` (`UPDATED_SYSTEM_APP`) residency.
- **Keep Mock Tester App on Launcher** (Root): Installs or hides the standalone zero-risk `eSIM 模拟写卡测试` app.

---

## Core Adaptation Principles

- **Dual-Channel SIM2 Bridge**: Primary OMAPI `SEService` (`SIM2`, virtual channels `12000+`) + automatic Telephony RIL (`iccOpenLogicalChannelBySlot`) fallback, with full ISO-7816-4 `61XX` (`GET RESPONSE`) & `6CXX` (`Le` retry) support.
- **Fail-Closed Safety & Empty-Card Support**: Ordinary SIM cards in Slot 2 immediately fail closed (`NO_SUCH_ELEMENT` -> `cardType=0`); genuine empty eUICC cards stay active for first-profile provisioning; SIM1 radio/GPIO paths remain strictly isolated.

---

## Security Architecture & Safety Red Lines

- **Zero Flash Writes in Mock Mode**: `test.esim.local` mock downloads are intercepted safely before any APDU write reaches the physical eUICC.
- **Strict Privilege Gate**: `UPDATED_SYSTEM_APP` sync only runs when `/product/priv-app` `FLAG_SYSTEM` privilege mounting is verified.
- **Privacy**: Only SHA-256 digests are persisted in `Settings.Secure`.

---

## Supported Devices, OS & Removable eSIM Cards

- **Verified Devices**: **Xiaomi MIX Flip 2** (`bixi`) and **REDMI K90 Pro Max** (`myron`) on HyperOS 4 (Android 17 SDK 37), plus generic support for dual-SIM Xiaomi/REDMI devices on HyperOS 4 (SDK 35+).
- **Supported Cards (Slot 2)**: **ESTKme**, **Xiaobai (小白卡)**, **Ctrip/JoyTel**, **9eSIM**, **Xesim**, **5ber**, and other standard GSMA SGP.22 removable eUICCs.

---

## Installation & Deployment Guide

1. *(KernelSU / SukiSU only)* Enable **`Hybrid Mount`** or **`meta-overlayfs`** and reboot.
2. Flash **`XiaomiEsimLPA-HyperOS4-v0.6.1.zip`** in your root manager and reboot.
3. Enable **HYPEROS4实体ESIM桥接** (`io.github.jabe1122.esimh4probe`) in **LSPosed** for `com.android.phone` and `com.miui.euicc`.
4. Insert your removable eUICC card into **SIM Slot 2**, toggle the master switch **ON** in the app (or tap **Action** in your root manager), and reboot once.

---

## Building from Source

```bash
cd work/esim-adapt
gradle -p probe clean assembleDebug
python build_module.py
python finalize_release.py
```

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
