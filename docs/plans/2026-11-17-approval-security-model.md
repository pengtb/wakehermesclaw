# 审批与安全设置 Implementation Plan

> **For Hermes:** 按任务顺序执行，每个任务单独 commit。开工前先读「已定决策」一节，超出范围的事不做（见文末「不做的事」）。

**Goal:** 把 dsh-Go 那套「确认方式 / 应用名单 / 始终允许 / 解除限制 / 操作记录」落到 WakeHermesClaw 的 Mobile Bridge（路径 ②③）上，让用户能按应用粒度控制 agent 能碰什么。

**Architecture:** 在能力组过滤之后、审批之前插一个纯函数规则引擎 `SecurityPolicy.decide()`；规则状态由两个新 Store（应用名单、解除限制）持有，落在 `EncryptedSharedPreferences`；审批卡改成三档（允许一次 / 本轮允许 / 取消），"本轮"用本地时间窗口近似；读屏文本在出口处过 `Masker`。

**Tech Stack:** Kotlin、Compose（现有 `ui/bridge/` 风格）、kotlinx.serialization、Robolectric（`./gradlew testStandardDebugUnitTest`）、10 个 `values-*` 语言目录。

---

## 现状（已核实）

| 项 | 现状 | 文件 |
|---|---|---|
| 确认方式 | 三档但语义是「风险阈值」：`ALWAYS_CONFIRM` / `CONFIRM_MEDIUM_HIGH` / `TRUSTED` | `bridge/MobileBridgeConfig.kt:14,110`；`bridge/MobileBridgeServer.kt:292` |
| 审批 | 单 Activity，按钮：允许一次 / 10 分钟 / 1 小时 / 始终允许 / 拒绝；名字命中破坏性动词时隐藏长 TTL | `bridge/BridgeApprovalActivity.kt:99-115` |
| 授权 | 按能力存 TTL（内存），`/grants` `/revoke` 可读写 | `bridge/grants/BridgeGrants.kt`；`BridgeApprovalRegistry.kt` |
| 应用维度 | 只有 `packageBlocklist`，且只作用于 `apps.launch` | `MobileBridgeConfig.kt:45,85`；`ExtraCapabilities.kt` |
| 解除限制 | 无 | — |
| 读屏打码 | 无；`ScreenNodeSnapshot` 不含 `isPassword` | `bridge/accessibility/AgentVoiceAccessibilityService.kt:255-293` |
| 操作记录 | 有，记能力/风险/状态/时间，message 截 180 字 | `bridge/BridgeActivityLog.kt` |
| 通知 | 只开 Activity，正文不带参数 | `bridge/BridgeApprovalNotifier.kt` |
| 路径 ① | 网关原生走 `node/InvokeDispatcher`，**默认全通**，不经桥接审批 | `AGENT.md` §3.1 |

我们**没有**文本输入能力，也没有 `android_shell`。所以 dsh-Go 的「密码与验证码交还用户」在我们这里只体现为：读屏时密码框打码（将来真加了输入能力再接同一道闸）。

## 语义映射

| dsh-Go | 本项目 | 说明 |
|---|---|---|
| 每次都询问 / 本任务免确认 / 始终免确认 | `ConfirmMode.ASK_EVERY_TIME / THIS_TASK / ALWAYS_ALLOW` | 旧 `TRUSTED` 迁移为 `ALWAYS_ALLOW`；风险等级退化为"是否属于需确认范围"，不再当模式 |
| 允许一次 / 本轮允许 / 取消 | `ApprovalScope.ONCE / TASK / DENY` | TASK 写入窗口化 grant（默认 10 分钟，桥接停止即清） |
| 应用名单：不碰 / 每一步都确认 / 照常 | `AppRule.OFF / STEP_UP / NORMAL` | 作用于 `screen.*`（前台包名）、`apps.launch`（目标包名）、`screen.screenshot` |
| 始终允许（总开关 + App 列表） | `AppRule.ALWAYS_ALLOW` | 与「应用名单」同一份存储，页面顶部一个总开关 |
| 解除限制：系统设置 / 密码验证码 / 本应用 | `Unlock.SYSTEM_SURFACE / SECRETS / SELF` | 开启需长按 1.5 s；有 pending 审批时禁改 |
| 操作记录（不含屏幕内容） | 扩 `BridgeActivityLog` 加 `targetPackage` / `policyReason` | 现有 100 条上限不动 |
| 悬浮条 / 3 分钟无操作 / Android Shell | **不做** | 前者要 overlay 权限与常驻服务；后者我们没有该能力 |

