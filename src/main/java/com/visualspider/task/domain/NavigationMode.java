package com.visualspider.task.domain;

/**
 * 翻页 / 加载更多导航模式（M5 spec §D1）。
 *
 * <ul>
 *   <li>{@link #NEXT_PAGE}：点击后 URL 变化，内容列表全替换；
 *       重复页保护用 URL + list-item hash 双重比对</li>
 *   <li>{@link #LOAD_MORE}：点击后 URL 不变，list-item 数量增长；
 *       "无新增"判定（连续 2 次点击后列表项数不增长即停止）</li>
 * </ul>
 */
public enum NavigationMode {
    /** 点击后 URL 变化，内容列表全替换；重复页保护用 URL + list-item hash 双重比对。 */
    NEXT_PAGE,
    /** 点击后 URL 不变，list-item 数量增长；"无新增"判定。 */
    LOAD_MORE
}
