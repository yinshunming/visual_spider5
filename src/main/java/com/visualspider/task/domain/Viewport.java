package com.visualspider.task.domain;

/**
 * 视口。M1 固定 1280x720；其它值 M1 拒绝并提示 M2 启用（spec §D4）。
 */
public record Viewport(int width, int height) {

    public static final Viewport DEFAULT = new Viewport(1280, 720);
}
