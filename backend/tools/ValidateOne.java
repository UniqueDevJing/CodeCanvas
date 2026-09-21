import com.codecanvas.common.HtmlValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单文件调试工具：拿真实的 HtmlValidator 校验一个 HTML 文件，打印全部问题。
 *
 * <p>为什么需要它：自检闭环的判定依据就是这个校验器。线上出现"连续 3 次修复都没通过"时，
 * 必须能直接看是**哪一条规则**在报，而不是靠读代码猜。
 *
 * <pre>
 * java -cp target/classes tools/ValidateOne.java &lt;文件路径&gt;
 * </pre>
 */
public class ValidateOne {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: java -cp target/classes tools/ValidateOne.java <html文件>");
            return;
        }
        Path path = Path.of(args[0]);
        String html = Files.readString(path);
        System.out.println("文件: " + path.getFileName() + "  (" + html.length() + " 字符)");

        List<HtmlValidator.Issue> issues = HtmlValidator.validate(html);
        System.out.println("--- 明细问题 " + issues.size() + " 条 ---");
        for (HtmlValidator.Issue issue : issues) {
            System.out.println("  [" + issue.type() + "] " + issue.message());
        }

        System.out.println("--- 质检四项 ---");
        for (HtmlValidator.CheckResult r : HtmlValidator.report(html)) {
            System.out.printf("  %-10s %-6s %s%n", r.id(), r.passed() ? "通过" : "不通过", r.detail());
        }
    }
}
