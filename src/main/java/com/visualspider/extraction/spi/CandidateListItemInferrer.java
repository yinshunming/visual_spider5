package com.visualspider.extraction.spi;

/**
 * 候选列表项推断器 SPI（M4 spec §D3）。
 *
 * <p>输入 {@link DomSnapshot}（由 Playwright lane 上 {@code evaluate(...)} 构造）；
 * 输出 {@link InferredCandidateListItem}（含 rule + score + ancestors + 替代候选）。
 *
 * <p>{@link #adjustAncestor(DomSnapshot, Direction)} 在 UI 上调（上一级 / 下一级 ancestor），
 * 重跑评分；纯函数不修改 DomSnapshot 内部状态。
 */
public interface CandidateListItemInferrer {

    InferredCandidateListItem infer(DomSnapshot clicked);

    InferredCandidateListItem adjustAncestor(DomSnapshot clicked, Direction direction);

    enum Direction {
        UP, DOWN
    }
}
