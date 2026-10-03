package com.imzako.rcon;

import com.imzako.auth.UserAccessService;
import com.imzako.blog.MarkdownBlog;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses and executes authenticated administrative commands. */
@Service
public class RconCommandService {
    private final MarkdownBlog blog;
    private final UserAccessService access;

    public RconCommandService(MarkdownBlog blog, UserAccessService access) {
        this.blog = blog;
        this.access = access;
    }

    public String execute(String input, String actor) {
        List<String> arguments = tokenize(input);
        if (!arguments.isEmpty() && "imzako".equalsIgnoreCase(arguments.getFirst())) {
            arguments = new ArrayList<>(arguments.subList(1, arguments.size()));
        }
        if (arguments.isEmpty() || "help".equalsIgnoreCase(arguments.getFirst())) return help();

        try {
            return switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
                case "new" -> createPost(arguments);
                case "op" -> setPermission(arguments, actor);
                case "ban" -> ban(arguments, actor);
                case "pardon", "unban" -> pardon(arguments);
                case "user", "status" -> status(arguments);
                default -> throw new IllegalArgumentException("未知命令；输入 help 查看命令列表");
            };
        } catch (IOException error) {
            throw new IllegalStateException("博客文件操作失败：" + error.getMessage(), error);
        }
    }

    private String createPost(List<String> arguments) throws IOException {
        if (arguments.size() < 2) throw new IllegalArgumentException("用法：imzako new \"博客名\" [--tags \"标签\"] [--dir 目录] [--publish]");
        String title = arguments.get(1);
        String tags = "";
        String directory = "";
        boolean draft = true;
        for (int index = 2; index < arguments.size(); index++) {
            String option = arguments.get(index);
            switch (option) {
                case "--tags", "-t" -> tags = optionValue(arguments, ++index, option);
                case "--dir", "-d" -> directory = optionValue(arguments, ++index, option);
                case "--publish" -> draft = false;
                case "--draft" -> {
                    if (index + 1 < arguments.size() && !arguments.get(index + 1).startsWith("-")) {
                        draft = parseBoolean(arguments.get(++index));
                    } else {
                        draft = true;
                    }
                }
                default -> throw new IllegalArgumentException("无法识别的 new 选项：" + option);
            }
        }
        MarkdownBlog.CreatedPost post = blog.createPost(title, tags, draft, directory);
        return "已创建 " + post.path() + "；slug=" + post.slug() + "；draft=" + post.draft();
    }

    private String setPermission(List<String> arguments, String actor) {
        requireSize(arguments, 3, "用法：op UID 权限等级");
        long uid = uid(arguments.get(1));
        int level = integer(arguments.get(2), "权限等级");
        return describe(access.setPermission(uid, level, actor));
    }

    private String ban(List<String> arguments, String actor) {
        if (arguments.size() < 4) throw new IllegalArgumentException("用法：ban UID 小时数 \"封禁理由\"");
        long uid = uid(arguments.get(1));
        long hours = longValue(arguments.get(2), "小时数");
        String reason = String.join(" ", arguments.subList(3, arguments.size()));
        return describe(access.ban(uid, hours, reason, actor));
    }

    private String pardon(List<String> arguments) {
        requireSize(arguments, 2, "用法：pardon UID");
        return describe(access.pardon(uid(arguments.get(1))));
    }

    private String status(List<String> arguments) {
        requireSize(arguments, 2, "用法：user UID");
        return describe(access.status(uid(arguments.get(1))));
    }

    private static String describe(UserAccessService.AccessStatus status) {
        String value = "UID=" + status.uid() + " level=" + status.permissionLevel();
        if (status.banned()) value += " bannedUntil=" + status.bannedUntil() + " reason=\"" + status.banReason() + "\"";
        else value += " banned=false";
        return value;
    }

    private static String help() {
        return "命令：imzako new \"博客名\" [--tags \"标签\"] [--dir 目录] [--publish]；"
                + "op UID 1|2|3；ban UID 小时数 \"理由\"；pardon UID；user UID";
    }

    static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) return List.of();
        var result = new ArrayList<String>();
        var token = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaping = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaping) {
                token.append(character);
                escaping = false;
            } else if (character == '\\') {
                escaping = true;
            } else if (quoted) {
                if (character == quote) quoted = false;
                else token.append(character);
            } else if (character == '\'' || character == '"') {
                quoted = true;
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (!token.isEmpty()) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (escaping) token.append('\\');
        if (quoted) throw new IllegalArgumentException("引号没有闭合");
        if (!token.isEmpty()) result.add(token.toString());
        return List.copyOf(result);
    }

    private static String optionValue(List<String> values, int index, String option) {
        if (index >= values.size()) throw new IllegalArgumentException(option + " 缺少取值");
        return values.get(index);
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new IllegalArgumentException("draft 只能是 true 或 false");
    }

    private static long uid(String value) {
        long uid = longValue(value, "UID");
        if (uid < 1) throw new IllegalArgumentException("UID 必须是正整数");
        return uid;
    }

    private static int integer(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " 必须是整数");
        }
    }

    private static long longValue(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " 必须是整数");
        }
    }

    private static void requireSize(List<String> arguments, int size, String usage) {
        if (arguments.size() != size) throw new IllegalArgumentException(usage);
    }
}
