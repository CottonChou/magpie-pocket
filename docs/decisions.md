# Magpie Pocket 架构与技术决策 (docs/decisions.md)

## 1. 架构与选型

- **UI 框架**：Kotlin 2.0 + Jetpack Compose + Material 3
- **最低兼容系统**：Android 7.0 (`minSdk = 24`)，目标与编译系统 `targetSdk = 34`, `compileSdk = 34`
- **并发与响应式**：Kotlin Coroutines + StateFlow / Flow + ViewModel
- **网络与序列化**：OkHttp 4.12.0 + kotlinx.serialization.json 1.6.3
- **安全与存储**：AndroidX Security Crypto (EncryptedSharedPreferences / Keystore) + Jetpack DataStore Preferences

## 2. 视觉材质 Liquid Glass 分层与降级

`GlassSurface` 将控制层与数据区域区分开：悬浮底栏、按钮与选择器采用透明色、高光和柔和阴影，数据面板保留清晰背景。所有文字与图标保持清晰，不对包含内容的整个节点施加模糊。

- `CLEAR`：按主题使用轻透色层。
- `TINTED`：提高色层不透明度。
- `SOLID`：使用不透明表面。

这是 Android 原生的玻璃风格实现，不声称实现光学折射或 Apple 系统材质。三档材质均支持最低 API 24。

## 3. 统计口径与算法实现

- **首响均值（TTFT Mean）**：默认采用加权算术均值 `ttftSumMs / ttftSampleCount`，不将均值标为中位数。
- **总 Token 归一化**：`inputUncached + output + cacheRead + cacheWrite`。上游输出中包含的 reasoning Token 不重复累加。
- **加权缓存命中率**：`sum(cacheRead) / sum(inputUncached + cacheRead + cacheWrite)`。分母为 0 时输出 null（界面显示“—”），而非错误记为 0%。
- **改道次数独立于终态**：`rerouteCount` 仅记录发生候选交接的次数；改道请求既可能最终成功（`成功 · 改道 1 次`），也可能最终失败（`失败 · 改道 2 次`）。
- **账号打码保护**：默认开启别名遮蔽，所有前台展示映射为 `账号 01` 或 `ad***n@domain`，明文凭证仅留在内存。

## 4. 演示模式 (Demo) 与真实服务模式 (Live) 物理隔离

- `DemoRepository` 与 `LiveMagpieRepository` 均实现统一接口 `MagpieRepository`。
- 未连接时不生成看似真实的假数据；Demo 模式下界面常驻“演示数据”胶囊标签。
- 无论离线还是接口缺失，均将错误诚实表达为产品状态，不编造虚假网关指标。


## 2026-10-22 UI reference alignment

The attached routing mockup is the visual baseline: cool white background with restrained lavender/blue ambient fields; a dark rounded bird mark; horizontal metric rules; a single opaque route canvas; pale lavender selection; compact decision/context rows; and one floating four-tab capsule with visible labels and thin outline icons. Native Android status/navigation areas remain native. Other screens share these tokens and surface rules.

Glass is rendered with translucent tint, a restrained highlight and soft shadow. This Android implementation does not implement optical refraction or claim Apple system material compliance. Data panels remain opaque and readable. A single request-detail host is shared across tabs; selecting a routing focus alone does not open it.

The implementation is verified against Android emulator screenshots, with shared outline icons, layout tokens and API-24-compatible timestamp handling.

### Bird artwork attribution

`BirdMark.kt` adapts Lucide's bird artwork (https://lucide.dev/icons/bird).

ISC License

Copyright (c) Lucide Contributors

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.


## 2026-10-22 Authentication and upstream compatibility

Connection validation and restored runtime sessions share MagpieSession rules. Each connection gets an isolated client; runtime reauthenticates with the encrypted saved Web Key before API loading or trace. A protected /api/state response is required; optional capabilities are claimed after actual probes, with404 degradation only. Authentication failures remain actionable until explicit retry or reconnection and cannot be erased by old trace emissions.

Current upstream response unions/aliases are parsed explicitly, including numeric IDs, nested route metadata, usage tiers, ledger rows and quota arrays. Quota percentages and fractional absolute counts are represented separately. Credentials and raw authenticated responses are excluded from source, fixtures and verification artifacts. See docs/verification.md for exact scope and production-login limitations.


## 2026-10-22 Local time and trace-driven routing

Request timestamps are parsed as RFC3339 instants and rendered in the device timezone; nanosecond fractions are normalized without changing the UTC instant. The Material date picker continues using UTC-encoded calendar dates. Generated metadata uses true UTC timestamps.

Trace snapshots expose normalized summaries and full details from the same retained cache. Live routing follows the selected group, while request-detail modals retain their own selection. Provider/account changes, retries and completion update the graph and evidence together. Epoch/sequence restarts clear stale live state, and deployments without explicit retained IDs use a bounded 60-route cache.

Motion is tied to real in-flight state. Completion feedback is finite and consumed when finished or leaving the canvas. App/system reduced motion, lifecycle and connection state gate animation. Historical inspection stays pinned until returning to live following.
