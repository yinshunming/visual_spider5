package com.visualspider.visualbrowser.api;

import com.visualspider.visualbrowser.ElementSummary;
import java.util.List;

public record ValidateSelectorsResponse(List<SelectorOutcome> outcomes) {

    public record SelectorOutcome(
            String selector,
            String type,
            boolean valid,
            int matchCount,
            String error,
            List<ElementSummary> matchedRanges
    ) {}
}
