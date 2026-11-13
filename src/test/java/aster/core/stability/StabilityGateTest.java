package aster.core.stability;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR 0055 #8 删除 {@code Func.piiLevel} 之后，PII 特性仍只由签名类型树中的 {@code PiiType} 触发。
 */
class StabilityGateTest {

    @Test
    void pii_signature_still_reported() {
        CoreModel.TypeName text = new CoreModel.TypeName();
        text.name = "Text";
        CoreModel.PiiType pii = new CoreModel.PiiType();
        pii.baseType = text;
        pii.sensitivity = "L2";
        pii.category = "email";
        CoreModel.Param email = new CoreModel.Param();
        email.name = "email";
        email.type = pii;
        CoreModel.Block body = new CoreModel.Block();
        body.statements = List.of();
        CoreModel.TypeName bool = new CoreModel.TypeName();
        bool.name = "Bool";
        CoreModel.Func f = new CoreModel.Func();
        f.name = "check";
        f.params = List.of(email);
        f.ret = bool;
        f.body = body;
        CoreModel.Module m = new CoreModel.Module();
        m.name = "demo";
        m.decls = List.of(f);

        List<StabilityGate.Diagnostic> diags = StabilityGate.scan(m, StabilityGate.Options.warnMode());

        assertEquals(List.of(StabilityGate.FeatureId.PII), diags.stream().map(StabilityGate.Diagnostic::featureId).toList());
    }
}
