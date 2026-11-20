package aster.core.layout;

import java.util.Objects;

/**
 * 显示排版的一个 span（ADR 0058 §7.3）：内容 span 在两个视图里原样出现；结构 span 在规范源码里是
 * {@code canonical}、在显示文本里换成 {@code display}（可为空串以隐藏）。
 */
public sealed interface LayoutSpan permits LayoutSpan.Content, LayoutSpan.Structural {

    /** span 在规范源码视图中的文字。 */
    String canonicalText();

    /** span 在显示视图中的文字。 */
    String displayText();

    /** 内容 span {@code {text}}：两个视图相同。 */
    record Content(String text) implements LayoutSpan {
        public Content {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String canonicalText() {
            return text;
        }

        @Override
        public String displayText() {
            return text;
        }
    }

    /** 结构 span {@code {canonical, display}}。 */
    record Structural(String canonical, String display) implements LayoutSpan {
        public Structural {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(display, "display");
        }

        @Override
        public String canonicalText() {
            return canonical;
        }

        @Override
        public String displayText() {
            return display;
        }
    }
}
