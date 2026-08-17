package com.visualspider.visualbrowser.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 候选列表项推断请求（M4-2 #32 / spec §D3）。
 *
 * <p>{@code clientWidth / clientHeight} = 客户端视口 CSS 像素；
 * 服务端经 {@link com.visualspider.visualbrowser.ViewportMapper#toRemote} 换算到远程视口坐标。
 * 越界（含 0 / null）由 mapper 返 null，服务端抛 {@link IllegalArgumentException}。
 */
public record InferRequest(
        @NotNull @Positive Integer x,
        @NotNull @Positive Integer y,
        @NotNull @Positive Integer clientWidth,
        @NotNull @Positive Integer clientHeight) {}