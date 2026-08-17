/**
 * identity 模块。
 *
 * <p>本包承载 identity 业务能力：账号模型、登录/退出、密码重置、
 * 管理员/采集人员权限与所有权检查。
 *
 * <p>M1 启用；M0.5 仅完成包结构占位。
 *
 * <p>本包遵守 ADR-0003（按业务模块组织深模块）：
 * 仅通过稳定 interface 对外暴露能力；隐藏密码哈希、Spring Security
 * 会话/CSRF 配置、HttpOnly/SameSite Cookie 等实现细节。
 */
package com.visualspider.identity;