package com.visualspider.run.internal.testutil;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.task.domain.SelectorType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假 {@link DomState}：按 {@code (selector, type)} 查询内存节点表（M3-3 #25）。
 *
 * <p>支持按"调用次数 / 调用顺序"返回不同节点，方便单测覆盖"select 第 N 次返 0 匹配 / 多匹配 / 单匹配"。
 */
public class TestDomState implements DomState {

    private final String url;
    private final Map<String, List<Node>> nodesByKey = new LinkedHashMap<>();
    private final List<String> queryKeys = new ArrayList<>();

    public TestDomState(String url) {
        this.url = url;
    }

    public TestDomState(String url, List<Node> nodes) {
        this.url = url;
        nodesByKey.put("", nodes);
    }

    /** 按 (selector, type) 注册一组节点；缺省 key = "" 用于无差别 lookup。 */
    public TestDomState withNodes(String selector, SelectorType type, List<Node> nodes) {
        nodesByKey.put(keyOf(selector, type), nodes);
        return this;
    }

    public List<String> queryKeys() {
        return List.copyOf(queryKeys);
    }

    public int queryCount() {
        return queryKeys.size();
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public List<Node> query(String selector, SelectorType type) {
        String key = keyOf(selector, type);
        queryKeys.add(key);
        List<Node> hit = nodesByKey.get(key);
        if (hit != null) {
            return hit;
        }
        return nodesByKey.getOrDefault("", List.of());
    }

    private static String keyOf(String selector, SelectorType type) {
        return (type == null ? SelectorType.CSS : type).name() + "::" + (selector == null ? "" : selector);
    }
}
