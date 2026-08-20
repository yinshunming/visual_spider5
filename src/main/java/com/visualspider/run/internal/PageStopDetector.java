package com.visualspider.run.internal;

import com.microsoft.playwright.Page;
import com.visualspider.run.spi.StopReason;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行期停止检测器（M5-5 / issue #43 / spec §D10）。
 *
 * <p>三类触发条件（检测即停，调用 {@link StopReason} 既有枚举复用 M3 占位）：
 * <ul>
 *   <li>HTTP 429：任一响应 429 -> 立即停止 {@link StopReason#HTTP_429}</li>
 *   <li>持续 403：滑动窗口连续 {@value #FORBIDDEN_WINDOW} 个 403 -> 停止
 *       {@link StopReason#HTTP_403}</li>
 *   <li>验证码：DOM 扫描（{@code reCAPTCHA} iframe / {@code hCaptcha} iframe /
 *       {@code input[name=captcha]} / {@code [class*=captcha]} /
 *       {@code [id*=captcha]}） + 文本含 captcha / 验证码 / verify you are human /
 *       i'm not a robot -> 停止 {@link StopReason#CAPTCHA}</li>
 * </ul>
 *
 * <p>由 {@link DefaultRunPageHandle} 在每次 {@code page.navigate} 后调用
 * {@link #recordResponse}（HTTP 状态码）与 {@link #checkCaptcha}（DOM 扫描）。
 *
 * <p>不切换 UA / 指纹 / 代理（roadmap §9 不做）；检测触发后由调用方负责
 * {@code run.markTerminal} + 写 {@code run_event}。
 */
final class PageStopDetector {

    private static final Logger LOG = LoggerFactory.getLogger(PageStopDetector.class);

    /** 滑动窗口长度：连续 N 个 403 触发停止（spec §D10；M6 入 system_setting 时只改读取路径）。 */
    static final int FORBIDDEN_WINDOW = 3;

    /** 验证码 DOM 扫描选择器（spec §D10）。 */
    static final List<String> CAPTCHA_SELECTORS = List.of(
            "iframe[src*='recaptcha']",
            "iframe[src*='hcaptcha']",
            "input[name='captcha']",
            "[class*='captcha']",
            "[id*='captcha']");

    /** 验证码文本扫描 token（spec §D10：大小写不敏感）。 */
    static final List<String> CAPTCHA_TEXT_TOKENS = List.of(
            "captcha", "验证码", "verify you are human", "i'm not a robot");

    private final Deque<Boolean> recentForbidden = new ArrayDeque<>(FORBIDDEN_WINDOW);
    private final Consumer<StopReason> onStop;

    PageStopDetector(Consumer<StopReason> onStop) {
        this.onStop = onStop;
    }

    /**
     * 记录单次响应的 HTTP 状态码；429 / 滑动窗口 403 触发 {@code onStop}。
     *
     * @param status HTTP 响应码（0 表示非 HTTP 场景，跳过）
     */
    void recordResponse(int status) {
        if (status == 429) {
            LOG.info("PageStopDetector: HTTP 429 -> stop");
            onStop.accept(StopReason.HTTP_429);
            return;
        }
        if (status == 403) {
            recentForbidden.addLast(Boolean.TRUE);
            if (recentForbidden.size() > FORBIDDEN_WINDOW) {
                recentForbidden.pollFirst();
            }
            if (recentForbidden.size() == FORBIDDEN_WINDOW
                    && recentForbidden.stream().allMatch(b -> b)) {
                LOG.info("PageStopDetector: sliding-window {} x 403 -> stop", FORBIDDEN_WINDOW);
                onStop.accept(StopReason.HTTP_403);
            }
            return;
        }
        // 任何非 403 都打破连续 403 窗口（spec §D10 滑动窗口语义）。
        if (!recentForbidden.isEmpty()) {
            recentForbidden.pollLast();
        }
    }

    /** 显式重置滑动窗口（spec §D10：连续 N 个 403 才触发；任一 2xx 立刻清空）。 */
    void resetForbiddenWindow() {
        recentForbidden.clear();
    }

    /**
     * 扫描页面 DOM 验证码特征（选择器 + 文本）；命中触发 {@code CAPTCHA}。
     *
     * <p>必须在 lane 线程内调用（直接使用 Playwright Page）；调用方负责 thread 亲和。
     */
    void checkCaptcha(Page page) {
        if (page == null) {
            return;
        }
        for (String sel : CAPTCHA_SELECTORS) {
            try {
                Object el = page.querySelector(sel);
                if (el != null) {
                    LOG.info("PageStopDetector: captcha selector matched sel={}", sel);
                    onStop.accept(StopReason.CAPTCHA);
                    return;
                }
            } catch (RuntimeException ignored) {
                // 选择器语法错误 / 元素查询异常不阻断扫描
            }
        }
        try {
            String body = page.innerText("body");
            if (body != null) {
                String lower = body.toLowerCase(Locale.ROOT);
                for (String token : CAPTCHA_TEXT_TOKENS) {
                    if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                        LOG.info("PageStopDetector: captcha text token matched token={}", token);
                        onStop.accept(StopReason.CAPTCHA);
                        return;
                    }
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("PageStopDetector: body innerText failed: {}", ex.getMessage());
        }
    }
}