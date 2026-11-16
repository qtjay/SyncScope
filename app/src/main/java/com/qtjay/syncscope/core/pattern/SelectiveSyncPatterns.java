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
 * <p><b>SyncScope 全权接管 .stignore</b>：托管块之外不保留任何规则。
 * syncthing 的匹配是"首个命中的规则生效"，如果保留用户原先写在块外的自定义规则，
 * 它们排在托管块之前就会优先命中 —— 界面上"没勾"的项可能仍被放行、
 * "勾了"的项可能仍被忽略，勾选状态与 syncthing 的实际状态对不上。
 * 所以这里生成的文本只包含本应用的托管块，勾选集就是唯一事实来源。
 */
public final class SelectiveSyncPatterns {

    private static final String SPECIAL_CHARS = "*?[]{}\\";

    private static final char ESCAPE_CHAR = '\\';

    public static final String BLOCK_BEGIN = "// >>> syncthing-fork selective-sync >>>";

    public static final String BLOCK_END = "// <<< syncthing-fork selective-sync <<<";

    public static final int DEFAULT_MAX_PATTERNS = 1000;

    /** "暂停下载"时写进托管块的唯一规则：忽略一切，本机不下载任何内容。 */
    public static final String PAUSE_PATTERN = "*";

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
        // 兜底自检：万一哪一层祖先没补上，这里把它补齐
        auditAncestors(lines, seen, warnings);

        if (lines.size() > maxPatterns) {
            warnings.add("规则条数 " + lines.size() + " 超过上限 " + maxPatterns + "，建议改为勾选上层目录");
        }
        return new Pair<>(lines, warnings);
    }

    /**
     * 把托管块写进 .stignore。
     *
     * <p>SyncScope 全权接管：块外的自定义规则一律不保留，避免它们抢在托管块之前命中。
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
     *                清空 .stignore（不留任何规则）；选择集仍保留，可随时再开启。
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
        return assemble(existingText, blockLines, warnings, enabled);
    }

    /**
     * 暂停下载：托管块里只留一条"忽略一切"，不下载任何东西。
     *
     * <p>用途：新加的文件夹还没被扫进全局模型时，browse 是空的，用户无从勾选；
     * 而等扫出来的这段时间里本机已经在下载了，等能勾选时文件已经下来一大堆，
     * 选择性同步就失去意义了。先按住不下载，等目录结构出现再勾想要的，
     * 保存时块会被换成正常的放行规则 —— 那时才开始下载勾选的内容。
     */
    public static Result buildPausedStignore(String existingText) {
        List<String> blockLines = new ArrayList<>();
        blockLines.add(PAUSE_PATTERN);
        return assemble(existingText, blockLines, new ArrayList<String>(), true);
    }

    private static Result assemble(String existingText,
                                   List<String> blockLines,
                                   List<String> warnings,
                                   boolean enabled) {
        // 接管：块外原有的自定义规则会被清掉，先数出来告诉用户，不做静默删除。
        List<String> foreign = foreignRules(existingText);
        if (!foreign.isEmpty()) {
            warnings.add("已接管并清除 " + foreign.size() + " 条原有自定义忽略规则");
        }

        if (!enabled) {
            // 全部同步：.stignore 必须彻底为空，留任何一条规则都会让界面与事实再次打架
            return new Result("", warnings);
        }

        List<String> out = new ArrayList<>();
        out.add(BLOCK_BEGIN);
        out.addAll(blockLines);
        out.add(BLOCK_END);

        StringBuilder sb = new StringBuilder();
        for (String line : out) {
            sb.append(line).append('\n');
        }
        return new Result(sb.toString(), warnings);
    }

    /**
     * 列出托管块之外、由用户或别的工具写下的规则（不含空行、注释、托管块标记）。
     *
     * <p>用于进入界面时提醒"这些规则会影响当前显示的状态"，
     * 以及保存时告诉用户一共接管了多少条。
     */
    public static List<String> foreignRules(String stignoreText) {
        List<String> out = new ArrayList<>();
        if (stignoreText == null || stignoreText.isEmpty()) {
            return out;
        }
        boolean inside = false;
        for (String raw : stignoreText.split("\n", -1)) {
            String line = raw.trim();
            if (BLOCK_BEGIN.equals(line)) {
                inside = true;
                continue;
            }
            if (BLOCK_END.equals(line)) {
                inside = false;
                continue;
            }
            if (inside || line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            out.add(line);
        }
        return out;
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
        /**
         * 处于"暂停下载"状态：托管块存在，但里面没有任何放行规则。
         * 判定依据就是"有块且放行清单为空" —— 此时本机什么都不下载。
         */
        public final boolean paused;

        private Selection(boolean hasBlock, List<String> selectedPaths,
                          Set<String> fullDirs, boolean paused) {
            this.hasBlock = hasBlock;
            this.selectedPaths = selectedPaths;
            this.fullDirs = fullDirs;
            this.paused = paused;
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
            return new Selection(false, selected, full, false);
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
        // 有托管块却一条放行都没有 = 暂停下载（块里只有"忽略一切"）
        return new Selection(hasBlock, selected, full, hasBlock && selected.isEmpty());
    }

    /**
     * 全部同步时清空 .stignore。
     *
     * <p>SyncScope 全权接管后，"全部同步"就是零规则；不能再把块外的自定义规则留下来，
     * 否则关掉选择性同步后它们又抢先命中，界面和实际状态依旧对不上。
     */
    public static String stripManagedBlock(String stignoreText) {
        return "";
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

    /**
     * 自检：被放行的每一项的祖先链必须完整。
     *
     * <p>Syncthing 的规则是"父目录被忽略后，子项无法放行"。少补任何一级祖先，
     * 表现就是"子目录明明勾了却同步不了" —— 规则自相矛盾，而且很难从界面上看出来。
     * walk() 本来就会逐层补，这里再兜一次，防止以后改动时漏掉。
     */
    private static void auditAncestors(List<String> lines, Set<String> seen,
                                       List<String> warnings) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String line : lines) {
            if (!line.startsWith("!")) {
                continue;
            }
            String path = toPath(line.substring(1));
            if (!path.isEmpty()) {
                allowed.add(path);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String path : allowed) {
            String[] comps = path.split("/");
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < comps.length - 1; i++) {
                if (current.length() > 0) {
                    current.append('/');
                }
                current.append(comps[i]);
                String ancestor = current.toString();
                if (!allowed.contains(ancestor) && !missing.contains(ancestor)) {
                    missing.add(ancestor);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        // 放行规则必须排在忽略规则之前（首个匹配生效），所以补在最前面
        List<String> patched = new ArrayList<>();
        for (String path : missing) {
            if (seen.add("!/" + path)) {
                patched.add("!/" + path);
            }
        }
        if (!patched.isEmpty()) {
            lines.addAll(0, patched);
            warnings.add(
                "已补齐 " + patched.size() + " 条父目录放行规则（缺了它们子项同步不了）");
        }
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
