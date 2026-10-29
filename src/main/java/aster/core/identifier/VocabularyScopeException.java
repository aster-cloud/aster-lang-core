package aster.core.identifier;

/**
 * 构建 {@link VocabularyScopes} 时词汇不合法：字段 / 枚举成员缺少父名，或同折叠键的字面量宏内容冲突。
 * 错误码与 TS VocabularyScopeError 一致。
 */
public final class VocabularyScopeException extends IllegalArgumentException {

    /** 错误码；value 与 TS 侧字符串一致。 */
    public enum Code {
        FIELD_PARENT_REQUIRED("field_parent_required"),
        MEMBER_PARENT_REQUIRED("member_parent_required"),
        LITERAL_CONFLICT("literal_conflict");

        private final String value;

        Code(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Code code;

    public VocabularyScopeException(Code code, String message) {
        super(code.value() + ": " + message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
