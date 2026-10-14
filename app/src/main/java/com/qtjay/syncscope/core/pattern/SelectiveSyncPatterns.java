package com.qtjay.syncscope.core.pattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 选择性同步：把"用户勾选的路径集合"编译成 .stignore 托管规则块。
 *
 * <p>依据 docs.syncthing.net/users/ignoring 与 syncthing v2.1.6 双节点真值校验：
 * <ol>
 *   <li>首个匹配的规则生效，所以 ! 放行必须排在忽略规则之前；</li>
 *   <li>父目录被忽略后子项无法放行，所以每个选中项都要补齐祖先链；</li>
 *   <li>{@code !/相册} 只放行目录本身、不含子项，所以目录需额外补 {@code /**}；</li>
 *   <li>Android/Linux 默认转义符是 {@code \}，Windows 才是 {@code |}；</li>
 *   <li><b>不能用单条全局兜底</b>（{@code *} 或 {@code **}）：真值校验实测它们拦不住
 *       "父目录被 ! 放行"的深层路径，必须给每个被下钻的目录显式补一条"忽略其余子项"。</li>
 * </ol>
 *
 * <p>托管块放在 .stignore 末尾，用户自有规则排在前面，因此用户规则优先级更高。
 */
public final class SelectiveSyncPatterns {

    private static final String SPECIAL_CHARS = "*?[]{}\\";

    private static final char ESCAPE_CHAR = '\\';

    public static final String BLOCK_BEGIN = "// >>> syncthing-fork selective-sync >>>";

    public static final String BLOCK_END = "// <<< syncthing-fork selective-sync <<<";

    public static final int DEFAULT_MAX_PATTERNS = 1000;

    private SelectiveSyncPatterns() {
    }

    /** 生成结果：规则文本 + 需要提示用户的警告。 */
    public static final class Result {
        public final String text;
        public final List<String> warnings;

        private Result(String text, List<String> warnings) {
            this.text = text;
            this.warnings = warnings;
        }
    }

    /** 极简二元组，避免为此引入额外依赖。 */
    public static final class Pair<A, B> {
        public final A first;
        public final B second;

        private Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    /**
     * 生成托管规则行。
     *
     * @param selected 选中的相对路径列表，POSIX 分隔符，不以 / 开头或结尾
     * @param dirs     其中属于目录的路径集合，整个目录被选中时放行其整棵子树
     */
    public static Pair<List<String>, List<String>> buildBlock(List<String> selected, Set<String> dirs) {
        return buildBlock(selected, dirs, DEFAULT_MAX_PATTERNS);
    }

    /**
     * @param maxPatterns 规则条数上限，超过时产生告警
     */
    public static Pair<List<String>, List<String>> buildBlock(List<String> selected,
                                                              Set<String> dirs,
                                                              int maxPatterns) {
        List<String> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> dirSet = (dirs == null) ? new LinkedHashSet<String>() : dirs;

        // 目录 -> 需要进入的子项名称（保持勾选顺序）
        Map<String, List<String>> children = new HashMap<>();
        List<String> valid = new ArrayList<>();
        if (selected != null) {
            for (String path : selected) {
                if (path == null || path.isEmpty() || path.startsWith("/")
                        || path.endsWith("/") || path.contains("//")) {
                    warnings.add("路径格式非法，已跳过: " + path);
                    continue;
                }
                valid.add(path);
                String[] comps = path.split("/");
                for (int i = 0; i < comps.length; i++) {
                    String parent = join(comps, i);
                    List<String> bucket = children.get(parent);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        children.put(parent, bucket);
                    }
                    if (!bucket.contains(comps[i])) {
                        bucket.add(comps[i]);
                    }
                }
            }
        }
        if (valid.isEmpty()) {
            warnings.add("未选择任何内容：该文件夹在本机将完全不同步");
        }

        walk("", children, dirSet, lines, seen, warnings);

        if (lines.size() > maxPatterns) {
            warnings.add("规则条数 " + lines.size() + " 超过上限 " + maxPatterns + "，建议改为勾选上层目录");
        }
        return new Pair<>(lines, warnings);
    }

    /**
     * 把托管块合并进已有 .stignore 文本。
     * 用户自有规则原样保留且排在块之前，因此优先级更高。
     */
    public static Result buildStignore(String existingText, List<String> selected, Set<String> dirs) {
        return buildStignore(existingText, selected, dirs, DEFAULT_MAX_PATTERNS);
    }

    public static Result buildStignore(String existingText,
                                       List<String> selected,
                                       Set<String> dirs,
                                       int maxPatterns) {
        return buildStignore(existingText, selected, dirs, maxPatterns, true);
    }

    /**
     * @param enabled false 表示关闭选择性同步（转为全部同步）：
     *                只保留用户自有规则，移除托管块；选择集仍保留，可随时再开启。
     */
    public static Result buildStignore(String existingText,
                                       List<String> selected,
                                       Set<String> dirs,
                                       int maxPatterns,
                                       boolean enabled) {
        List<String> warnings = new ArrayList<>();
        List<String> blockLines = new ArrayList<>();
        if (enabled) {
            Pair<List<String>, List<String>> built = buildBlock(selected, dirs, maxPatterns);
            blockLines = built.first;
            warnings = built.second;
        }

        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        if (existingText != null && !existingText.isEmpty()) {
            for (String line : existingText.split("\n", -1)) {
                String stripped = line.trim();
                if (BLOCK_BEGIN.equals(stripped)) {
                    skipping = true;
                    continue;
                }
                if (BLOCK_END.equals(stripped)) {
                    skipping = false;
                    continue;
                }
                if (!skipping) {
                    kept.add(line);
                }
            }
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).trim().isEmpty()) {
            kept.remove(kept.size() - 1);
        }

        List<String> out = new ArrayList<>(kept);
        if (!enabled) {
            // 全部同步：不再写入托管块
            StringBuilder plain = new StringBuilder();
            for (String line : out) {
                plain.append(line).append('\n');
            }
            return new Result(plain.toString(), warnings);
        }
        if (!out.isEmpty()) {
            out.add("");
        }
        out.add(BLOCK_BEGIN);
        out.addAll(blockLines);
        out.add(BLOCK_END);

        StringBuilder sb = new StringBuilder();
        for (String line : out) {
            sb.append(line).append('\n');
        }
        return new Result(sb.toString(), warnings);
    }

    /** 从托管块文本判断是否已被本功能接管。选择集本身持久化在 {@link SelectiveSyncStore}。 */
    public static boolean hasManagedBlock(String stignoreText) {
        return stignoreText != null && stignoreText.contains(BLOCK_BEGIN);
    }

    /** 反解托管块得到的勾选集合。 */
    public static final class Selection {
        /** 是否存在托管块；不存在意味着"选择性同步没开，全部同步"。 */
        public final boolean hasBlock;
        /** 所有被放行的路径（含只放行了部分子项的目录本身）。 */
        public final List<String> selectedPaths;
        /** 整棵子树都被选中的目录（规则里带 /** 的那些）。 */
        public final Set<String> fullDirs;

        private Selection(boolean hasBlock, List<String> selectedPaths, Set<String> fullDirs) {
            this.hasBlock = hasBlock;
            this.selectedPaths = selectedPaths;
            this.fullDirs = fullDirs;
        }
    }

    /**
     * 把托管块反解成"哪些路径是勾上的"，用于在目录树上还原现有规则。
     *
     * <p>块内两类行有含义：{@code !/x/**} 表示整个目录被选中，
     * {@code !/x} 表示该项被放行（若是目录则说明只放行了其中一部分子项）；
     * 其余 {@code /x/**} 只是"忽略本层其余子项"的脚手架，不代表勾选。
     */
    public static Selection parseManagedBlock(String stignoreText) {
        List<String> selected = new ArrayList<>();
        Set<String> full = new LinkedHashSet<>();
        boolean hasBlock = hasManagedBlock(stignoreText);
        if (stignoreText == null) {
            return new Selection(false, selected, full);
        }
        boolean inside = false;
        for (String raw : stignoreText.split("\n", -1)) {
            String line = raw.trim();
            if (line.equals(BLOCK_BEGIN)) {
                inside = true;
                continue;
            }
            if (line.equals(BLOCK_END)) {
                inside = false;
                continue;
            }
            if (!inside || !line.startsWith("!")) {
                continue;
            }
            boolean subtree = line.endsWith("/**");
            String path = toPath(line.substring(1));
            if (path.isEmpty()) {
                continue;
            }
            if (subtree) {
                full.add(path);
            }
            if (!selected.contains(path)) {
                selected.add(path);
            }
        }
        return new Selection(hasBlock, selected, full);
    }

    /** 去掉托管块，只保留用户自己的规则（关闭选择性同步时用）。 */
    public static String stripManagedBlock(String stignoreText) {
        if (stignoreText == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inside = false;
        for (String raw : stignoreText.split("\n", -1)) {
            String line = raw.trim();
            if (line.equals(BLOCK_BEGIN)) {
                inside = true;
                continue;
            }
            if (line.equals(BLOCK_END)) {
                inside = false;
                continue;
            }
            if (inside) {
                continue;
            }
            sb.append(raw).append('\n');
        }
        String result = sb.toString();
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 规则行 -> 相对路径：去掉前导 / 、尾部 /** 与转义符。 */
    private static String toPath(String pattern) {
        String p = pattern.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/**")) {
            p = p.substring(0, p.length() - 3);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return unescape(p);
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE_CHAR && i + 1 < value.length()
                    && SPECIAL_CHARS.indexOf(value.charAt(i + 1)) >= 0) {
                sb.append(value.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 逐层生成：先放行该层需要的子项（含其子树），再忽略该层其余子项。
     * 每层末尾那条"忽略其余子项"是关键 —— 只靠一条全局兜底拦不住深层路径。
     */
    private static void walk(String directory, Map<String, List<String>> children,
                             Set<String> dirs, List<String> lines, Set<String> seen,
                             List<String> warnings) {
        List<String> kids = children.get(directory);
        if (kids != null) {
            for (String name : kids) {
                String path = directory.isEmpty() ? name : directory + "/" + name;
                if (dirs.contains(path)) {
                    // 整个目录都被选中：先放行其整棵子树，再放行目录本身
                    emit(lines, seen, "!/" + escaped(path, warnings) + "/**");
                    emit(lines, seen, "!/" + escaped(path, warnings));
                } else if (children.containsKey(path)) {
                    // 只放行了其中一部分：先下钻（内部先放行所需子项，再忽略其余），
                    // 最后才放行目录本身 —— 顺序颠倒会让"忽略其余"对子项失效。
                    walk(path, children, dirs, lines, seen, warnings);
                    emit(lines, seen, "!/" + escaped(path, warnings));
                } else {
                    emit(lines, seen, "!/" + escaped(path, warnings));
                }
            }
        }
        emit(lines, seen, directory.isEmpty() ? "/**" : "/" + escaped(directory, warnings) + "/**");
    }

    private static void emit(List<String> lines, Set<String> seen, String line) {
        if (seen.add(line)) {
            lines.add(line);
        }
    }

    private static String join(String[] comps, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(comps[i]);
        }
        return sb.toString();
    }

    private static String escaped(String path, List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        String[] comps = path.split("/");
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(safeComponent(comps[i], path, warnings));
        }
        return sb.toString();
    }

    private static String escapeComponent(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (SPECIAL_CHARS.indexOf(ch) >= 0) {
                sb.append(ESCAPE_CHAR);
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String safeComponent(String name, String path, List<String> warnings) {
        boolean lead = name.startsWith(" ");
        boolean tail = name.endsWith(" ");
        if (lead || tail) {
            warnings.add(path + ": 名称含首尾空格（Syncthing 会自动裁剪），已用 ? 兜底");
        }
        String core = trimSpaces(name);
        if (lead) {
            core = "?" + core;
        }
        if (tail) {
            core = core + "?";
        }
        String escaped = escapeComponent(core);
        if (escaped.startsWith("!") || escaped.startsWith("#")
                || escaped.startsWith("(") || escaped.startsWith("//")) {
            warnings.add(path + ": 名称以 !/#/( 或 // 开头，无法精确表达，已用 ? 兜底");
            escaped = "?" + escaped.substring(1);
        }
        return escaped;
    }

    private static String trimSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(start, end);
    }
}
