package com.visualspider.identity.domain;

/**
 * 角色：sealed 类型确保穷尽性。
 *
 * <p>首版仅 {@code Admin}（全局可见）与 {@code Collector}（仅自己资源）两种角色。
 * 自定义角色、权限组等延后到产品 §4 声明的不做项。
 */
public sealed interface ActorRole permits ActorRole.Admin, ActorRole.Collector {

    record Admin() implements ActorRole {}

    record Collector() implements ActorRole {}
}
