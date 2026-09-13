# Visual Spider 5 — LAN 真机延迟采样

> 本文档是 M0 / M7 决策门延后项的离线验证流程。
> **Agent 不执行**（必须在物理 LAN 第二台 PC 上手工跑）；采集人员/部署者跑完后把 20+20 次原始数值贴回 [`release-notes-v0.1.1.md`](./release-notes-v0.1.1.md) §3.4 对应段落或独立 perf issue。
> 目标：点击 / 滚动命令发出到下一帧到达的 RTT 中位数 ≤ 500ms。

## 1. 前置

- 一台运行 visual-spider5 的 Linux/Windows 服务器（部署形态见 [`windows.md`](./windows.md) / [`linux.md`](./linux.md)）。
- 一台同 LAN 的 PC，浏览器打开配置会话页面（采集人员身份登录）。
- 帧通道使用既有 WS 协议；每条命令的 `serverTimeMs` → 下一帧 `clientTimeMs` 的差即为 RTT。
- 不在远程浏览器本身（第一台）采样 — VNC / RDP / Chromium remote debug 都加噪声，必须用第二台独立 PC 浏览器。

## 2. 采样脚本（粘到浏览器 DevTools Console，配置会话打开后）

```javascript
(function () {
  'use strict';
  if (!window.__VS5_WS) {
    console.error('[LAN-RTT] 未找到帧 WebSocket；请在配置会话页面运行');
    return;
  }
  var ws = window.__VS5_WS;
  if (!ws.__baselineAttached) {
    ws.addEventListener('message', function (ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (!msg || !msg.frameSeq || !msg.serverTimeMs) return;
      var lastCmd = window.__VS5_LAST_CMD;
      if (!lastCmd) return;
      var rtt = msg.serverTimeMs - lastCmd.clientTimeMs;
      console.log('[LAN-RTT] ' + lastCmd.kind + ' seq=' + msg.frameSeq + ' rtt=' + rtt + 'ms');
      window.__VS5_RTTS.push(rtt);
      window.__VS5_LAST_CMD = null;
    });
    ws.__baselineAttached = true;
  }
  window.__VS5_RTTS = window.__VS5_RTTS || [];
  window.__VS5_LAST_CMD = null;
  console.log('[LAN-RTT] 就绪；sendClick 与 sendScroll 函数已注册（执行 20+20 次后调用 summary）');
})();

function sendClick() {
  if (!window.__VS5_WS) { console.error('no ws'); return; }
  var t = Date.now();
  window.__VS5_LAST_CMD = { kind: 'click', clientTimeMs: t };
  window.__VS5_WS.send(JSON.stringify({ type: 'CLICK', x: 200, y: 200, clientTimeMs: t }));
}

function sendScroll() {
  if (!window.__VS5_WS) { console.error('no ws'); return; }
  var t = Date.now();
  window.__VS5_LAST_CMD = { kind: 'scroll', clientTimeMs: t };
  window.__VS5_WS.send(JSON.stringify({ type: 'SCROLL', dx: 0, dy: 200, clientTimeMs: t }));
}

function summary() {
  var arr = window.__VS5_RTTS || [];
  if (arr.length === 0) { console.log('no samples'); return; }
  var sorted = arr.slice().sort(function (a, b) { return a - b; });
  var median = sorted[Math.floor(sorted.length / 2)];
  var p95 = sorted[Math.floor(sorted.length * 0.95)];
  var max = sorted[sorted.length - 1];
  console.log('[LAN-RTT] n=' + arr.length + ' median=' + median + 'ms p95=' + p95 + 'ms max=' + max + 'ms');
  console.log('[LAN-RTT] raw=' + JSON.stringify(arr));
  console.log('[LAN-RTT] 目标 median ≤ 500ms；' + (median <= 500 ? '达标' : '未达标，按性能 bug 开 issue'));
}
```

## 3. 使用步骤

1. 物理 LAN 第二台 PC 浏览器打开 `http://<server>:8080/`，采集人员账号登录。
2. 打开一个配置会话（任一任务）。
3. F12 打开 DevTools → Console → 粘贴"采样脚本"。
4. 在远程页面任意位置连续执行 `sendClick()` 20 次（每次等下一帧到达再执行下一次）。
5. 连续执行 `sendScroll()` 20 次（同样等帧）。
6. 执行 `summary()` 打印中位数 / p95 / max / 原始数组。
7. 复制原始数组贴回 `release-notes-v0.1.1.md §3.4` "原始数值"段或独立 perf issue。

## 4. 判定

- `median ≤ 500ms` → **达标**，`release-notes-v0.1.1.md §3.4` 标注 "已验收"。
- `median > 500ms` → **未达标**，按性能 bug 开独立 issue（不在 M7/M8 发布工单内修复）；`release-notes-v0.1.1.md §3.4` 标注 "未达标，bug #&lt;n&gt;"。

## 5. 失败时动作（未达标 → perf issue 模板）

```markdown
## Title
LAN 帧 RTT 中位数 > 500ms（v0.1.1 LAN 验证）

## 现象
- 部署版本：v0.1.1
- 部署平台：Windows / Linux
- 物理 LAN 拓扑：（同交换机 / 跨路由器 / Wi-Fi）
- 第二台 PC 浏览器 / OS：
- 配置会话目标 URL：

## 实测
- click n=20 median=___ms p95=___ms max=___ms
- scroll n=20 median=___ms p95=___ms max=___ms
- raw click 数组：[…]
- raw scroll 数组：[…]

## 已排除
- [ ] 第二台 PC 浏览器直连服务器（非 RDP / VNC / Chromium remote debug）
- [ ] 服务器无显著 CPU / 内存压力
- [ ] 远程页面无大量同步 JS 阻塞

## 期望
- median ≤ 500ms（M0 决策门）
```

## 6. 约束

- 不要在远程浏览器本身（第一台）采样 — VNC / RDP / Chromium remote debug 都加噪声，必须用第二台独立 PC 浏览器。
- 控制台采样脚本仅供本里程碑验收用，不进入产品代码。
- 帧序号与时间戳协议字段以浏览器 devtools 实测为准：本采样脚本假设每帧 WS 消息携带 `frameSeq` + `serverTimeMs`（与 [`docs/specs/m2.md`](../specs/m2.md) §D4 WebSocket 帧通道同源），实际字段名以配置会话页 WS 消息为准，必要时按实样微调脚本。
- 本文档由 [`docs/specs/m8.md`](../specs/m8.md) D1 落地；旧脚本草稿 `lan-latency-sample.txt` 已在 M8-1 中删除（[`docs/roadmap.md`](../roadmap.md) §11 LAN 退出标准 `[x]` + 理由行）。
