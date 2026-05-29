package aster.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * R30+ audit P1：标记"虽然 public 但不属于稳定 API"的类型 / 方法。
 *
 * <p>aster-lang-core 通过 Maven Central + GitHub Packages 发布，所有
 * {@code public} 类目前都被外部消费者视为契约。这是历史遗留——大量
 * parser / canonicalizer 内部 helper 因为同一包内访问需求被升到 public。
 *
 * <p>用 {@code @Internal} 显式打标，传达给 IDE / SonarQube / 文档生成器：
 * <ul>
 *   <li>下游升级 aster-lang-core 时这些 API 可能在补丁版本里改</li>
 *   <li>不会出现在 Javadoc 的 "Stable API" 段落</li>
 *   <li>消费者直接使用 = 自担风险</li>
 * </ul>
 *
 * <p>该注解仅作文档与工具集成入口，不强制 enforcement —— 强制需要
 * ArchUnit 或 module-info 改造，是单独的工作。
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
  /**
   * 简短说明 / 历史背景，方便消费者判断"是否能换成稳定 API"。
   */
  String value() default "";
}
