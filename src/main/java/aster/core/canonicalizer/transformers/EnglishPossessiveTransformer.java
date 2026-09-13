package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * 英语属格 {@code 's} → 成员访问符 {@code .} 的变换器。
 * <p>
 * 示例：{@code driver's age} → {@code driver.age}
 */
public final class EnglishPossessiveTransformer implements SyntaxTransformer {

    public static final EnglishPossessiveTransformer INSTANCE = new EnglishPossessiveTransformer();

    /**
     * ★左锚 {@code (?<!\p{L})} 是 <b>ReDoS 修复</b>，且必须与 TS 侧
     * {@code transformers.ts} 的 {@code POSSESSIVE_RE} <b>逐字一致</b>
     * ——两个引擎的 canonicalize 输出必须字节相同（tier1-parity 门禁）。
     *
     * <p>无左锚时 {@code [\p{L}][\p{L}0-9_]*} 可从标识符<b>中间</b>任意位置起跑：
     * 每个字符位置都重新贪婪吃完整个标识符、再因后面不是 {@code 's} 而全部回退，
     * 呈二次增长。TS 侧实测：10000→160ms、20000→639ms、40000→2552ms（×4）。
     * 攻击载荷就是一行普通源码：一长串字母后跟一个不闭合的 {@code 's}。
     *
     * <p>★左锚里<b>只能</b>排除 {@code \p{L}}，不能连 {@code 0-9_} 一起排。
     * 模式首字符是 {@code [\p{L}]}——不含数字与下划线，所以 {@code _x's y}
     * 是从 {@code x} 起跑的合法匹配（得 {@code _x.y}），多排了 {@code _}
     * 会把它整个挡掉。左锚的正确宽度 = 「该模式<b>首字符</b>能取的字符集」。
     *
     * <p>实证（TS 侧同款模式，随机 300000 组、16715 组确有替换）：
     * {@code (?<!\p{L})} 分歧 0；{@code (?<![\p{L}0-9_])} 分歧 1895。
     */
    private static final Pattern POSSESSIVE = Pattern.compile(
            "(?<!\\p{L})([\\p{L}][\\p{L}0-9_]*)'s\\s+([\\p{L}][\\p{L}0-9_]*)"
    );

    private EnglishPossessiveTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, POSSESSIVE, "$1.$2");
    }
}
