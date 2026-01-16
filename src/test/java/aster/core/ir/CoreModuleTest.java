package aster.core.ir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Core IR 模块基础测试
 */
class CoreModuleTest {

    @Test
    void moduleInterfaceExists() {
        // 占位测试：验证模块接口可访问
        assertNotNull(CoreModule.class, "CoreModule 接口应可访问");
    }
}
