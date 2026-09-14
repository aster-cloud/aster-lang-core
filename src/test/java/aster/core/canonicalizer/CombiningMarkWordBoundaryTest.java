package aster.core.canonicalizer;

import aster.core.lexicon.DynamicLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 组合记号（Unicode Mark）必须参与词边界判定（issue aster-lang-hi#73）。
 *
 * <h2>★为什么这条测试必须在 core 而不只在 hi</h2>
 *
 * <p>修复代码在本仓 {@link Canonicalizer#isIdentifierChar(char)}，但此前唯一的
 * 行为守卫在 <b>aster-lang-hi</b>。core 的 CI 不跑 hi，于是在本仓重构
 * {@code isIdentifierChar} 的人拿不到任何信号——实测：把修复整个撤销，
 * core 自有的 1667 条全绿。
 *
 * <p>本文件把守卫搬回代码所在地，用自建的最小天城文词表，不依赖 hi 仓。
 *
 * <h2>★夹具是实测选出来的，不是想当然写的</h2>
 *
 * <p>第一版夹具用了 {@code "RULE": "नियम"}——但 <b>RULE 根本不是合法的关键词
 * 键名</b>（{@code नियम} 的真实键是 {@code FUNC_TO}），未知键被静默忽略。
 * 于是那两条用例断言的是「一个从不翻译的词没有被翻译」，<b>恒真</b>：
 * 把修复整个撤销它们依旧全绿。这是又一例夹具维度塌缩。
 *
 * <p>因此本文件的每个关键词都先实测过<b>独立成词时确实会翻译</b>
 * （见 {@link #keywordsActuallyTranslate()}）——否则「保持不变」这个断言
 * 没有任何鉴别力。
 *
 * <h2>四个象限</h2>
 *
 * <p>词边界是对称的两道判定（前驱字符 / 后继字符），组合记号又分
 * Mn(NON_SPACING_MARK) / Mc(COMBINING_SPACING_MARK)，故需 2×2 四个样本。
 *
 * <p>关键词的<b>首尾必须是基辅音(Lo)</b>，matra 才落在词边界上：
 * 若关键词自身以 matra 结尾（如 {@code तो} = त+ो），追加的 matra 就不在
 * 边界位置，测不到目标分支。{@code पर}(ON)、{@code जब}(WHEN)、
 * {@code चरण}(STEP) 均为首尾 Lo，实测有效。
 *
 * <p>变异验证（逐象限独立可证伪）：
 * <ul>
 *   <li>删掉整个 Mn|Mc 分支 → 4 条象限用例全红</li>
 *   <li>只删 Mn → 两条 Mn 象限红，两条 Mc 象限仍绿</li>
 *   <li>只删 Mc → 两条 Mc 象限红，两条 Mn 象限仍绿</li>
 *   <li>反向 {@code return true}（一切皆标识符字符）→ 反向守卫红</li>
 * </ul>
 */
@DisplayName("组合记号参与词边界判定")
class CombiningMarkWordBoundaryTest {

    /**
     * 最小天城文词表：三个首尾均为基辅音的短关键词。
     *
     * <p>取自真实 hi-IN 词表。该词表 79 个关键词里多数含组合记号、
     * 近 30 个长度 ≤4，任何含短关键词且相邻位置是 matra 的标识符
     * 都会被静默改写。
     */
    private static final String HI_MINIMAL = """
        {
          "meta": { "id": "hi-mark-test", "name": "测试用最小天城文词表", "direction": "LTR" },
          "keywords": { "ON": "पर", "WHEN": "जब", "STEP": "चरण" },
          "punctuation": { "statementEnd": "।", "listSeparator": ",", "enumSeparator": ",",
                           "blockStart": ":", "stringQuoteOpen": "\\"",
                           "stringQuoteClose": "\\"" },
          "canonicalization": { "fullWidthToHalf": false, "whitespaceMode": "ENGLISH",
                                "removeArticles": false,
                                "preTranslationTransformers": [],
                                "postTranslationTransformers": [] },
          "messages": {}
        }
        """;

    private String canonicalize(String source) {
        return new Canonicalizer(DynamicLexicon.fromJsonString(HI_MINIMAL)).canonicalize(source);
    }

    /** 标识符必须原样穿过规范化——被改写即为缺陷。 */
    private void assertUnchanged(String identifier, String quadrant) {
        assertEquals(identifier, canonicalize(identifier),
            quadrant + "：标识符被词内替换（关键词吃掉了用户标识符）");
    }

    @Test
    @DisplayName("前提：三个关键词独立成词时确实会翻译")
    void keywordsActuallyTranslate() {
        // ★没有这一条，下面四个象限的「保持不变」可能只是因为该词压根不翻译
        //   （第一版夹具正是栽在这里：RULE 不是合法键名，静默忽略）。
        assertEquals("on", canonicalize("पर"), "पर 必须翻译成 on");
        assertEquals("When", canonicalize("जब"), "जब 必须翻译成 When");
        assertEquals("step", canonicalize("चरण"), "चरण 必须翻译成 step");
    }

    @Test
    @DisplayName("next-char × Mc：词首关键词后随间距组合记号")
    void nextCharCombiningSpacingMark() {
        // परी = पर(ON) + ी(Mc,U+0940)；修复前 → onी
        assertUnchanged("परी", "next-char × Mc");
        // चरणी = चरण(STEP) + ी(Mc)；换一个关键词，避免单点巧合
        assertUnchanged("चरणी", "next-char × Mc");
    }

    @Test
    @DisplayName("next-char × Mn：词首关键词后随非间距记号")
    void nextCharNonSpacingMark() {
        // परु = पर(ON) + ु(Mn,U+0941)；修复前 → onु
        assertUnchanged("परु", "next-char × Mn");
        assertUnchanged("जबु", "next-char × Mn");
    }

    @Test
    @DisplayName("prev-char × Mc：词尾关键词前驱为间距组合记号")
    void prevCharCombiningSpacingMark() {
        // कीपर = क + ी(Mc) + पर(ON)；修复前 → कीon
        assertUnchanged("कीपर", "prev-char × Mc");
        assertUnchanged("कीचरण", "prev-char × Mc");
    }

    @Test
    @DisplayName("prev-char × Mn：词尾关键词前驱为非间距记号")
    void prevCharNonSpacingMark() {
        // कुपर = क + ु(Mn) + पर(ON)；修复前 → कुon
        assertUnchanged("कुपर", "prev-char × Mn");
        assertUnchanged("कुजब", "prev-char × Mn");
    }

    @Test
    @DisplayName("反向守卫：以空格分隔的关键词仍必须被翻译")
    void spaceSeparatedKeywordsStillTranslate() {
        // ★必须用**有相邻字符**的形态：孤立的 "पर" 两侧无字符，
        //   压根走不到边界判定，因此挡不住「把一切都当标识符字符」这个反向变异
        //   （实测：`isIdentifierChar` 恒返回 true 时，canonicalize("पर") 仍是 "on"）。
        //   空格分隔才真正经过前驱/后继两道判定。
        assertEquals("on When", canonicalize("पर जब"),
            "空格分隔的关键词必须各自翻译——收紧词边界不得误伤正常语句");
        assertEquals("When on step", canonicalize("जब पर चरण"),
            "三词语句必须逐词翻译");
    }
}
