package aster.core.ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 类路径 schema 副本与 aster-lang-locales {@code core-ir/core-ir.v2.schema.json} 逐字节相同（ADR 0055 §3，
 * ADR 0045 副本模式）。同步方式是整文件复制；深度相等之外再锁字节，避免格式漂移。CI 缺 locales 即失败。
 */
class CoreIrSchemaSiblingParityTest {

    @Test
    void copy_equals_locales_source() throws Exception {
        Optional<Path> source = CoreIrLocales.locate("core-ir/core-ir.v2.schema.json");
        assumeTrue(source.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
        byte[] copy;
        try (InputStream in = CoreIrJson.class.getResourceAsStream(CoreIrJson.SCHEMA_RESOURCE)) {
            assertNotNull(in, "类路径缺少 " + CoreIrJson.SCHEMA_RESOURCE);
            copy = in.readAllBytes();
        }
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(source.get().toFile()), mapper.readTree(copy));
        assertArrayEquals(Files.readAllBytes(source.get()), copy,
            "类路径副本须与 locales core-ir/core-ir.v2.schema.json 逐字节相同");
    }
}
