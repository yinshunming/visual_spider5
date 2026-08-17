package com.visualspider.visualbrowser.spi;

/**
 * lane 借用凭据（M2-1 #17）：lane 持 Playwright + Browser，lease 持本会话独立
 * Context + Page。Lease 归还后 lane 可被下一个 session 借用。
 *
 * <p>Lease 是 session 内部状态，外部代码不直接操作 Context/Page；
 * lane 内部 SPI 实现负责绑定、释放、关闭顺序。
 */
public interface Lease extends AutoCloseable {

    /** 关联的 BrowserLane 名（仅供诊断日志，不依赖外部使用）。 */
    String laneName();

    /** lease 是否仍生效。归还或关闭后为 false。 */
    boolean isOpen();

    @Override
    void close();
}
