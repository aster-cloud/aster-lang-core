package aster.core.resolve;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.InputRefsAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0046 §11 D2：inputRefs 只写入声明了档案的模块。对 aster-lang-test 语料（与 DeclarationResolverCorpusTest
 * 同一纳入口径）逐个编译：未声明档案的模块 IR 不含 inputRefs 键、标注步骤原样返回同一对象（IR 逐字节不变）；
 * 声明档案的模块只有返回 Verdict 的规则带 inputRefs。语料来自同级仓库 aster-lang-test，缺失时跳过。
 */
class NoProfileIrUnchangedTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 未声明档案的语料IR不含inputRefs() throws IOException {
        int noProfile = 0;
        int profiled = 0;
        List<String> leaked = new ArrayList<>();
        for (DeclarationResolverCorpusTest.Parsed p : DeclarationResolverCorpusTest.parseCorpus("inputRefs")) {
            CoreModel.Module core = NameResolution.resolveAndLower(p.module(), ResolutionContext.none()).core();
            if (core.profile == null) {
                noProfile++;
                assertSame(core, NameResolution.withInputRefs(core), p.file().toString());
                if (MAPPER.writeValueAsString(core).contains("\"inputRefs\"")) leaked.add(p.file().toString());
                continue;
            }
            profiled++;
            for (CoreModel.Decl d : core.decls) {
                if (!(d instanceof CoreModel.Func f)) continue;
                if (InputRefsAnalyzer.applies(core, f)) assertNotNull(f.inputRefs, p.file() + " " + f.name);
                else assertEquals(null, f.inputRefs, p.file() + " " + f.name);
            }
        }
        System.out.println("inputRefs corpus: noProfile=" + noProfile + " profiled=" + profiled);
        assertTrue(noProfile > 100, "纳入的无档案语料过少：" + noProfile);
        assertEquals(List.of(), leaked, "未声明档案的模块不得出现 inputRefs");
    }
}
