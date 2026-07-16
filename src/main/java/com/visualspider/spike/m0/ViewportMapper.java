package com.visualspider.spike.m0;

/**
 * 客户端显示坐标 -&gt; 远程视口坐标换算。远程视口固定 1280×720，客户端按任意尺寸等比显示，
 * 输入坐标按显示尺寸换算回远程视口。越界或非法尺寸返回 null（命令应被拒绝）。
 */
public final class ViewportMapper {
    public static final int REMOTE_WIDTH = 1280;
    public static final int REMOTE_HEIGHT = 720;

    private ViewportMapper() {}

    /**
     * 把客户端显示坐标换算为远程视口坐标。
     *
     * @return {remoteX, remoteY}；客户端尺寸非正或坐标越界时返回 null
     */
    public static int[] toRemote(int clientX, int clientY, int clientWidth, int clientHeight) {
        if (clientWidth <= 0 || clientHeight <= 0) {
            return null;
        }
        if (clientX < 0 || clientX > clientWidth || clientY < 0 || clientY > clientHeight) {
            return null;
        }
        int rx = (int) Math.floor((double) clientX * REMOTE_WIDTH / clientWidth);
        int ry = (int) Math.floor((double) clientY * REMOTE_HEIGHT / clientHeight);
        if (rx < 0) {
            rx = 0;
        }
        if (rx >= REMOTE_WIDTH) {
            rx = REMOTE_WIDTH - 1;
        }
        if (ry < 0) {
            ry = 0;
        }
        if (ry >= REMOTE_HEIGHT) {
            ry = REMOTE_HEIGHT - 1;
        }
        return new int[]{rx, ry};
    }
}
