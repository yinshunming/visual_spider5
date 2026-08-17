/**
 * visualbrowser 模块。
 *
 * <p>本包承载 visualbrowser 业务能力：远程浏览器 lane、配置会话生命周期、
 * 浏览/选择模式、坐标命中、CSS/XPath 候选生成与校验、画面/输入协议骨架。
 *
 * <p>由 M0 spike 升入。M0.5 仅完成包结构占位（M0 spike 19 个生产类在 M0.5-T2
 * 迁入此包，原 spike 单包清空）；M2 起进入产品化（按 AGENTS "一用户一配置会话"、
 * "WebSocket 身份与所有权"等约束加固）。
 *
 * <p>本包遵守 ADR-0003（按业务模块组织深模块）：
 * 仅通过稳定 interface 对外暴露能力；隐藏 Playwright 对象、线程亲和性、
 * 浏览器 lane 与帧通道等实现细节，禁止泄漏到模块 interface。
 */
package com.visualspider.visualbrowser;