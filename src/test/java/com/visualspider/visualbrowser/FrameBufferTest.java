package com.visualspider.visualbrowser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrameBufferTest {

    @Test
    void keepsOnlyLatestFrame() {
        FrameBuffer b = new FrameBuffer();
        b.push(frame(1));
        b.push(frame(2));
        b.push(frame(3));
        assertThat(b.drain()).containsExactly((byte) 3);
    }

    @Test
    void drainClearsBuffer() {
        FrameBuffer b = new FrameBuffer();
        b.push(frame(1));
        assertThat(b.drain()).containsExactly((byte) 1);
        assertThat(b.drain()).isNull();
        assertThat(b.hasFrame()).isFalse();
    }

    @Test
    void drainEmptyBufferReturnsNull() {
        assertThat(new FrameBuffer().drain()).isNull();
    }

    @Test
    void ignoresNullFrame() {
        FrameBuffer b = new FrameBuffer();
        b.push(null);
        assertThat(b.hasFrame()).isFalse();
    }

    private byte[] frame(int marker) {
        return new byte[]{(byte) marker};
    }
}
