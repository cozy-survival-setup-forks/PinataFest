package dev.pinatafest.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets lang.yml keep working with the older {@code &a} and {@code &#RRGGBB} codes by turning
 * them into MiniMessage tags before the text is parsed.
 */
public final class Legacy {

    private static final Pattern CODE = Pattern.compile(
            "&#([0-9a-fA-F]{6})|&x((?:&[0-9a-fA-F]){6})|&([0-9a-fk-orA-FK-OR])");

    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"), Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    private Legacy() {
    }

    public static String toMiniMessage(String input) {
        if (input.indexOf('&') < 0) {
            return input;
        }
        final Matcher matcher = CODE.matcher(input);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String tag;
            if (matcher.group(1) != null) {
                tag = "<#" + matcher.group(1) + ">";
            } else if (matcher.group(2) != null) {
                tag = "<#" + matcher.group(2).replace("&", "") + ">";
            } else {
                tag = TAGS.getOrDefault(Character.toLowerCase(matcher.group(3).charAt(0)), matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
