package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputSequencerTest {

    @Test
    void acceptsStrictlyIncreasingSequence() {
        InputSequencer s = new InputSequencer();
        assertThat(s.accept(1)).isTrue();
        assertThat(s.accept(2)).isTrue();
        assertThat(s.accept(5)).isTrue();
        assertThat(s.lastSequence()).isEqualTo(5);
    }

    @Test
    void rejectsDuplicateAndOlder() {
        InputSequencer s = new InputSequencer();
        s.accept(10);
        assertThat(s.accept(10)).isFalse();
        assertThat(s.accept(5)).isFalse();
        assertThat(s.lastSequence()).isEqualTo(10);
    }

    @Test
    void rejectsZeroAndNegativeInitially() {
        InputSequencer s = new InputSequencer();
        assertThat(s.accept(0)).isFalse();
        assertThat(s.accept(-1)).isFalse();
        assertThat(s.accept(1)).isTrue();
    }
}
