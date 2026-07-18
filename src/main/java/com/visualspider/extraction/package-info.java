/**
 * extraction 模块。
 *
 * <p>本包承载 extraction 业务能力：单页字段来源（可见文本、属性、链接/图片 URL、
 * 当前页 URL）、字段清洗（首尾空白、正则提取、类型转换）、原始值/最终值预览与诊断。
 *
 * <p>M2/M3 启用（M2 完成单页字段模型与预览；M3 在单页运行中复用同一提取实现）；M0.5 仅完成包结构占位。
 *
 * <p>本包遵守 ADR-0003（按业务模块组织深模块）：
 * 仅通过稳定 interface 对外暴露能力；隐藏 DOM 查询、Playwright 元素重查、
 * 字段类型转换实现细节。
 */
package com.visualspider.extraction;