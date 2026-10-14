import com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns;
import java.util.*;

public class VerifyPatterns {
    static int failed = 0;

    static void check(boolean cond, String name) {
        System.out.println((cond ? "OK   " : "FAIL ") + name);
        if (!cond) failed++;
    }

    public static void main(String[] args) {
        // 场景：选中目录 相册 的整棵子树，外加单个文件 文档/readme.md
        List<String> selected = Arrays.asList("相册", "文档/readme.md");
        Set<String> dirs = new HashSet<>(Arrays.asList("相册"));
        SelectiveSyncPatterns.Result r =
                SelectiveSyncPatterns.buildStignore("", selected, dirs);

        System.out.println("--- 生成的 .stignore ---");
        System.out.println(r.text);
        System.out.println("------------------------");

        // 目录被整体选中：先放行整棵子树，再放行目录本身
        check(r.text.contains("!/相册/**"), "放行整棵子树");
        check(r.text.contains("!/相册\n"), "放行目录本身");
        // 单个文件的祖先需要补齐
        check(r.text.contains("!/文档"), "补齐祖先目录");
        check(r.text.contains("!/文档/readme.md"), "放行单个文件");
        // 全局兜底
        check(r.text.contains("/**"), "全局兜底");
        // 顺序：放行必须在对应的"忽略其余"之前
        int iAllow = r.text.indexOf("!/文档/readme.md");
        int iIgnore = r.text.indexOf("/文档/**");
        check(iAllow >= 0 && iIgnore >= 0 && iAllow < iIgnore,
                "顺序正确：先放行所需，再忽略其余");
        // 关闭选择性同步时不产生托管块
        SelectiveSyncPatterns.Result off =
                SelectiveSyncPatterns.buildStignore("", selected, dirs,
                        SelectiveSyncPatterns.DEFAULT_MAX_PATTERNS, false);
        check(!off.text.contains(SelectiveSyncPatterns.BLOCK_BEGIN),
                "关闭时不写托管块");

        List<String> none = Collections.emptyList();
        SelectiveSyncPatterns.Result empty =
                SelectiveSyncPatterns.buildStignore("", none, Collections.emptySet());
        check(empty.text.contains("/**"), "未选择任何内容时全部忽略");
        check(!empty.warnings.isEmpty(), "未选择任何内容时有告警");

        System.out.println(failed == 0 ? "\n全部通过" : "\n有 " + failed + " 项失败");
        if (failed > 0) System.exit(1);
    }
}