## 规则优先级（`decide()` 的唯一口径）

```
0. 能力组未开 / isAvailable=false        → DENY unsupported_capability（已有，保持不变）
1. 目标应用命中 OFF                       → DENY app_blocked（应用名单里才能改）
2. 目标是系统设置页且未解 SYSTEM_SURFACE  → DENY system_surface_locked
3. 目标是本应用且未解 SELF                → DENY self_surface_locked
4. 目标应用命中 STEP_UP                   → 必须审批（忽略 grant、忽略 ALWAYS_ALLOW、忽略 ConfirmMode=ALWAYS_ALLOW）
5. 目标应用命中 ALWAYS_ALLOW              → ALLOW（不询问）
6. riskLevel == LOW                      → ALLOW
7. 按 ConfirmMode：ASK_EVERY_TIME → 审批；THIS_TASK → 本任务窗口内有 grant 则放行，否则审批；ALWAYS_ALLOW → 放行
8. 名字命中破坏性动词的，即使批准也只发 ONCE / TASK，永不给持久 grant（沿用现有口径）
```

`SECRETS` 不在 `decide()` 里拦截——它控制 `Masker` 是否对密码节点打码（关闭打码 = 返回 `••••`）。

## 新文件与改动

```
app/src/main/java/com/openclaw/assistant/bridge/security/
├── SecurityPolicy.kt      // ConfirmMode / AppRule / Unlock / Decision / decide()
├── AppRulesStore.kt       // Map<pkg, AppRule> + 总开关，EncryptedSharedPreferences
├── UnlockStore.kt         // Set<Unlock> + 开启审计
├── Masker.kt              // 卡号/身份证/密码打码
├── SensitiveSurfaces.kt   // 系统设置包名表、self 包名、isPasswordNode()
└── CapabilityTarget.kt    // 能力 → 目标包名
app/src/main/java/com/openclaw/assistant/ui/bridge/
├── AppRulesActivity.kt        // 新建：应用名单 + 始终允许总开关
└── UnlockSettingsActivity.kt  // 新建：解除限制三项
```

---

## 已定决策（2026-11-17 与用户确认）

1. **只覆盖桥接路径（②③）**。网关原生（①，`node/InvokeDispatcher`）维持现状，不改；缺口写进 README「已知限制」。
2. **"本轮"就是本地时间窗口**：10 分钟，键 `(能力, 目标包名)`，桥接停止 / token 轮换 / 进程死亡即清。**不预留 `runId` 字段**，`/execute` 请求结构不动。
3. **硬规则默认开**：系统设置页、本应用默认拒绝，密码框默认打码。升级后首次打开桥接设置给一次性说明，不做灰度。
4. **文案最后补**：实现阶段只写 `values/`（英文默认）与 `values-zh-rCN`，其余 8 种语言作为最后一个任务补齐，以 `compileStandardDebugResources` 通过为判据。

---

## Task 1: 建立规则模型与纯函数决策器

**Objective:** 一次性把优先级写成可单测的纯函数，后续接入点都只调它。

**Files:**
- Create: `app/src/main/java/com/openclaw/assistant/bridge/security/SecurityPolicy.kt`
- Test: `app/src/test/java/com/openclaw/assistant/bridge/security/SecurityPolicyTest.kt`

**Step 1: 写失败测试（表驱动，先写 6 条）**

