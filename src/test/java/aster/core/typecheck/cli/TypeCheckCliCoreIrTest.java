package aster.core.typecheck.cli;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TypeCheckCli 读 emit-core 输出时走严格读方 {@link CoreIrJson#read}（ADR 0055 §5 严格读方清单；plan Task C5）。 */
class TypeCheckCliCoreIrTest {

    @Test
    void v1_ir_without_schema_version_is_rejected() {
        String v1 = "{\"kind\":\"Module\",\"name\":\"m\",\"decls\":[]}";
        TypeCheckCli.CliException e = assertThrows(TypeCheckCli.CliException.class,
            () -> TypeCheckCli.readCoreIr(v1.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, e.exitCode());
        assertTrue(e.getMessage().contains(CoreIrJson.UNSUPPORTED_SCHEMA_VERSION), e.getMessage());
    }

    @Test
    void unknown_field_is_rejected() {
        String json = CoreIrJson.serialize(module()).replaceFirst("\\{", "{\"extra\":1,");
        TypeCheckCli.CliException e = assertThrows(TypeCheckCli.CliException.class,
            () -> TypeCheckCli.readCoreIr(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains(CoreIrJson.INVALID_REQUEST), e.getMessage());
    }

    @Test
    void v2_ir_is_read() {
        CoreModel.Module m = TypeCheckCli.readCoreIr(CoreIrJson.serialize(module()).getBytes(StandardCharsets.UTF_8));
        assertEquals("m", m.name);
        assertEquals(2, m.schemaVersion);
    }

    private static CoreModel.Module module() {
        CoreModel.Position start = new CoreModel.Position();
        start.line = 1;
        start.col = 1;
        CoreModel.Origin origin = new CoreModel.Origin();
        origin.start = start;
        origin.end = start;
        CoreModel.Module m = new CoreModel.Module();
        m.name = "m";
        m.decls = List.of();
        m.origin = origin;
        return m;
    }
}
