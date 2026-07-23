package com.visualspider.visualbrowser.spi;

/**
 * 高级选择器编辑器 SPI 占位（M2-2 #18）。
 *
 * <p>后端会在该接口补充 {@link ElementSnapshot} / 候选评分契约；前端不通过 SPI 反向调用。
 */
public interface AdvancedSelectorEditor {

    /**
     * 选择模式或选择器校验得到的元素静态快照（不含运行时 ElementHandle）。
     * 字段均为可空字符串：tagName 必须存在。
     */
    record ElementSnapshot(
            String tagName,
            String id,
            String className) {

        public ElementSnapshot {
            if (tagName == null || tagName.isBlank()) {
                throw new IllegalArgumentException("tagName 不能为空");
            }
        }
    }
}
