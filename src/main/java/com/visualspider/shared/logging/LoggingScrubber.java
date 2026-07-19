package com.visualspider.shared.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志敏感信息脱敏（spec §D8）。
 *
 * <p>对单行日志字符串应用一组正则规则：
 * <ul>
 *   <li>{@code password\s*[:=]\s*\S+} → {@code password=***}</li>
 *   <li>{@code Authorization:\s*\S+} → {@code Authorization: ***}</li>
 *   <li>{@code Set-Cookie:\s*\S+} → {@code Set-Cookie: ***}</li>
 *   <li>{@code XSRF-TOKEN\s*=\s*\S+} → {@code XSRF-TOKEN=***}</li>
 *   <li>JSONB 内 {@code "password": "..."} → {@code "password": "***"}</li>
 *   <li>JSONB 内 {@code "token": "..."} → {@code "token": "***"}</li>
 * </ul>
 *
 * <p>Spring Boot 启动时注册为 {@code logging.pattern.console} / {@code logging.pattern.file} 的后处理
 * 转换器（见 {@link LoggingPatternConfigurer}）。
 */
public final class LoggingScrubber {

    private static final Pattern PASSWORD_ASSIGN = Pattern.compile(
            "(?i)\\bpassword\\s*[:=]\\s*\\S+");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\bAuthorization:\\s*[^\\r\\n]+");
    private static final Pattern SET_COOKIE = Pattern.compile(
            "(?i)\\bSet-Cookie:\\s*\\S+");
    private static final Pattern XSRF_TOKEN = Pattern.compile(
            "(?i)\\bXSRF-TOKEN\\s*=\\s*\\S+");
    private static final Pattern JSON_PASSWORD = Pattern.compile(
            "(\"password\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_TOKEN = Pattern.compile(
            "(\"token\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final String REPLACEMENT_PASSWORD = "password=***";
    private static final String REPLACEMENT_JSON = "$1\"***\"";

    private LoggingScrubber() {
    }

    public static String scrub(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        result = maskPasswordAssign(result);
        result = maskAuthorization(result);
        result = maskSetCookie(result);
        result = maskXsrfToken(result);
        result = maskJsonPassword(result);
        result = maskJsonToken(result);
        return result;
    }

    private static String maskPasswordAssign(String s) {
        Matcher m = PASSWORD_ASSIGN.matcher(s);
        return m.replaceAll(Matcher.quoteReplacement(REPLACEMENT_PASSWORD));
    }

    private static String maskAuthorization(String s) {
        Matcher m = AUTHORIZATION.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("Authorization: ***"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskSetCookie(String s) {
        Matcher m = SET_COOKIE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("Set-Cookie: ***"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskXsrfToken(String s) {
        Matcher m = XSRF_TOKEN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("XSRF-TOKEN=***"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskJsonPassword(String s) {
        return JSON_PASSWORD.matcher(s).replaceAll(REPLACEMENT_JSON);
    }

    private static String maskJsonToken(String s) {
        return JSON_TOKEN.matcher(s).replaceAll(REPLACEMENT_JSON);
    }
}