```kotlin
class SecurityPolicyTest {
    private fun cap(name: String, risk: RiskLevel) = FakeCap(name, risk)

    @Test fun `banking app is blocked even when always allow is on`() {
        val d = SecurityPolicy.decide(
            cap = cap("screen.tap", RiskLevel.MEDIUM),
            target = Target(pkg = "com.icbc", isSystemSurface = false, isSelf = false),
            policy = PolicySnapshot(ConfirmMode.ALWAYS_ALLOW, mapOf("com.icbc" to AppRule.OFF), emptySet()),
            grants = NoGrants,
        )
        assertFalse(d.allow); assertEquals("app_blocked", d.reason)
    }

    @Test fun `step up app asks even in always allow mode`() {
        val d = SecurityPolicy.decide(/* com.tencent.mm → STEP_UP, ConfirmMode.ALWAYS_ALLOW */)
        assertTrue(d.needsApproval); assertEquals("app_step_up", d.reason)
    }

    @Test fun `settings surface is denied until unlocked`() { /* com.android.settings, unlocks empty → system_surface_locked */ }

    @Test fun `settings surface passes after unlock`() { /* unlocks = setOf(SYSTEM_SURFACE) */ }

    @Test fun `always allow app skips approval but not step up`() { }

    @Test fun `low risk capability never asks`() { /* apps.list, ConfirmMode.ASK_EVERY_TIME → !needsApproval */ }

    @Test fun `task window grant only covers same capability and package`() { }
}
```

**Step 2: 跑测试确认编译失败**

Run: `./gradlew testStandardDebugUnitTest --tests '*SecurityPolicyTest*'`
Expected: 编译错误，`SecurityPolicy` 未定义。

**Step 3: 写实现**

```kotlin
package com.openclaw.assistant.bridge.security

enum class ConfirmMode { ASK_EVERY_TIME, THIS_TASK, ALWAYS_ALLOW }
enum class AppRule { NORMAL, STEP_UP, OFF, ALWAYS_ALLOW }
enum class Unlock { SYSTEM_SURFACE, SECRETS, SELF }

data class Target(val pkg: String?, val isSystemSurface: Boolean = false, val isSelf: Boolean = false)

data class PolicySnapshot(
    val confirmMode: ConfirmMode,
    val appRules: Map<String, AppRule>,
    val unlocks: Set<Unlock>,
)

data class Decision(
    val allow: Boolean,
    val needsApproval: Boolean,
    val reason: String,
    val missingUnlock: Unlock? = null,
    val targetPackage: String? = null,
)

interface GrantQuery { fun hasTaskGrant(capability: String, pkg: String?): Boolean }

object NoGrants : GrantQuery { override fun hasTaskGrant(capability: String, pkg: String?) = false }

object SecurityPolicy {
    fun decide(capability: String, risk: RiskLevel, target: Target, policy: PolicySnapshot, grants: GrantQuery): Decision {
        val rule = target.pkg?.let { policy.appRules[it] }
        if (rule == AppRule.OFF) return Decision(false, false, "app_blocked", null, target.pkg)
        if (target.isSystemSurface && Unlock.SYSTEM_SURFACE !in policy.unlocks)
            return Decision(false, false, "system_surface_locked", Unlock.SYSTEM_SURFACE, target.pkg)
        if (target.isSelf && Unlock.SELF !in policy.unlocks)
            return Decision(false, false, "self_surface_locked", Unlock.SELF, target.pkg)
        if (rule == AppRule.STEP_UP) return Decision(true, true, "app_step_up", null, target.pkg)
        if (rule == AppRule.ALWAYS_ALLOW) return Decision(true, false, "app_always_allow", null, target.pkg)
        if (risk == RiskLevel.LOW) return Decision(true, false, "low_risk", null, target.pkg)
        return when (policy.confirmMode) {
            ConfirmMode.ALWAYS_ALLOW -> Decision(true, false, "always_allow_mode", null, target.pkg)
            ConfirmMode.ASK_EVERY_TIME -> Decision(true, true, "approval_required", null, target.pkg)
            ConfirmMode.THIS_TASK -> if (grants.hasTaskGrant(capability, target.pkg))
                Decision(true, false, "task_grant", null, target.pkg)
            else Decision(true, true, "approval_required", null, target.pkg)
        }
    }
}
```

**Step 4: 跑测试**

Run: `./gradlew testStandardDebugUnitTest --tests '*SecurityPolicyTest*'`
Expected: 7 条全过。

**Step 5: commit**

