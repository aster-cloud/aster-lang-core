package aster.core.lexicon.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lexicon 校验报告。
 *
 * @param lexiconId 被校验 lexicon 的 ID
 * @param issues 发现的问题列表（ERROR / WARNING / INFO）
 */
public record LexiconValidationReport(String lexiconId, List<Issue> issues) {

    public enum Severity { ERROR, WARNING, INFO }

    public record Issue(Severity severity, String code, String message, String suggestion) {}

    public LexiconValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** True 当且仅当不含任何 ERROR 级 issue。 */
    public boolean passed() {
        return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
    }

    public List<Issue> errors() {
        return filterBy(Severity.ERROR);
    }

    public List<Issue> warnings() {
        return filterBy(Severity.WARNING);
    }

    private List<Issue> filterBy(Severity sev) {
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.severity() == sev) out.add(i);
        }
        return Collections.unmodifiableList(out);
    }
}
