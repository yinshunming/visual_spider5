package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewportMapperTest {

    @Test
    void mapsClientToRemoteProportionally() {
        // 客户端 640×360（远程一半），点击中心 -> 远程中心
        assertThat(ViewportMapper.toRemote(320, 180, 640, 360)).containsExactly(640, 360);
    }

    @Test
    void differentClientSizeSameLogicalPointMapsToSameRemote() {
        // 同一逻辑比例点在不同客户端尺寸下映射到同一远程坐标
        int[] a = ViewportMapper.toRemote(100, 50, 200, 100);
        int[] b = ViewportMapper.toRemote(200, 100, 400, 200);
        assertThat(a).containsExactly(b);
    }

    @Test
    void clampsBottomRightCorner() {
        // 右下角坐标换算后钳制到 [0, REMOTE-1]
        assertThat(ViewportMapper.toRemote(1280, 720, 1280, 720)).containsExactly(1279, 719);
    }

    @Test
    void rejectsNonPositiveClientSize() {
        assertThat(ViewportMapper.toRemote(10, 10, 0, 0)).isNull();
        assertThat(ViewportMapper.toRemote(10, 10, -1, 100)).isNull();
    }

    @Test
    void rejectsOutOfBoundsClientCoord() {
        assertThat(ViewportMapper.toRemote(2000, 10, 1000, 1000)).isNull();
        assertThat(ViewportMapper.toRemote(-1, 10, 1000, 1000)).isNull();
    }
}
