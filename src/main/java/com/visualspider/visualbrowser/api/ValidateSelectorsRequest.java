package com.visualspider.visualbrowser.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ValidateSelectorsRequest(@NotEmpty List<SelectorEntry> selectors) {

    public record SelectorEntry(String type, String selector) {
    }
}
