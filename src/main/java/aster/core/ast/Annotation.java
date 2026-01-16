package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 注解（用于标注 Field 或 Parameter）
 * <p>
 * 支持带参数的注解，例如 @sensitive(level="high")。
 *
 * @param name   注解名称
 * @param params 注解参数（键值对）
 */
public record Annotation(
    @JsonProperty("name") String name,
    @JsonProperty("params") Map<String, Object> params
) {}
