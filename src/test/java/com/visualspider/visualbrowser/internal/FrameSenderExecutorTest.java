package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FrameSenderExecutorTest {

    @Test
    void destroyStopsRunningSenders() throws Exception {
        FrameSenderExecutor executor = new FrameSenderExecutor(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch interruptSeen = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                start.countDown();
                Thread.sleep(60_000);
            } catch (InterruptedException ex) {
                wasInterrupted.set(true);
                interruptSeen.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();

        executor.shutdown();

        assertThat(interruptSeen.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(wasInterrupted).isTrue();
    }

    @Test
    void submitAfterShutdownIsRejected() {
        FrameSenderExecutor executor = new FrameSenderExecutor(1);
        executor.shutdown();
        assertThatThrownBy(() -> executor.submit(() -> {}))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }
}