```bash
git add app/src/main/java/com/openclaw/assistant/bridge/security/SecurityPolicy.kt app/src/test/java/com/openclaw/assistant/bridge/security/SecurityPolicyTest.kt
git commit -m "feat(bridge): pure security policy decider for app/rule gating"
```

---

## Task 2: 目标应用解析（能力 → 包名）

**Objective:** 让 `decide()` 拿到"这次操作落在哪个应用上"。

**Files:**
- Create: `.../bridge/security/CapabilityTarget.kt`
- Test: `app/src/test/java/com/openclaw/assistant/bridge/security/CapabilityTargetTest.kt`
- Modify: `.../bridge/accessibility/AgentVoiceAccessibilityService.kt`（新增 `currentPackageName()`，内部复用 `snapshotRoots()` 取 `root.packageName`）

规则：

| 能力 | 目标包名来源 |
|---|---|
| `screen.tap/swipe/long_press/drag/find_nodes/describe_node/hash/diff/window.describe` | 当前活动窗口包名（无障碍 service）|
| `screen.screenshot` / `screen.screenshot.fetch` | 同上（抓屏时记录，fetch 用记录的包名）|
| `apps.launch` | `arguments["package"]` |
| `notification.dismiss` | `arguments["package"]`（可选，缺省不过滤）|
| 其余 | `null`（不参与应用规则）|

**Step 1–4**：TDD，测试覆盖"参数优先于前台""无障碍未开时返回 null 不误伤""包名不合法时返回 null"。
**Step 5**：commit `feat(bridge): resolve per-app target for capability gating`。

---

## Task 3: 两个 Store（应用名单、解除限制）

**Objective:** 持久化用户选择，轮询式暴露 `StateFlow` 供 UI 与 gate 读取（每请求现读，和现有 `allowedCapabilityGroups` 一致）。

**Files:**
- Create: `.../bridge/security/AppRulesStore.kt`、`.../bridge/security/UnlockStore.kt`
- Test: `app/src/test/java/com/openclaw/assistant/bridge/security/AppRulesStoreTest.kt`（用 `SharedPreferences` 的 fake 实现，**不要**碰 `SettingsRepository`，见 AGENT.md §5）

关键点：

- `AppRulesStore` 存 `pkg → rule` 的 JSON 字符串（`kotlinx.serialization`），另有 `alwaysAllowEnabled: Boolean`（总开关：关掉时 `ALWAYS_ALLOW` 一律按 `NORMAL` 处理）。
- 首次初始化播种子：

```kotlin
private val SEED_OFF = setOf(
    "com.eg.android.AlipayGphone", "com.unionpay", "com.icbc", "cmb.pb",
    "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci",
    "com.jd.jrapp", "com.webank.wemoney",
)
private val SEED_STEP_UP = setOf("com.tencent.mm")
```

  种子只在"从未写过"时写入，用户删掉不会再回来。
- `UnlockStore.set(unlock, on, source)`；`source` 记录是长按开关还是迁移，进操作记录。
- 同一任务里把 `MobileBridgeConfig.approvalMode`（类型 `BridgeApprovalMode`）改名为 `confirmMode`（类型 `ConfirmMode`），旧值读时映射：`TRUSTED` → `ALWAYS_ALLOW`，`CONFIRM_MEDIUM_HIGH` / `ALWAYS_CONFIRM` → `ASK_EVERY_TIME`。改完顺手改掉 `MobileBridgeServer.requiresApproval`、`MobileBridgeHandler` 的状态上报和 `MobileBridgeSettingsActivity` 的 chips（UI 文案在 Task 10 再收敛）。这样 Task 5 才能直接读 `config.confirmMode`。

**验证**：`AppRulesStoreTest` 断言种子、覆盖、总开关生效、JSON 往返不丢规则。

---

## Task 4: 打码器

**Objective:** 出口文本统一过一遍，卡号身份证永远打码，密码框按解锁开关决定。

**Files:**
- Create: `.../bridge/security/Masker.kt`、`.../bridge/security/SensitiveSurfaces.kt`
- Test: `app/src/test/java/com/openclaw/assistant/bridge/security/MaskerTest.kt`

