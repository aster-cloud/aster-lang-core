package aster.core.layout;

import java.util.List;

/**
 * 一份显示排版（格式版本 v 恒为 1，ADR 0058 §7.3）：按规范源码顺序排列的 span。
 *
 * <p>由 {@link LayoutFormat#parseStrict(byte[])} 读入的排版已是存储的规范形态（相邻内容 span 已合并）。
 */
public record PolicyLayout(List<LayoutSpan> spans) {

    public PolicyLayout {
        spans = List.copyOf(spans);
    }

    /** 编译用的规范源码：各 span 的 canonical 侧依次拼接。 */
    public String toCanonical() {
        StringBuilder sb = new StringBuilder();
        for (LayoutSpan s : spans) {
            sb.append(s.canonicalText());
        }
        return sb.toString();
    }

    /** 给人看的显示文本：各 span 的 display 侧依次拼接。 */
    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        for (LayoutSpan s : spans) {
            sb.append(s.displayText());
        }
        return sb.toString();
    }
}
