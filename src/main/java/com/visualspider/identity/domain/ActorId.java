package com.visualspider.identity.domain;

/**
 * 身份标识：其它模块接收的稳定身份载体。
 *
 * <p>M1 spec §D3：仅暴露 {@code long value}；不暴露 username/role。
 * 由 {@code identity} 模块隐藏 SecurityContext 访问，其它模块直接接收 {@code ActorId}。
 */
public record ActorId(long value) {

    public ActorId {
        if (value <= 0) {
            throw new IllegalArgumentException("ActorId.value 必须 > 0；got " + value);
        }
    }
}
