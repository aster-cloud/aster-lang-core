package aster.core.identifier;

import java.util.Objects;

/**
 * 词汇作用域中的一个候选（ADR 0051 §5）。同一折叠键下的全部候选都保留，由解析器按位置判定。
 *
 * @param written   源码写法（NFC）
 * @param canonical 规范名；字面量宏为字面量内容
 * @param kind      标识符类型
 * @param parent    字段 / 枚举成员所属的规范父名；其它类型为 null
 * @param source    主词条（localized）为 TERM，别名（aliases）为 SYNONYM
 */
public record Candidate(
    String written,
    String canonical,
    IdentifierKind kind,
    String parent,
    Source source
) {

    /** 候选来源；value 为规范 JSON 中的字符串。 */
    public enum Source {
        TERM("term"),
        SYNONYM("synonym");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public Candidate {
        Objects.requireNonNull(written, "written is required");
        Objects.requireNonNull(canonical, "canonical is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(source, "source is required");
    }
}
