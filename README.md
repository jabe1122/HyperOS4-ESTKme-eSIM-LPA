# 小米 HyperOS 4 可插拔实体 eSIM（ESTKme / 小白卡 / 9eSIM 等）系统级适配与桥接方案
# HyperOS 4 Removable Physical eSIM (ESTKme / EasyEUICC-Compatible) Adaptation (`v0.5.0`)

[![Version](https://img.shields.io/badge/Version-v0.5.0-brightgreen.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-HyperOS%204%20%7C%20Android%2015%2B%20(SDK%2035%2B)-green.svg)]()
[![Hardware](https://img.shields.io/badge/SIM2-ESTKme%20%7C%20Xiaobai%20%7C%209eSIM%20%7C%20Xesim-orange.svg)]()
[![Root](https://img.shields.io/badge/Framework-KernelSU%20%7C%20SukiSU%20%7C%20Magisk%20%7C%20APatch%20%2B%20LSPosed-purple.svg)]()

---

## 目录 / Table of Contents

- [中文说明文档](#中文说明文档)
  - [项目概览与 v0.5.0 更新亮点](#项目概览与-v050-更新亮点)
  - [⚠️ KernelSU / SukiSU 用户必读：系统挂载元模块要求](#️-kernelsu--sukisu-用户必读系统挂载元模块要求)
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
  - [Overview & What's New in v0.5.0](#overview--whats-new-in-v050)
  - [⚠️ Mandatory Metamodule Guide for KernelSU / SukiSU Users](#️-mandatory-metamodule-guide-for-kernelsu--sukisu-users)
  - [Core Adaptation Principles](#core-adaptation-principles)
  - [Security Architecture & Safety Red Lines](#security-architecture--safety-red-lines)
  - [Supported Devices, OS & Removable eSIM Cards](#supported-devices-os--removable-esim-cards)
  - [Repository Architecture](#repository-architecture)
  - [Installation & Deployment Guide](#installation--deployment-guide)
  - [Mock Testing Framework](#mock-testing-framework)
  - [Building from Source](#building-from-source)
  - [Disclaimers & Safety Notice](#disclaimers--safety-notice)
  - [License](#license)

---

# 中文说明文档

## 项目概览与 v0.5.0 更新亮点

**小米 HyperOS 4 可插拔实体 eSIM 适配与桥接方案（`v0.5.0`）** 是一套面向 Xiaomi / REDMI 澎湃 HyperOS 4（Android 15 / 16 / 17，API 35+）的开源系统级兼容层与桥接模块，专门解决各类符合 **GSMA SGP.22 标准的可插拔实体 eUICC 卡**（支持 EasyEUICC 的 **小白卡、携程/JoyTel 白卡、ESTKme、9eSIM、Xesim、5ber** 等）在国行双实体卡槽小米/红米机型上无法直接使用小米官方系统原生 LPA 写卡与管理套餐的难题。

### `v0.5.0` 核心升级内容
1. **全面兼容主流可插拔实体 eSIM 卡（含全新空白卡首写）**：
   - 支持 **ESTKme** 以及所有兼容 **EasyEUICC / 标准 SGP.22** 的可插拔实体 eSIM 卡（如 **小白卡、携程/JoyTel 白卡、9eSIM、Xesim、5ber** 等）。
   - **全新空白卡（0 套餐 / 无激活 Profile）直接写卡支持**：针对新买的空白实体 eSIM 卡（尚无 ICCID、`SubscriptionInfo` 为空、`SIM_STATE_NOT_READY`），修复系统设置「开启 eSIM 失败」与「管理 eSIM」置灰问题，插卡即可直接进入系统 eSIM 管理界面扫码下载第一张套餐。
2. **ISO-7816-4 分段 APDU（`61XX` / `6CXX`）与 OMAPI + RIL 双通道容错**：
   - 完整支持部分白卡固件返回 `61 XX`（自动链式发送 `00 C0 00 00 XX` `GET RESPONSE` 重组完整报文）与 `6C XX`（动态修正期望长度 `Le` 重试），兼容 `BF3E` 与 `BF2E` EID 读取。
   - 新增 **Telephony RIL 逻辑通道自动兜底**：当部分卡片或机型无 ARA-M 规则导致 OMAPI 受阻时，自动回退至底层 RIL ISD-R 通道完成卡片识别与 EID 读取。
3. **跨机型 HyperOS 4 深度适配（MIX Flip 2 / REDMI K90 Pro Max 等）**：
   - 兼容原厂无 eSIM 配置机型（如 **Xiaomi MIX Flip 2** `bixi`，`cardId=1`）与原厂自带 `euicc.mep.xml` 多端口声明机型（如 **REDMI K90 Pro Max** `myron`，`cardId=5`），并适配不同 HyperOS 4 固件弹窗文案空格差异。
4. **KernelSU / SukiSU 系统特权挂载守护与自愈修复**：
   - 彻底根除无元模块环境下 `com.miui.euicc` 被误装入 `/data/app` 降级为普通第三方应用（丢失 `BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 等系统特权）的陷阱；自动检测并清理异常残留的 `/data/app` 无特权安装，并在主界面与测试应用中直观展示 **`LPA 系统特权挂载：✅ 已生效`** 诊断状态。

---

## ⚠️ KernelSU / SukiSU 用户必读：系统挂载元模块要求

> **重要提示**：小米官方原版 `MIUIEsimLPA.apk`（`com.miui.euicc`）**必须**通过系统分区挂载至 `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk`，并配合 `/product/etc/permissions/privapp-permissions-euicc.xml` 特权白名单声明，才能获得 `FLAG_SYSTEM`、`BIND_EUICC_SERVICE`、`SECURE_ELEMENT_PRIVILEGED_OPERATION` 与 `MODIFY_PHONE_STATE` 系统级特权。若缺少系统挂载，LPA 将无法绑定系统 eSIM 框架或访问 SIM2 安全元件。

- **KernelSU（v2.0+ / v3.0+）与 SukiSU Ultra（如 v4.x）用户**：
  1. 由于新版 KernelSU 与 SukiSU Ultra 移除了内置 OverlayFS 挂载，**刷入本模块前必须先安装并启用系统挂载元模块**（推荐使用官方 **`meta-overlayfs`** 或 **`magic_mount`**，确保 `/data/adb/metamodule` 处于有效启用状态）。
  2. 如果未安装或未启用元模块，本模块 `v0.5.0` 安装脚本（`customize.sh`）会主动拦截并给出双语提示，防止产生损坏的 `/data/app` 安装。
  3. 刷入并开启模块重启后，请打开桌面上的 **「HYPEROS4实体ESIM桥接」** 或 **「eSIM 模拟写卡测试」**，确认首行显示：
     - `LPA 系统特权挂载：✅ 已生效`
     - 若显示 `❌ 未挂载（请检查 KernelSU/SukiSU 元模块如 meta-overlayfs 并重启）`，请检查元模块是否正常工作并重启手机。
- **Magisk（v20.4+）与 APatch 用户**：自带系统分区挂载能力，直接刷入即可。

---

## 实体 eSIM 卡适配核心原理

### 1. OMAPI `SEService` + Telephony RIL 双通道 SIM2 桥接
在 HyperOS 4 系统下，部分固件的基带 RIL 或 OMAPI 访问控制存在差异。本项目通过 `CompatModule` 构建透明双通道架构：
- 优先连接 Android Open Mobile API (`android.se.omapi.SEService`) **`SIM2`** 读卡器，分配从 **`12000+`** 起步的独立虚拟逻辑通道池，严格同步生命周期、杜绝句柄泄漏；
- 支持 ISO-7816-4 `61 XX`（`GET RESPONSE` 分段拼接）与 `6C XX`（`Le` 自动重试）状态字处理；
- 当 OMAPI 因卡片无 ARA-M 规则或服务未就绪受限时，自动使用 `TelephonyManager.iccOpenLogicalChannelBySlot(1, ISDR_AID, 0)` RIL 逻辑通道完成安全探测与 EID 解析。

```
+--------------------------------------------------------------------+
|       Android 系统框架 / 第三方运营商应用 / 官方 MIUIEsimLPA.apk     |
+--------------------------------------------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|          CompatModule (LSPosed 模块: com.android.phone / euicc)    |
|  - APDU 通道路由器、虚拟通道池 (12000+) 与 61XX/6CXX 协议处理        |
+--------------------------------------------------------------------+
           |                                         |
           | (SIM2 双通道桥接: OMAPI 优先 + RIL 兜底)  | (普通 UICC 通道)
           v                                         v
+-----------------------------+           +--------------------------+
|  SIM2 实体 eUICC 通信层      |           | 原生 Telephony RIL       |
|  OMAPI "SIM2" / RIL Slot 1  |           | 物理卡槽 0 (SIM1 主卡)   |
+-----------------------------+           +--------------------------+
           |                                         |
           v                                         v
+-----------------------------+           +--------------------------+
| 可插拔实体 eSIM 卡 (SIM2)    |           | 用户物理 SIM1 电话卡     |
| ESTKme / 小白卡 / 9eSIM 等   |           | 绝对隔离，保持常开       |
+-----------------------------+           +--------------------------+
```

### 2. SGP.22 ISD-R AID 探测与全新空白卡生命周期支持
GSMA SGP.22 规范定义了 eUICC 根安全域（ISD-R）应用标识符：
```
A0 00 00 05 59 10 10 FF FF FF FF 89 00 00 01 00
```
- 支持 `GET DATA [BF 3E]`（及 `BF 2E` 兼容响应）ASN.1 DER 解码提取 32 位数字 EID（Tag `0x5A`）；
- 即使物理卡槽 2 中的实体 eSIM 卡为**全新空白卡**（0 个已下载套餐、无活跃 ICCID、`SubscriptionInfo` 为空、`SIM_STATE_NOT_READY`），只要通过 ISD-R 真卡校验，系统设置中的 eSIM 开关与「管理 eSIM」入口即保持可用，允许用户顺畅写入第一张套餐。

### 3. Fail-Closed 实体卡类型安全守护（`CardTypeGuard`）
为防止用户在 SIM2 插回普通手机卡（移动/联通/电信普通 USIM）时受到任何干扰，`CardTypeGuard` 严格执行**失效关闭（Fail-Closed）**策略：
- 监听卡槽状态广播并异步向 SIM2 发起 ISD-R AID 握手验证；
- 普通 SIM 卡返回 `NO_SUCH_ELEMENT` 时立即判定 `eUICC = false`，绝不误判为 eSIM，绝不修改或停用普通 SIM 卡；
- **隐私保护原则**：`Settings.Secure` 中绝不保存明文 ICCID 或 EID，仅保存 SHA-256 摘要（`xiaomi_esim_h4_verified_iccid`、`xiaomi_esim_h4_verified_eid`）。

### 4. SIM1 射频与网络彻底隔离（拦截 OEM GPIO 切换）
小米板载 eSIM 机型在激活 eSIM 时会通过底层 GPIO 切换物理卡槽射频通路。`CompatModule` 深度拦截 `QcMiuiEsimManager`、`MiuiPhoneInterfaceManager` 与 `XmLPAService` 中的硬件切换方法（`setSimType`、`switchSlot`、`isSupportEsim`），确保 **SIM1 主卡 5G/4G/VoLTE 全程零断流、零干扰**。

---

## 安全架构与测试红线约束

针对插有真实商业套餐的实体 eSIM 卡，本项目内置多重非破坏性验证保护：

| 红线规则 | 实现约束 |
|---|---|
| **严禁测试态写卡/重置** | 针对 `test.esim.local` 模拟测试流程，严格拦截 `BF 36`（LoadBoundProfilePackage）与 `BF 34`（EuiccMemoryReset），绝不写入物理卡闪存。 |
| **严禁未经授权删卡** | 模拟测试全程在内存层完成授权弹窗与回调验证，卡内现有真实套餐 100% 原样保留。 |
| **状态完整可逆还原** | 模块关闭、禁用或卸载时，通过 `/data/adb/xiaomi-esim-hyperos4/` 备份与 `restore-guard.sh` 自动还原全部系统设置与属性。 |

---

## 支持设备、系统与实体卡列表

- **已验证机型**：
  - **Xiaomi MIX Flip 2**（`bixi`，HyperOS 4 / Android 17 API 37）
  - **REDMI K90 Pro Max**（`myron`，HyperOS 4 `OS4.0.6.0.XPMCNXM` / Android 17 API 37）
  - **通用支持**：无硬编码机型限制，适配所有运行 **Xiaomi HyperOS 4（Android 15 / 16 / 17，SDK 35+）** 的双物理卡槽小米 / REDMI 机型。
- **支持的可插拔实体 eSIM 卡（必须插入物理 SIM 卡槽 2）**：
  - **ESTKme** 全系列可插拔实体 eUICC
  - **小白卡**、**携程 / JoyTel 实体白卡**
  - **9eSIM**、**Xesim**、**5ber** 及其他符合 **GSMA SGP.22 标准（支持 EasyEUICC）** 的可插拔实体 eSIM 卡（支持已写卡与**全新空白卡**）。

---

## 仓库与模块架构

```
work/esim-adapt/
├── .gitignore                      # Git 忽略规则
├── LICENSE                         # Apache 2.0 开源协议
├── README.md                       # 中英双语完整技术文档
├── build_module.py                 # KernelSU / SukiSU / Magisk 模块自动化打包脚本 (v0.5.0)
├── finalize_release.py             # 发布产物完整性校验与 SHA256 生成脚本
├── prebuilt/
│   └── MIUIEsimLPA.apk             # 提取自官方 HyperOS 4 固件的未修改原版小米 LPA (v4.1.0.1)
├── probe/                          # Android Studio Gradle 工程
│   ├── app/                        # LSPosed 核心桥接与诊断模块 (io.github.jabe1122.esimh4probe)
│   │   └── src/main/java/io/github/local/esimh4probe/
│   │       ├── CompatModule.java       # LibXposed 入口、APDU 路由与多机型适配
│   │       ├── CardTypeGuard.java      # Fail-closed ISD-R AID 探测（含空白卡支持）
│   │       ├── EuiccCardReader.java    # 双通道探测与 EID 摘要工具
│   │       ├── Sim2Subscription.java   # 卡2订阅解析与可选空卡状态兼容
│   │       ├── Sim2Control.java        # Root CLI 状态探测与同步工具
│   │       ├── LpaBridgeActivity.java  # LPA:// 链接与扫码激活 Intent 桥接
│   │       └── MainActivity.java       # 本地诊断面板与 ESIM_PROBE CLI
│   └── tester/                     # 独立模拟运营商写卡测试应用 (io.github.local.esimh4tester)
│       └── src/main/java/io/github/local/esimh4tester/
│           └── TesterActivity.java     # 权限挂载自检与一键写卡弹窗模拟验证
└── outputs/                        # 构建发布产物目录
```

---

## 安装与部署指南

### 第一步：（仅 KernelSU / SukiSU 用户）确认已启用系统挂载元模块
1. 如果您使用 **KernelSU（v2.0+）** 或 **SukiSU Ultra**，请先确认已安装并启用 **`meta-overlayfs`** 或 **`magic_mount`** 元模块，且已重启生效。
2. Magisk 与 APatch 用户可直接跳过此步。

### 第二步：刷入模块 ZIP
1. 下载最新发布包 `XiaomiEsimLPA-HyperOS4-v0.5.0.zip`。
2. 打开 KernelSU / SukiSU / Magisk / APatch 管理器，从本地选择刷入该 ZIP。
3. 安装脚本会自动校验挂载环境、清理历史异常 `/data/app` LPA 残留，并将系统原始设置备份至 `/data/adb/xiaomi-esim-hyperos4/`（权限 `0700`）。
4. **重启手机**。

### 第三步：配置 LSPosed 作用域
1. 打开 LSPosed 管理器，启用模块 **「HYPEROS4实体ESIM桥接」**（`io.github.jabe1122.esimh4probe`）。
2. 确保勾选以下两个作用域（静态作用域已预置）：
   - `com.android.phone`（电话服务）
   - `com.miui.euicc`（小米 eSIM LPA）

### 第四步：插入实体 eSIM 卡并开启模块功能
1. 将实体 eSIM 卡（ESTKme / 小白卡 / 9eSIM / Xesim 等）放入手机 **SIM 卡槽 2**。
2. 打开 KernelSU / SukiSU / Magisk → 找到 `Xiaomi eSIM LPA - HyperOS 4` → 点击 **「操作 (Action)」** 切换为开启状态。
3. **重启手机**。
4. 打开桌面上的 **「HYPEROS4实体ESIM桥接」** 确认显示 **`LPA 系统特权挂载：✅ 已生效`**，然后前往系统「设置 → 双卡与移动网络 → eSIM管理」即可读取 EID、添加新套餐（支持全新空白卡直接扫码写卡）或切换已有套餐。

---

## 模拟测试框架与非破坏性验证

模块在开启状态下会自动部署独立测试应用 `io.github.local.esimh4tester`（关闭或卸载模块时自动卸载清理）：
- 顶部状态面板实时检测 **LPA 系统特权挂载**、`FEATURE_TELEPHONY_EUICC`、`EuiccManager.isEnabled`、`defaultCardId`、**卡槽 2 实体卡状态（区分已启用套餐与空白卡）** 及 `LPA://` 跳转就绪情况。
- 默认使用安全模拟激活码 `LPA:1$test.esim.local$MOCK-TEST-001` 调起小米原生授权弹窗 `DownloadConfirmDialogActivity`，底层自动拦截测试码，**100% 不向实体卡写入任何测试数据**。

---

## 源码构建指南

### 构建环境要求
- JDK 21+
- Android SDK（API 35/36）
- Gradle 8.11+
- Python 3.10+

### 一键构建与打包
```bash
cd work/esim-adapt

# 1. 编译 LSPosed 桥接模块 APK 与模拟测试 APK
gradle -p probe clean assembleDebug

# 2. 生成 XiaomiEsimLPA-HyperOS4-v0.5.0.zip 刷机模块包
python build_module.py

# 3. 执行发布完整性断言校验并生成 SHA256SUMS.txt
python finalize_release.py
```

---

## 安全须知与免责声明

1. **Root 风险声明**：本模块需要 Root 权限与 LSPosed 框架支持，刷机前请做好重要数据备份。
2. **商标与独立性声明**：Xiaomi、REDMI、HyperOS、MIUI、ESTKme、9eSIM、Xesim、GSMA 等商标归各自权利人所有。本项目为独立洁净室（Clean-Room）开源兼容层实现，不含任何第三方受限残留代码，亦与上述厂商无隶属关系。
3. **免责条款**：本项目按 Apache License 2.0 协议「原样」提供，不对因不当操作造成的任何损失承担责任。

---

## 开源协议与参与贡献

本项目基于 **Apache License 2.0** 开源，详见 [LICENSE](LICENSE)。欢迎提交 Issue 与 Pull Request 反馈更多机型与实体 eSIM 卡片实测结果！

---
---

# English Documentation

## Overview & What's New in `v0.5.0`

**HyperOS 4 Removable Physical eSIM Adaptation (`v0.5.0`)** is a clean-room, system-level compatibility and bridging module for Xiaomi and REDMI devices running **Xiaomi HyperOS 4 (Android 15 / 16 / 17, SDK 35+)**. It enables standard **GSMA SGP.22 removable eUICC cards**—including **ESTKme** as well as EasyEUICC-compatible cards such as **Xiaobai (小白卡), Ctrip/JoyTel white cards, 9eSIM, Xesim, and 5ber**—installed in **physical SIM Slot 2** to work natively with Xiaomi's unmodified stock LPA (`MIUIEsimLPA.apk` v4.1.0.1).

### Key Highlights in `v0.5.0`
1. **Universal SGP.22 Removable eUICC & Brand-New Empty Card Support**:
   - Supports **ESTKme** and EasyEUICC-compatible removable eUICC cards (**Xiaobai / JoyTel / 9eSIM / Xesim / 5ber**, etc.).
   - **First-Profile Write on Empty Cards**: Brand-new removable eUICC cards with zero profiles (no active ICCID, no `SubscriptionInfo`, `SIM_STATE_NOT_READY`) keep the system eSIM toggle and "Manage eSIM" entry active without throwing `"Physical SIM2 subscription unavailable"`.
2. **ISO-7816-4 Segmented APDU (`61XX` / `6CXX`) & Dual-Channel (OMAPI + RIL) Fallback**:
   - Handles chained `61 XX` (`GET RESPONSE` `00 C0 00 00 XX`) and `6C XX` (`Le` retry) responses, as well as `BF3E` / `BF2E` `GET EID` payloads.
   - Automatically falls back to **Telephony RIL logical channels** (`iccOpenLogicalChannelBySlot(1, ISDR_AID, 0)`) when OMAPI `SEService` is unavailable or blocked by missing ARA-M rules.
3. **Multi-Device HyperOS 4 Support (`MIX Flip 2`, `REDMI K90 Pro Max`, etc.)**:
   - Works seamlessly across devices without factory eSIM declarations (e.g., **Xiaomi MIX Flip 2** `bixi`, `cardId=1`) and devices whose stock `/vendor` includes `euicc.mep.xml` (e.g., **REDMI K90 Pro Max** `myron`, `cardId=5`), including localized dialog whitespace variations.
4. **KernelSU / SukiSU System-Mount Guard & Self-Repair**:
   - Eliminates the unmounted `/data/app` trap where `com.miui.euicc` lost privileged permissions (`BIND_EUICC_SERVICE`, `SECURE_ELEMENT_PRIVILEGED_OPERATION`, `MODIFY_PHONE_STATE`). Automatically detects and cleans up unprivileged `/data/app` installs, exposes `sys.xiaomi_esim_h4.mount_ok`, and displays prominent privilege-mount diagnostics in both `MainActivity` and `TesterActivity`.

---

## ⚠️ Mandatory Metamodule Guide for KernelSU / SukiSU Users

> **CRITICAL REQUIREMENT**: Xiaomi's unmodified `MIUIEsimLPA.apk` (`com.miui.euicc`) **must** be mounted as a privileged system app at `/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` alongside `/product/etc/permissions/privapp-permissions-euicc.xml` so Android grants `FLAG_SYSTEM`, `BIND_EUICC_SERVICE`, `SECURE_ELEMENT_PRIVILEGED_OPERATION`, and `MODIFY_PHONE_STATE`. Installing `MIUIEsimLPA.apk` into `/data/app` without a system overlay strips all privileged eSIM permissions.

- **KernelSU (v2.0+ / v3.0+) & SukiSU Ultra (v4.x+) Users**:
  1. Modern KernelSU and SukiSU Ultra require a **system-mount metamodule** to mount `system/product/...` overlays. You **must install and enable an active metamodule**—such as **`meta-overlayfs`** or **`magic_mount`** (`/data/adb/metamodule`)—and reboot **before** flashing this module.
  2. In `v0.5.0`, `customize.sh` automatically blocks installation on KernelSU/SukiSU when no active metamodule is present, and repairs any prior unprivileged `/data/app` `com.miui.euicc` residue.
  3. After enabling the module and rebooting, open the diagnostic app or mock tester app and verify the top line reads:
     `LPA 系统特权挂载：✅ 已生效` (`lpaPrivMounted=true`).
- **Magisk (v20.4+) & APatch Users**: Built-in magic/overlay mounting works out of the box—no extra metamodule is needed.

---

## Core Adaptation Principles

### 1. Dual-Channel SIM2 Bridging (OMAPI `SEService` + Telephony RIL)
- Routes framework and LPA APDU sessions to **physical SIM Slot 2 (`SIM2` / slot index `1`)** using isolated virtual channel IDs starting at **`12000+`**.
- Reassembles ISO-7816-4 `61 XX` segmented responses and retries `6C XX` length hints cleanly without double-fetching.
- Falls back to Telephony RIL logical channels when OMAPI is restricted, strictly closing channels in `finally` blocks to prevent handle leaks.

### 2. Fail-Closed `CardTypeGuard` & Empty-Card Support
- Probes SIM2 against the GSMA SGP.22 ISD-R AID (`A0000005591010FFFFFFFF8900000100`).
- **Fail-Closed Safety**: Ordinary non-eUICC SIM cards returning `NO_SUCH_ELEMENT` are immediately classified as normal SIMs (`eUICC = false`) and are never altered or disabled.
- **Empty eUICC Support**: Genuine removable eUICC cards with zero profiles remain recognized and switchable so users can download their first profile directly in Xiaomi's native UI.
- **Privacy**: Only SHA-256 digests (`xiaomi_esim_h4_verified_iccid`, `xiaomi_esim_h4_verified_eid`) are stored in `Settings.Secure`—never plaintext identifiers.

### 3. Primary SIM1 Radio Isolation
- Neutralizes OEM GPIO/antenna-multiplexer calls (`setSimType`, `switchSlot`, `isSupportEsim`) in `com.android.phone` and `com.miui.euicc`, ensuring continuous 5G/4G/VoLTE service on SIM1.

---

## Security Architecture & Safety Red Lines

| Safety Rule | Enforcement |
|---|---|
| **Zero Unauthorized Writes in Mock Mode** | Mock activation domain `test.esim.local` intercepts download flows before any `BF 36` (`LoadBoundProfilePackage`) or `BF 34` (`EuiccMemoryReset`) APDU reaches physical card flash. |
| **No Unprivileged LPA Poisoning** | `customize.sh`, `service.sh`, and `action.sh` never `pm install` `com.miui.euicc` as a user app and automatically purge any unprivileged `/data/app` residue. |
| **Full State Reversibility** | Disabling or uninstalling restores all backed-up settings and properties via `/data/adb/xiaomi-esim-hyperos4/` and `restore-guard.sh`. |

---

## Supported Devices, OS & Removable eSIM Cards

- **Verified Devices**:
  - **Xiaomi MIX Flip 2** (`bixi`, HyperOS 4 / Android 17 SDK 37)
  - **REDMI K90 Pro Max** (`myron`, HyperOS 4 `OS4.0.6.0.XPMCNXM` / Android 17 SDK 37)
  - **Generic Compatibility**: Any dual-physical-SIM Xiaomi / REDMI phone running **Xiaomi HyperOS 4 (Android 15, 16, or 17 / SDK 35+)**.
- **Supported Removable eUICC Cards (in SIM Slot 2)**:
  - **ESTKme** (all models)
  - **EasyEUICC-compatible / Standard GSMA SGP.22 removable eUICC cards**: **Xiaobai (小白卡)**, **Ctrip / JoyTel removable eSIM**, **9eSIM**, **Xesim**, **5ber**, etc. (both pre-provisioned and brand-new empty cards).
- **Root & Xposed Environment**:
  - **Magisk v20.4+**, **APatch**, or **KernelSU / SukiSU Ultra with an active metamodule (`meta-overlayfs` / `magic_mount`)**.
  - **LSPosed** (LibXposed API 101+, scopes: `com.android.phone` and `com.miui.euicc`).

---

## Repository Architecture

```
work/esim-adapt/
├── .gitignore
├── LICENSE
├── README.md
├── build_module.py                 # Builds XiaomiEsimLPA-HyperOS4-v0.5.0.zip
├── finalize_release.py             # Release integrity validator & SHA256 generator
├── prebuilt/
│   └── MIUIEsimLPA.apk             # Unmodified Xiaomi HyperOS 4 LPA (v4.1.0.1)
├── probe/
│   ├── app/                        # LSPosed Module & Diagnostics UI (io.github.jabe1122.esimh4probe)
│   └── tester/                     # Standalone Mock Carrier App (io.github.local.esimh4tester)
└── outputs/                        # Release ZIPs, APKs, and checksums
```

---

## Installation & Deployment Guide

1. **(KernelSU / SukiSU Only)** Install and enable **`meta-overlayfs`** or **`magic_mount`** first and reboot so `/data/adb/metamodule` is active.
2. Flash **`XiaomiEsimLPA-HyperOS4-v0.5.0.zip`** in Magisk / KernelSU / SukiSU / APatch Manager and **reboot**.
3. In **LSPosed Manager**, enable **HYPEROS4实体ESIM桥接** (`io.github.jabe1122.esimh4probe`) for `com.android.phone` and `com.miui.euicc`.
4. Insert your removable eSIM card (ESTKme / Xiaobai / 9eSIM / Xesim, etc.) into **SIM Slot 2**.
5. In your root manager, tap **Action** on `Xiaomi eSIM LPA - HyperOS 4` to switch mode to **ON**, then **reboot**.
6. Verify `LPA 系统特权挂载：✅ 已生效` in the module app, then manage eSIM profiles natively under `Settings → Mobile Networks → eSIM`.

---

## Mock Testing Framework

When `MODE=on`, the standalone app `io.github.local.esimh4tester` lets you safely verify carrier `EuiccManager.downloadSubscription`, `LPA://` deep links, and `ACTION_START_EUICC_ACTIVATION` using `LPA:1$test.esim.local$MOCK-TEST-001` without modifying any real profile on your physical eUICC card.

---

## Building from Source

```bash
cd work/esim-adapt
gradle -p probe clean assembleDebug
python build_module.py
python finalize_release.py
```

---

## Disclaimers & Safety Notice

1. **Root Access**: Requires root privileges and LSPosed. Use at your own risk.
2. **Trademarks**: Xiaomi, REDMI, HyperOS, MIUI, ESTKme, 9eSIM, Xesim, and GSMA are trademarks of their respective owners. This clean-room open-source project contains zero proprietary upstream residue and is unaffiliated with those vendors.
3. **No Warranty**: Provided "AS IS" under the Apache License 2.0.

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