```kotlin
object Masker {
    private val CARD = Regex("""(?<!\d)(\d[ -]?){12,18}\d(?!\d)""")
    private val ID = Regex("""(?<![\dXx])(\d{6})(\d{8})(\d{3}[\dXx])(?![\dXx])""")

    fun mask(text: String): String = text.replace(CARD) { m -> keepEnds(m.value.filter { it.isDigit() }, 4, 4) }
        .replace(ID) { m -> "${m.groupValues[1].take(3)}********${m.groupValues[3].takeLast(4)}" }

    fun maskPassword(text: String?): String = if (text.isNullOrBlank()) "" else "•".repeat(text.length.coerceAtMost(16))
}
```

接入点（Task 8）：`ScreenSnapshot.fromRoots` 里 `text`/`desc` 先过 `Masker.mask`，`isPassword` 节点过 `maskPassword`（未解 `SECRETS` 时）；`computeHash()` 用打码后的文本，保证 `screen.diff` 与读屏一致。

**验证**：13/16/19 位卡号带空格、18 位身份证末位 X、纯数字订单号不误伤（长度 < 13）、`maskPassword` 空值与超长。

---

## Task 5: 接入 dispatch（唯一的执行闸）

**Objective:** 桥接每次 `/execute` 先过 `decide()`，再决定是否弹审批。

**Files:**
- Modify: `.../bridge/MobileBridgeServer.kt:235-296`（`executeResponse` + `approvalGate` + `requiresApproval`）
- Test: `app/src/test/java/com/openclaw/assistant/bridge/MobileBridgeServerTest.kt`（扩展现有）

改动要点：

```kotlin
val target = CapabilityTarget.resolve(context, cap.name, arguments)
val decision = SecurityPolicy.decide(
    capability = cap.name, risk = cap.riskLevel, target = target,
    policy = PolicySnapshot(config.confirmMode.value, appRules.snapshot(), unlocks.snapshot()),
    grants = object : GrantQuery {
        override fun hasTaskGrant(capability: String, pkg: String?) =
            BridgeGrants.hasTaskGrant(capability, pkg)
    },
)
if (!decision.allow) {
    BridgeActivityLog.record(context, capabilityName, cap.riskLevel, "blocked", decision.reason, decision.targetPackage)
    return HttpResponse(200, errorEnvelope(requestId, decision.reason, decision.reason))
}
if (decision.needsApproval) { /* 原 approvalGate，但把 decision 传给 UI */ }
```

`approvalGate` 签名加 `decision: Decision`，用于卡片显示"因为这是微信 / 因为这是系统设置页 / 已解除限制"。

**删除**旧的 `requiresApproval(cap)` 风险阈值逻辑，`BridgeApprovalMode` 改名 `ConfirmMode` 并迁到 `security/`（保留旧枚举名读迁移，见 Task 10）。

**Step 1–4**：先写 3 条断言（OFF 拒绝且不弹审批 / STEP_UP + TRUSTED 仍弹 / LOW 不弹），跑红再实现。

---

## Task 6: 审批卡三档 + 本轮 grant

**Objective:** 卡片从五种按钮收敛成三档，长 TTL 收到"更多授权"里。

**Files:**
- Modify: `.../bridge/BridgeApprovalActivity.kt`
- Modify: `.../bridge/grants/BridgeGrants.kt`（`grant(scope, ttlMs)`、`hasTaskGrant`、`clearTaskGrants`）
- Modify: `.../bridge/BridgeApprovalRegistry.kt`（`await` 返回 `ApprovalScope` 而不是 `Boolean`）

```kotlin
enum class ApprovalScope { ONCE, TASK, DENY }
```

- `TASK` → `BridgeGrants.grantTask(cap, pkg, windowMs = 10 * 60_000L)`；键 `task:<cap>|<pkg>`。
- 破坏性动词：只允许 `ONCE` / `TASK`，"更多授权"里的 1 小时 / 始终允许按钮隐藏（沿用现状）。
- 卡片底部一行状态：`目标应用：com.tencent.mm · 规则：每一步都确认 · 已解除限制：无`。
- 取消 = 只作废这一条，返回 `approval_denied`（错误码不变，Hermes 侧不用改）。

