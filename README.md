# 小米 HyperOS 4 可插拔实体 eSIM（ESTKme / 小白卡 / 9eSIM 等）适配与桥接
# HyperOS 4 Removable Physical eSIM (ESTKme / EasyEUICC-Compatible) Adaptation (`v0.6.1`)

[![Version](https://img.shields.io/badge/Version-v0.6.1-brightgreen.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-HyperOS%204%20%7C%20Android%2015--17%20(SDK%2035%2B)-green.svg)]()
[![Hardware](https://img.shields.io/badge/SIM2-ESTKme%20%7C%20Xiaobai%20%7C%209eSIM%20%7C%20Xesim-orange.svg)]()
[![Root](https://img.shields.io/badge/Framework-KernelSU%20%7C%20SukiSU%20%7C%20Magisk%20%7C%20APatch%20%2B%20LSPosed-purple.svg)]()

---

## 目录 / Table of Contents

- [中文说明文档](#中文说明文档)
  - [项目概览](#项目概览)
  - [元模块选型与 umount 免疫](#元模块选型与-umount-免疫)
  - [控制中心与功能开关](#控制中心与功能开关)
  - [界面与个性化设置](#界面与个性化设置)
  - [实体 eSIM 适配原理](#实体-esim-适配原理)
  - [安全约束](#安全约束)
  - [支持设备与实体卡](#支持设备与实体卡)
  - [仓库结构](#仓库结构)
  - [安装与部署](#安装与部署)
  - [模拟测试](#模拟测试)
  - [源码构建](#源码构建)
  - [免责声明](#免责声明)
  - [开源协议](#开源协议)
- [English Documentation](#english-documentation)
  - [Overview](#overview)
  - [Metamodule Selection and umount Immunity](#metamodule-selection-and-umount-immunity)
  - [Control Center and Feature Switches](#control-center-and-feature-switches)
  - [Appearance and Personalization Settings](#appearance-and-personalization-settings)
  - [Core Adaptation Principles](#core-adaptation-principles)
  - [Safety Constraints](#safety-constraints)
  - [Supported Devices and Cards](#supported-devices-and-cards)
  - [Repository Layout](#repository-layout)
  - [Installation](#installation)
  - [Mock Testing](#mock-testing)
  - [Building from Source](#building-from-source)
  - [Disclaimer](#disclaimer)
  - [License](#license)

---

# 中文说明文档

## 项目概览

本项目是面向 Xiaomi / REDMI HyperOS 4（Android 15 / 16 / 17，SDK 35+）的系统级兼容层与桥接模块，将物理 SIM 卡槽 2 中符合 **GSMA SGP.22** 标准的可插拔实体 eUICC 卡（支持 EasyEUICC 的 **ESTKme、小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber** 等，含无套餐空白卡）接入小米官方系统 LPA（`prebuilt/MIUIEsimLPA.apk`，未修改），使其可以扫码写卡与管理套餐。模块由 KernelSU / SukiSU / Magisk / APatch 模块与 LSPosed 模块两部分组成，控制应用提供 Material 3 与 Miuix 两种界面风格。

### `v0.6.1` / `v0.6.0` 变更摘要

1. 修复普通应用 UID 调用 `ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS` 被 Android 17 `EuiccUiDispatcherActivity` 拦截的问题（`v0.6.1`）：改为前台直接拉起 `ProfileListActivity`，以 `com.android.phone` 特权代理兜底。
2. 新增 Material 3 与 Miuix 双界面、主题与语言设置、设备与 eUICC 状态页、开源许可页与模拟测试页。
3. SukiSU / KernelSU `ksu_handle_umount` 免疫：确认 `/product/priv-app` 特权挂载后同步 `/data/app` 更新层，避免从相册选图写卡时闪退。
4. 功能开关共 7 项，其中 5 项免 Root；核心开关通过电话框架 IPC 实时生效，日常使用不触发 Root 提示。
5. 同时钩住 `Resources.getString` 与 `Resources.getText`，适配 Android 17 `boot-framework.oat`；增加元模块选型检测与提示。

---

## 元模块选型与 umount 免疫

> 特权挂载要求：未修改的 `MIUIEsimLPA.apk`（`com.miui.euicc`）需通过 `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` 与 `/product/etc/permissions/privapp-permissions-euicc.xml` 完成系统特权挂载，以获得 `FLAG_SYSTEM`、`BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 与 `MODIFY_PHONE_STATE` 权限。

1. **元模块选型**：HyperOS 4 的 `/product` 等分区为多层 `overlayfs` 结构，`Magic Mount-rs` 在部分环境下挂载不完整（表现为移动网络出现「发现 eSIM 卡」但点击无响应、`lpaPrivMounted=false`）。KernelSU / SukiSU 用户建议先启用 `Hybrid Mount` 或 `meta-overlayfs` 元模块并重启，再启用本模块。
2. **umount 免疫**：特权挂载生效后默认开启「挂载卸载免疫」（`/data/app` 更新层同步），防止其他应用命名空间卸载 `/product/priv-app` 时导致选图写卡闪退。
3. **Magisk（v20.4+）与 APatch**：自带系统挂载能力，直接刷入即可。

---

## 控制中心与功能开关

打开桌面 **「HYPEROS4实体ESIM桥接」** 查看状态并控制以下开关：

| 分组 | 开关 | Root | 说明 |
|---|---|---|---|
| 桥接 | 实体 eSIM 桥接总开关 | 需要 | 启停模块模式，与 Root 管理器的 `Action` 按钮同步。 |
| 功能 | 系统 eSIM（同步卡槽 2） | 不需要 | 与「移动网络 → 开启 eSIM」双向同步，控制卡槽 2 供电。 |
| 功能 | 运营商应用写卡 | 不需要 | 允许无系统特权签名的运营商 / 漫游应用调用 `EuiccManager` 打开写卡确认弹窗。 |
| 功能 | 打开 LPA:// 链接 | 不需要 | 开启时 `LPA:1$...` 激活链接由小米写卡页打开；关闭后交由 EasyLPAC 等应用处理。 |
| 功能 | 修正设置中的 eSIM 文案 | 不需要 | 将「替换 SIM 卡 2」提示改为与实体 eSIM 同步一致的说明。 |
| 系统 | umount 后保留 LPA | 需要 | 确认特权挂载后同步 `/data/app` 更新层，避免卸载挂载后写卡闪退；关闭时执行 `pm uninstall-system-updates` 移除副本。 |
| 系统 | eSTK.me 使用 SE1 | 需要 | 为 eSTK.me 卡片选择 SE1；关闭时使用 SE0（标准 ISD-R）。切换后建议重启再管理套餐。 |
| 个性化 | 显示测试标签页 | 不需要 | 在底部导航栏显示或隐藏「测试」页。 |

设置页另提供「设备与 eUICC 状态」入口（设备信息、LPA 挂载、`EUICC` 特性声明、`defaultCardId`、`LPA://` 接管状态，下拉刷新）。未开启 eSIM 时系统显示「发现 eSIM 卡」为原厂设计，开启后变为「管理 eSIM」。

---

## 界面与个性化设置

应用提供 Material 3 与 Miuix 两种界面风格，可在「设置 → 主题设置」切换，两种风格共用同一套设置与页面状态。

**外观**

- 界面风格：Material 3 / Miuix。
- 深色模式：跟随系统 / 浅色 / 深色。
- 动态取色：使用壁纸颜色（Material 3 为 Monet 取色；Miuix 下替代默认的 HyperOS 蓝）。

**顶栏与底栏（仅 Miuix 风格）**

- 毛玻璃效果：模糊顶栏和底栏后方的页面内容。
- 渐进式模糊：模糊强度从屏幕边缘向内容方向减弱（需先开启毛玻璃效果）。
- 悬浮底栏：底栏显示为悬浮胶囊。
- 液态玻璃：悬浮底栏使用轻度模糊、边缘折射与高光效果（需先开启悬浮底栏）。

**应用背景**

- 背景图片：显示在所有页面后方，可更换或移除；导入时按最长边 2160 px 缩放。
- 背景模糊、背景暗化、卡片不透明度：设置背景图片后可调。

**其他**

- 语言：跟随系统 / English / 简体中文 / 繁體中文，按应用生效（Android 13+ 应用语言）。
- 显示测试标签页：在底部导航栏显示或隐藏「测试」页。
- 隐藏入口：多次点击首页状态区域可打开诊断报告；在「关于」页多次点击标题区域可导出模块日志。

---

## 实体 eSIM 适配原理

### 1. OMAPI `SEService` + Telephony RIL 双通道 SIM2 桥接
- 优先连接 OMAPI（`android.se.omapi.SEService`）`SIM2` 读卡器，使用从 `12000+` 起步的独立虚拟逻辑通道池，同步生命周期、避免句柄泄漏；
- 支持 ISO-7816-4 `61 XX`（`GET RESPONSE` 分段拼接）、`6C XX`（`Le` 自动重试）及 Case-4 → Case-3 RIL 兼容重试；
- 卡片缺少 ARA-M 规则导致 OMAPI 受限时，回退至 `TelephonyManager.iccOpenLogicalChannelBySlot(1, ISDR_AID, 0)` 完成探测与 EID 解析。

### 2. SGP.22 ISD-R AID 探测与空白卡支持
- 针对 ISD-R AID `A0000005591010FFFFFFFF8900000100`，支持 `BF3E` 与 `BF2E` 响应提取 32 位数字 EID（Tag `0x5A`）；
- 无套餐空白卡（`SIM_STATE_NOT_READY`、无活跃 ICCID、`SubscriptionInfo` 为空）通过校验后，系统 eSIM 开关与「管理 eSIM」入口保持可用，可直接写入首个套餐。

### 3. Fail-Closed 物理 SIM 隔离与 SIM1 保活
- 普通物理 SIM 卡返回 `NO_SUCH_ELEMENT` 时判定非 eUICC（`cardType=0`），不干扰普通手机卡；
- 拦截原厂板载 eSIM 的 GPIO 切换（`setSimType`、`switchSlot`），SIM1 主卡数据与通话不中断；
- `Settings.Secure` 仅存 SHA-256 哈希摘要，不落盘明文 EID 或 ICCID。

---

## 安全约束

| 规则 | 实现 |
|---|---|
| 测试流程不写卡 | `test.esim.local` 模拟流程在授权确认前被拦截，不向物理卡发送写卡或重置 APDU。 |
| `/data/app` 更新层有前置条件 | 仅在验证 `/product/priv-app` `FLAG_SYSTEM` 特权挂载已生效后才同步 `UPDATED_SYSTEM_APP`；否则自动剥离异常安装。 |
| 状态可逆 | 模块关闭、禁用或卸载时执行 `pm uninstall-system-updates` 并还原备份的设置与系统属性，卡内套餐不受影响。 |

---

## 支持设备与实体卡

- **已验证机型**：
  - **Xiaomi MIX Flip 2**（`bixi`，HyperOS 4 `OS4.0.0.11.XOHCNXM` / Android 17 API 37）
  - **REDMI K90 Pro Max**（`myron`，HyperOS 4 `OS4.0.6.0.XPMCNXM` / Android 17 API 37）
  - 无硬编码机型限制，适用于运行 HyperOS 4（Android 15 / 16 / 17，SDK 35+）的双物理卡槽 Xiaomi / REDMI 机型。
- **支持的可插拔实体 eSIM 卡（放入物理 SIM 卡槽 2）**：
  - **ESTKme** 全系列可插拔实体 eUICC；
  - **小白卡**、**携程 / JoyTel 实体白卡**、**9eSIM**、**Xesim**、**5ber** 及其他符合 **GSMA SGP.22（支持 EasyEUICC）** 的可插拔实体 eSIM 卡（含已写卡与空白卡）。

---

## 仓库结构

```
.
├── LICENSE                         # Apache License 2.0
├── README.md                       # 中英双语文档
├── build_module.py                 # KernelSU / SukiSU / Magisk 模块打包脚本
├── finalize_release.py             # 发布产物校验与 SHA-256 生成
├── prebuilt/
│   └── MIUIEsimLPA.apk             # 未修改的小米 LPA（提取自官方 HyperOS 4 固件）
└── probe/                          # Android Gradle 工程
    ├── app/                        # LSPosed 模块与控制应用 (io.github.jabe1122.esimh4probe)
    │   └── src/main/java/io/github/local/esimh4probe/
    │       ├── CompatModule.java       # LibXposed 钩子、APDU 双通道桥接、控制 IPC
    │       ├── CardTypeGuard.java      # Fail-closed ISD-R 探测与开关状态缓存
    │       ├── MainActivity.java       # 控制中心（Material 3 页面）
    │       ├── MainScreen.kt           # 控制中心（Miuix 页面：首页 / 测试 / 设置）
    │       ├── DashboardModel.java     # 两种风格共用的页面状态
    │       ├── TesterPage.java         # 测试页：模拟运营商写卡验证
    │       ├── DeviceStatus*.*         # 设备与 eUICC 状态页
    │       ├── About*.* / Licenses*.*  # 关于与开源许可页
    │       └── …                       # LPA:// 桥接、卡 2 订阅解析、日志导出等
    └── ui/                         # 共享 UI 模块 (io.github.local.esimh4ui)
        ├── ThemeScreen.kt / ThemeConfig.java   # 主题设置页与配置
        ├── MiuixKit.kt / UiKit.java            # Miuix 与 Material 组件
        └── LanguageSetting.java                # 应用语言设置
```

---

## 安装与部署

1. **（KernelSU / SukiSU）启用挂载元模块**：安装并启用 `Hybrid Mount` 或 `meta-overlayfs` 并重启（Magisk / APatch 跳过此步）。
2. **刷入模块 ZIP**：在 Root 管理器中刷入 `XiaomiEsimLPA-HyperOS4-v0.6.1.zip` 并重启（全新安装默认关闭，覆盖升级保留原状态）。
3. **配置 LSPosed 作用域**：启用 **「HYPEROS4实体ESIM桥接」**（`io.github.jabe1122.esimh4probe`），勾选 `com.android.phone` 与 `com.miui.euicc`。
4. **开启桥接服务**：将实体 eSIM 卡插入卡槽 2，打开应用开启顶部总开关（或在 Root 管理器点击模块 `Action`），按提示重启一次。
5. **日常写卡与管理**：在应用内点击「管理 eSIM」，或前往 `系统设置 → 移动网络 → eSIM` 管理套餐、查看 EID、扫码或从相册选二维码写卡。

---

## 模拟测试

底部导航栏的「测试」页（可在设置中显示或隐藏）用于非破坏性验证：

- 顶部横幅显示测试条件（LPA 特权挂载、`EuiccManager.isEnabled()`、卡槽 2 实体 eSIM），下拉刷新；
- 默认使用模拟码 `LPA:1$test.esim.local$MOCK-TEST-001` 验证写卡授权弹窗 `DownloadConfirmDialogActivity`、控制台回调与 `LPA://` 跳转；
- 含 `test.esim.local` 的激活码会被底层拦截，不写入卡片。

---

## 源码构建

需要 Gradle 9（CI 使用 9.9.2）与 JDK 17+。默认构建经 R8 压缩的 release 包，签名信息配置在 `probe/local.properties` 的 `signing.*`（或 `SIGNING_*` 环境变量）：

```bash
gradle -p probe clean assembleRelease
python build_module.py
python finalize_release.py
```

调试时改用 debug 包：

```bash
gradle -p probe clean assembleDebug
python build_module.py --debug
python finalize_release.py --debug
```

---

## 免责声明

1. 本模块需要 Root 权限与 LSPosed 框架，操作前请备份重要数据。
2. Xiaomi、REDMI、HyperOS、MIUI、ESTKme、9eSIM、Xesim、GSMA 等商标归各自权利人所有。本项目为独立实现，与上述厂商无隶属关系。
3. 本项目按 Apache License 2.0「原样」提供，不对使用中产生的损失承担责任。

---

## 开源协议

本项目基于 **Apache License 2.0** 开源，详见 [LICENSE](LICENSE)。欢迎提交 Issue 与 Pull Request。

---
---

# English Documentation

## Overview

This project is a system-level compatibility and bridging module for Xiaomi and REDMI devices running **Xiaomi HyperOS 4 (Android 15 / 16 / 17, SDK 35+)**. It connects **GSMA SGP.22 removable eUICC cards** in **physical SIM Slot 2** (**ESTKme, Xiaobai, Ctrip/JoyTel, 9eSIM, Xesim, 5ber**, including zero-profile cards) to Xiaomi's unmodified stock LPA (`prebuilt/MIUIEsimLPA.apk`) for profile download and management. It ships as a KernelSU / SukiSU / Magisk / APatch module plus an LSPosed module; the control app offers both Material 3 and Miuix interfaces.

### Changes in `v0.6.1` / `v0.6.0`

1. Fixes `ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS` being blocked for normal app UIDs by Android 17 `EuiccUiDispatcherActivity` (`v0.6.1`): `ProfileListActivity` is now launched directly, with a `com.android.phone` privileged dispatcher fallback.
2. Adds Material 3 and Miuix interfaces, theme and language settings, a device and eUICC status page, an open source licenses page, and a mock test page.
3. SukiSU / KernelSU `ksu_handle_umount` immunity: once privileged `/product/priv-app` mounting is verified, a `/data/app` update layer is synced to avoid crashes when picking QR images from Gallery.
4. Seven feature switches, five of them Root-free; the core switches take effect in real time over telephony-framework IPC, without Root prompts in daily use.
5. Hooks both `Resources.getString` and `Resources.getText` for Android 17 `boot-framework.oat`, and adds metamodule selection checks and guidance.

---

## Metamodule Selection and umount Immunity

> Privilege requirement: the unmodified `MIUIEsimLPA.apk` (`com.miui.euicc`) must be mounted as a privileged system app via `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` and `/product/etc/permissions/privapp-permissions-euicc.xml` to obtain `FLAG_SYSTEM`, `BIND_EUICC_SERVICE`, `SECURE_ELEMENT_PRIVILEGED_OPERATION` and `MODIFY_PHONE_STATE`.

1. **Metamodules**: HyperOS 4 system partitions use multi-layer `overlayfs`; `Magic Mount-rs` may mount `/product/priv-app` incompletely in some environments (the Mobile Network entry shows "eSIM found" but does not respond, `lpaPrivMounted=false`). KernelSU / SukiSU users should enable `Hybrid Mount` or `meta-overlayfs` and reboot before enabling this module.
2. **umount immunity**: once privileged mounting is verified, the umount guard (a `/data/app` update layer) is enabled by default, preventing crashes when other app namespaces unmount `/product/priv-app` during QR image picking.
3. **Magisk (v20.4+) and APatch**: mount system paths on their own; flash the module directly.

---

## Control Center and Feature Switches

Open **HYPEROS4实体ESIM桥接** to view status and control the switches below:

| Group | Switch | Root | Description |
|---|---|---|---|
| Bridge | Master bridge switch | Required | Enables or disables the module; stays in sync with the root manager `Action` button. |
| Features | System eSIM (SIM2 sync) | Not required | Bidirectional sync with the Mobile Network eSIM toggle; controls SIM Slot 2 power. |
| Features | Carrier app provisioning | Not required | Lets carrier / roaming apps without system privileges invoke `EuiccManager` and open the provisioning confirmation dialog. |
| Features | Open `LPA://` links | Not required | When on, `LPA:1$...` activation links open in the Xiaomi provisioning page; when off, they are released to apps such as EasyLPAC. |
| Features | Rewrite eSIM settings text | Not required | Replaces the "replaces SIM 2" prompt in settings with wording that matches removable eSIM behavior. |
| System | Keep LPA after umount | Required | Syncs a `/data/app` update layer once privileged mounting is verified, avoiding provisioning crashes after umount; removed via `pm uninstall-system-updates` when switched off. |
| System | eSTK.me uses SE1 | Required | Selects SE1 for eSTK.me cards; when off, SE0 (standard ISD-R) is used. Reboot before managing profiles after switching. |
| Personalization | Show the Test tab | Not required | Shows or hides the Test tab in the bottom bar. |

Settings also includes a **Device and eUICC status** entry (device info, LPA mounting, `EUICC` feature declaration, `defaultCardId`, `LPA://` interception state, pull to refresh). Before System eSIM is enabled, the system shows "eSIM found" by design; it changes to "Manage eSIM" once enabled.

---

## Appearance and Personalization Settings

The app has two interface styles, Material 3 and Miuix, switchable under Settings > Theme. Both styles share the same settings and page state.

**Appearance**

- Interface style: Material 3 or Miuix.
- Dark mode: follow system, light, or dark.
- Dynamic color: uses wallpaper colors (Monet extraction for Material 3; replaces the default HyperOS blue in Miuix).

**Top and bottom bars (Miuix only)**

- Glass effect: blurs page content behind the top and bottom bars.
- Progressive blur: blur strength decreases from the screen edges toward the content (requires the glass effect).
- Floating bottom bar: shows the bottom bar as a floating pill.
- Liquid glass: applies light blur, edge refraction and highlights to the floating bottom bar (requires the floating bottom bar).

**Background**

- Background image: shown behind all pages; replaceable or removable (imported images are downscaled to 2160 px on the longest side).
- Background blur, background dim, and card opacity: adjustable once a background image is set.

**Other**

- Language: follow system, English, Simplified Chinese, or Traditional Chinese (per-app language, Android 13+).
- Show the Test tab: shows or hides the Test tab in the bottom bar.
- Hidden entries: repeated taps on the status area on the home page open the diagnostics report; repeated taps on the header of the About page export module logs.

---

## Core Adaptation Principles

- **Dual-channel SIM2 bridge**: primary OMAPI `SEService` (`SIM2`, virtual channels `12000+`) with automatic Telephony RIL (`iccOpenLogicalChannelBySlot`) fallback; supports ISO-7816-4 `61XX` (`GET RESPONSE`) and `6CXX` (`Le` retry).
- **Fail-closed safety and empty-card support**: ordinary SIM cards in Slot 2 fail closed immediately (`NO_SUCH_ELEMENT` -> `cardType=0`); genuine empty eUICC cards stay available for first-profile provisioning; SIM1 radio and GPIO paths remain isolated.

---

## Safety Constraints

- **No writes in mock mode**: `test.esim.local` mock downloads are intercepted before any write or reset APDU reaches the physical eUICC.
- **Privilege gate**: the `UPDATED_SYSTEM_APP` sync only runs when `/product/priv-app` `FLAG_SYSTEM` mounting is verified.
- **Reversible state**: disabling or removing the module runs `pm uninstall-system-updates` and restores backed-up settings and system properties; profiles on the card are untouched.
- **Privacy**: only SHA-256 digests are persisted in `Settings.Secure`.

---

## Supported Devices and Cards

- **Verified devices**: **Xiaomi MIX Flip 2** (`bixi`) and **REDMI K90 Pro Max** (`myron`) on HyperOS 4 (Android 17, SDK 37); no hardcoded device limits, so dual-SIM Xiaomi / REDMI devices on HyperOS 4 (SDK 35+) are supported in general.
- **Supported cards (Slot 2)**: **ESTKme**, **Xiaobai**, **Ctrip/JoyTel**, **9eSIM**, **Xesim**, **5ber**, and other GSMA SGP.22 (EasyEUICC-compatible) removable eUICC cards.

---

## Repository Layout

```
.
├── LICENSE                         # Apache License 2.0
├── README.md                       # Bilingual documentation
├── build_module.py                 # KernelSU / SukiSU / Magisk module packaging script
├── finalize_release.py             # Artifact verification and SHA-256 generation
├── prebuilt/
│   └── MIUIEsimLPA.apk             # Unmodified Xiaomi LPA extracted from stock HyperOS 4 firmware
└── probe/                          # Android Gradle project
    ├── app/                        # LSPosed module and control app (io.github.jabe1122.esimh4probe)
    │   └── src/main/java/io/github/local/esimh4probe/
    │       ├── CompatModule.java       # LibXposed hooks, APDU bridge, control IPC
    │       ├── CardTypeGuard.java      # Fail-closed ISD-R probing and switch state cache
    │       ├── MainActivity.java       # Control center (Material 3 pages)
    │       ├── MainScreen.kt           # Control center (Miuix pages: home / test / settings)
    │       ├── DashboardModel.java     # Page state shared by both styles
    │       ├── TesterPage.java         # Test tab: mock carrier provisioning
    │       ├── DeviceStatus*.*         # Device and eUICC status page
    │       ├── About*.* / Licenses*.*  # About and open source licenses pages
    │       └── …                       # LPA:// bridge, slot 2 subscription parsing, log export
    └── ui/                         # Shared UI module (io.github.local.esimh4ui)
        ├── ThemeScreen.kt / ThemeConfig.java   # Theme settings page and configuration
        ├── MiuixKit.kt / UiKit.java            # Miuix and Material components
        └── LanguageSetting.java                # Per-app language setting
```

---

## Installation

1. *(KernelSU / SukiSU)* Enable `Hybrid Mount` or `meta-overlayfs` and reboot (skip for Magisk / APatch).
2. Flash `XiaomiEsimLPA-HyperOS4-v0.6.1.zip` in your root manager and reboot (fresh installs start disabled; upgrades keep their state).
3. Enable **HYPEROS4实体ESIM桥接** (`io.github.jabe1122.esimh4probe`) in LSPosed for `com.android.phone` and `com.miui.euicc`.
4. Insert the removable eUICC card into SIM Slot 2, turn on the master switch in the app (or tap `Action` in the root manager), and reboot once.
5. For daily use, tap **Manage eSIM** in the app, or go to `Settings > Mobile network > eSIM` to manage profiles, view the EID, and provision from a QR code or the Gallery.

---

## Mock Testing

The **Test** tab in the bottom bar (showable from settings) provides non-destructive verification:

- The top banner shows test prerequisites (LPA privileged mounting, `EuiccManager.isEnabled()`, removable eUICC in Slot 2) with pull to refresh.
- The default code `LPA:1$test.esim.local$MOCK-TEST-001` exercises the provisioning dialog `DownloadConfirmDialogActivity`, console callbacks, and `LPA://` routing.
- Activation codes containing `test.esim.local` are intercepted and never written to the card.

---

## Building from Source

Requires Gradle 9 (CI uses 9.9.2) and JDK 17+. The default build is the R8-minified release, signed with the `signing.*` entries in `probe/local.properties` (or the `SIGNING_*` environment variables):

```bash
gradle -p probe clean assembleRelease
python build_module.py
python finalize_release.py
```

To package the debug APKs instead:

```bash
gradle -p probe clean assembleDebug
python build_module.py --debug
python finalize_release.py --debug
```

---

## Disclaimer

1. The module requires Root and LSPosed; back up important data before use.
2. Xiaomi, REDMI, HyperOS, MIUI, ESTKme, 9eSIM, Xesim and GSMA trademarks belong to their respective owners. This is an independent project with no affiliation to those vendors.
3. Provided "as is" under the Apache License 2.0, without liability for losses arising from its use.

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
