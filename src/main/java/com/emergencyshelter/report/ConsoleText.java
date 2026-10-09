package com.emergencyshelter.report;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 把报告文字渲染成服务端控制台用的字符串。
 * 专用服务端默认只有英文；系统语言是中文时用本模组自带的中文。
 */
public final class ConsoleText {
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s");
    private static Map<String, String> chinese;

    private ConsoleText() {
    }

    public static String render(Component component) {
        StringBuilder sb = new StringBuilder();
        append(sb, component);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            String pattern = lookup(translatable.getKey(), translatable.getFallback());
            Object[] args = translatable.getArgs();
            Matcher m = ARG.matcher(pattern);
            int last = 0;
            int next = 0;
            while (m.find()) {
                sb.append(pattern, last, m.start());
                int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
                if (index >= 0 && index < args.length) {
                    Object arg = args[index];
                    if (arg instanceof Component c) {
                        append(sb, c);
                    } else {
                        sb.append(arg);
                    }
                }
                last = m.end();
            }
            sb.append(pattern.substring(last));
        } else {
            component.getContents().visit(text -> {
                sb.append(text);
                return java.util.Optional.empty();
            });
        }
        for (Component sibling : component.getSiblings()) {
            append(sb, sibling);
        }
    }

    private static String lookup(String key, String fallback) {
        if (Locale.getDefault().getLanguage().equals("zh")) {
            String zh = chinese().get(key);
            if (zh != null) {
                return zh;
            }
        }
        Language language = Language.getInstance();
        if (language.has(key)) {
            return language.getOrDefault(key);
        }
        return fallback != null ? fallback : key;
    }

    private static synchronized Map<String, String> chinese() {
        if (chinese == null) {
            chinese = Map.of();
            try (InputStream in = ConsoleText.class.getResourceAsStream("/assets/emergencyshelter/lang/zh_cn.json")) {
                if (in != null) {
                    chinese = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                            new TypeToken<Map<String, String>>() {}.getType());
                }
            } catch (Exception e) {
                EmergencyShelter.LOGGER.debug("无法读取中文语言文件", e);
            }
        }
        return chinese;
    }
}