**测试**：`BridgeGrantsTest` 补 task 窗口过期、`clearTaskGrants`、同名不同包互不影响；`MobileBridgeServerTest` 断言第二次同能力命中 task grant 不再调用 fake gate。

---

## Task 7: 解除限制的开启动作

**Objective:** 三项开关，开启需长按 1.5 s，有 pending 审批时禁用。

**Files:**
- Create: `.../ui/bridge/UnlockSettingsActivity.kt`
- Modify: `.../bridge/security/UnlockStore.kt`

- 长按实现：`Box(Modifier.pointerInput { detectTapGestures(onLongPress = { ... }, onPress = { /* 画进度环 */ }) })`，进度用 `Animatable(0f).animateTo(1f, tween(1500))`。
- 禁用判据：`BridgeApprovalRegistry.pendingIds().isNotEmpty()` → 整页只读并显示"正在处理审批，结束后可修改"。
- 免责声明文案（新增字符串）：开启后 AI 可在无人确认时操作系统设置 / 读取密码框内容 / 操作本应用，风险自担。
- 每次变更写 `BridgeActivityLog.record(context, "unlock.${unlock.name}", null, "changed", if (on) "on" else "off")`。

---

## Task 8: 打码接入读屏与截图

**Files:**
- Modify: `.../bridge/accessibility/AgentVoiceAccessibilityService.kt:255-293`（`walk()` 里取 `node.isPassword`，存进 `ScreenNodeSnapshot`）
- Modify: `.../bridge/security/Masker.kt`（接入点）
- Test: `app/src/test/java/com/openclaw/assistant/bridge/accessibility/ScreenSnapshotMaskingTest.kt`（用 `AccessibilityNodeInfo.obtain()` 构造树）

- `ScreenNodeSnapshot` 加 `isPassword: Boolean`，`fromRoots(fromRoots, mask = MaskOptions(unlockSecrets))`。
- `screen.screenshot` **不做像素打码**，README「已知限制」写明：截图里可能出现卡号，敏感场景请先关闭 `screen` 组。

---

## Task 9: 操作记录加字段与界面

**Files:**
- Modify: `.../bridge/BridgeActivityLog.kt`（`BridgeActivityEntry` 加 `targetPackage: String?`、`reason: String?`，旧记录反序列化用默认值）
- Modify: `.../ui/bridge/MobileBridgeSettingsActivity.kt`（安全卡片底部"最近操作"20 条 + 清空）

**测试**：旧 JSON 反序列化不崩（默认值），新记录字段落库。

---

## Task 10: 设置界面

**Files:**
- Modify: `.../ui/bridge/MobileBridgeSettingsActivity.kt:173-183`（`av_bridge_security_section` 卡片）
- Create: `.../ui/bridge/AppRulesActivity.kt`
- Modify: `.../bridge/MobileBridgeConfig.kt`（`approvalMode` → `confirmMode`，旧值 `TRUSTED`/`CONFIRM_MEDIUM_HIGH` 读时映射为 `ALWAYS_ALLOW`/`ASK_EVERY_TIME`）

安全卡片结构：

```
确认方式        [每次都询问] [本任务免确认] [始终免确认]
应用名单        9 个不碰 · 1 个每步确认            >
始终允许        关（不询问就向名单内应用发送内容）  >
解除限制        系统设置 · 密码 · 本应用 全部未开   >
最近操作        3 条 · 查看全部                     >
```

`AppRulesActivity`：搜索框 + 已列规则（三档 chips）+「添加应用」走已有 `apps.list` 的数据源（`PackageManager` MAIN/LAUNCHER），每行可移除。顶部一个总开关控制 `ALWAYS_ALLOW` 是否生效。

---

## Task 11: 迁移与提示

- `MobileBridgeConfig` 首次读到旧 `approvalMode` 时映射并写回新键，日志记一次 `migrated_from=TRUSTED`。
- 升级后首次打开「设置 → 移动桥接」，在安全卡片上方显示一次性提示："新增了应用名单 / 解除限制，默认拦下系统设置页与本应用" + 「去检查」按钮（点击后写 `securityNoticeShown=true`）。

---

## Task 12: 文案（10 语言，最后补）

**Objective:** 新增 26 条字符串补齐 10 个 `values-*`，覆盖率保持 100%。

