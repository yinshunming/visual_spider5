package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** M2 基础 URL 策略：只校验协议和主机名语法，完整 SSRF 防护留到 M6。 */
@Component
public final class BasicTargetUrlPolicy implements TargetUrlPolicy {

    private static final Pattern HOST_LABEL =
            Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");

    @Override
    public void validate(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                        || scheme.toLowerCase(Locale.ROOT).equals("https"))
                || !isValidHost(host)) {
            throw new InvalidTargetUrlException();
        }
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidTargetUrlException();
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTargetUrlException();
        }
    }

    private boolean isValidHost(String host) {
        if (host == null || host.isBlank() || host.length() > 253) {
            return false;
        }
        String normalized = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (normalized.isEmpty()) {
            return false;
        }
        for (String label : normalized.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }
}