文件：`app/src/main/res/values/strings.xml` + `values-{zh-rCN,zh-rTW,ja,ko,de,es,fr,hi,ru}/strings.xml`

键名草案：

```
av_sec_confirm_mode / av_sec_confirm_ask / av_sec_confirm_task / av_sec_confirm_always
av_sec_app_rules / av_sec_app_rules_summary / av_sec_always_allow / av_sec_always_allow_on / av_sec_always_allow_off
av_sec_unlock_title / av_sec_unlock_system / av_sec_unlock_secrets / av_sec_unlock_self
av_sec_unlock_hold / av_sec_unlock_disclaimer / av_sec_unlock_pending_busy
av_sec_rule_normal / av_sec_rule_step_up / av_sec_rule_off / av_sec_rule_always
av_sec_recent_actions / av_sec_recent_empty / av_sec_clear
av_appr_once / av_appr_task / av_appr_cancel / av_appr_more / av_appr_reason_prefix
av_err_app_blocked / av_err_system_surface_locked / av_err_self_surface_locked
```

**顺序**：实现阶段（Task 1–11）只改 `values/` 与 `values-zh-rCN`，其余 8 种语言在这个任务里一次性补齐；`DISPLAY_LANGUAGE_OPTIONS` 已列出的语言必须保持 100%，不能在未补齐时先放行。

**验证**：`./gradlew :app:compileStandardDebugResources` 通过（aapt2 会因 `'`、`\r` 报错，见 AGENT.md §6）。

---

## Task 13: 全量验证

```bash
./gradlew testStandardDebugUnitTest            # 单测（Robolectric）
./gradlew assembleStandardDebug                # 资源 + 编译
adb -s <设备局域网地址>:<端口> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

实机清单（adb + curl，每步记录实际返回码）：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 打开银行 app，`screen.tap` | `app_blocked`，操作记录有该包名 |
| 2 | 微信在前台，`screen.tap` | 弹审批卡，且"始终免确认"模式下也弹 |
| 3 | 系统设置页，`screen.tap` | `system_surface_locked`；长按开启解除限制后放行 |
| 4 | 密码输入框页面，`screen.find_nodes` | 该节点 text 为 `••••`；开启 SECRETS 后返回原文 |
| 5 | 审批卡「本轮允许」后再次同能力 | 10 分钟内不再弹；换另一个包名仍弹 |
| 6 | 桥接停止 → 重新开始，再调用 | 之前的本轮授权已失效 |
| 7 | 短信文本里的 16 位卡号 | 返回值已打码 |
| 8 | 通知栏审批 → 点通知 | 打开卡片，行为与前台一致 |
| 9 | 设置页修改确认方式 | 下一条请求立刻生效（不重启桥） |

---

## 风险与回归

1. **默认硬规则会拦下既有脚本**：原本 `TRUSTED` 下能操作设置页的自动化，升级后会被 `system_surface_locked` 拒绝。缓解：一次性提示 + 解除限制入口。
2. **打码影响 `screen.hash`**：打码在 hash 之前，`screen.diff` 语义保持一致；但依赖原文的脚本（读验证码）在未解 `SECRETS` 时会拿到 `••••`。已在 Task 8 的测试锁住。
3. **`AppRule.ALWAYS_ALLOW` 与组开关是两套口径**：组（`allowedCapabilityGroups`）决定"能力是否可见"，应用规则决定"这一下要不要问"。两者都不开才是真的不可用，UI 要在应用名单页说明这一点。
4. **路径 ① 不受影响**：网关原生调用仍默认全通。这是已知缺口，写进 README，不在本计划内修。
5. **10 语言文案**是本次最容易漏的收尾项，Task 12 单独成任务且以 `compileStandardDebugResources` 为完成判据。

## 不做的事

- 悬浮条 / 屏幕四周色带（需要 `SYSTEM_ALERT_WINDOW` + 常驻 overlay 服务）。
- "3 分钟无操作自动结束"（需要任务边界概念，等 Task 6 的窗口机制跑稳后再评估）。
- 截图像素级打码。
- `android_shell` 独立授权（我们没有该能力）。
- 改路径 ① 的准入。
