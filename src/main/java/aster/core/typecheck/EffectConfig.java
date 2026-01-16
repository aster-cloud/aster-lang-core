package aster.core.typecheck;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 效果系统配置（Effect Config）
 * <p>
 * 支持从配置文件加载自定义效果推断规则，定义如何根据函数调用前缀推断效果类型。
 * 完全复制 TypeScript 版本的效果配置系统，确保推断规则一致。
 * <p>
 * 核心功能：
 * - JSON 配置加载：支持从 .aster/effects.json 加载配置
 * - 环境变量支持：ASTER_EFFECT_CONFIG 可自定义配置路径
 * - 静默降级：配置加载失败时自动使用默认配置
 * - 细粒度分类：支持 io.http、io.sql、io.files、cpu、ai 等细分效果
 * - 单例模式：模块级缓存避免重复 I/O
 */
public final class EffectConfig {

  // ========== 单例实例 ==========

  private static volatile EffectConfig instance;

  /**
   * 获取全局单例实例
   */
  public static EffectConfig getInstance() {
    if (instance == null) {
      synchronized (EffectConfig.class) {
        if (instance == null) {
          instance = new EffectConfig();
        }
      }
    }
    return instance;
  }

  /**
   * 重置配置缓存（仅用于测试）
   */
  public static synchronized void resetForTesting() {
    instance = null;
  }

  // ========== 配置数据结构 ==========

  /**
   * 效果推断配置
   *
   * @param patterns 效果推断模式配置
   */
  public record EffectInferenceConfig(Patterns patterns) {
  }

  /**
   * 效果模式配置
   *
   * @param io  IO 效果的细粒度分类
   * @param cpu CPU 密集型计算前缀
   * @param ai  AI 模型调用前缀
   */
  public record Patterns(IoPatterns io, List<String> cpu, List<String> ai) {
  }

  /**
   * IO 效果的细粒度分类
   *
   * @param http    HTTP 网络请求前缀
   * @param sql     SQL 数据库操作前缀
   * @param files   文件系统操作前缀
   * @param secrets 密钥/凭证访问前缀
   * @param time    时间相关操作前缀
   */
  public record IoPatterns(
    List<String> http,
    List<String> sql,
    List<String> files,
    List<String> secrets,
    List<String> time
  ) {
  }

  // ========== 默认配置 ==========

  /**
   * 默认效果推断配置
   * <p>
   * 包含当前所有硬编码前缀，确保向后兼容。
   * 前缀来源：src/config/semantic.ts 中的 IO_PREFIXES 和 CPU_PREFIXES。
   */
  private static final EffectInferenceConfig DEFAULT_CONFIG = new EffectInferenceConfig(
    new Patterns(
      new IoPatterns(
        // HTTP 相关：Http. 和产品服务调用，以及通用 IO 前缀
        List.of("IO.", "Http.", "AuthRepo.", "ProfileSvc.", "FeedSvc."),
        // SQL 相关：数据库操作
        List.of("Db."),
        // 文件相关：暂无默认前缀
        List.of(),
        // 密钥相关：UUID 生成（随机性）
        List.of("UUID.randomUUID"),
        // 时间相关：暂无默认前缀
        List.of()
      ),
      // CPU 相关：当前为空（完全依赖调用链传播）
      List.of(),
      // AI 相关：暂无默认前缀
      List.of()
    )
  );

  // ========== 字段 ==========

  private final EffectInferenceConfig config;

  // ========== 构造器（私有） ==========

  private EffectConfig() {
    this.config = loadConfig();
  }

  // ========== 公共 API ==========

  /**
   * 获取所有 IO 前缀（合并所有 IO 子分类）
   */
  public List<String> getIOPrefixes() {
    var io = config.patterns().io();
    return Stream.of(
        io.http().stream(),
        io.sql().stream(),
        io.files().stream(),
        io.secrets().stream(),
        io.time().stream()
      )
      .flatMap(s -> s)
      .toList();
  }

  /**
   * 获取 CPU 前缀
   */
  public List<String> getCPUPrefixes() {
    return config.patterns().cpu();
  }

  /**
   * 获取 AI 前缀
   */
  public List<String> getAIPrefixes() {
    return config.patterns().ai();
  }

  /**
   * 获取 HTTP 前缀（IO 子分类）
   */
  public List<String> getHttpPrefixes() {
    return config.patterns().io().http();
  }

  /**
   * 获取 SQL 前缀（IO 子分类）
   */
  public List<String> getSqlPrefixes() {
    return config.patterns().io().sql();
  }

  /**
   * 获取文件系统前缀（IO 子分类）
   */
  public List<String> getFilesPrefixes() {
    return config.patterns().io().files();
  }

  /**
   * 获取密钥访问前缀（IO 子分类）
   */
  public List<String> getSecretsPrefixes() {
    return config.patterns().io().secrets();
  }

  /**
   * 获取时间操作前缀（IO 子分类）
   */
  public List<String> getTimePrefixes() {
    return config.patterns().io().time();
  }

  /**
   * 获取完整配置（用于调试或序列化）
   */
  public EffectInferenceConfig getConfig() {
    return config;
  }

  // ========== 私有辅助方法 ==========

  /**
   * 加载配置文件
   * <p>
   * 配置来源优先级：
   * 1. 环境变量 ASTER_EFFECT_CONFIG 指定的路径
   * 2. 默认路径 .aster/effects.json
   * 3. 配置文件不存在或解析失败时使用 DEFAULT_CONFIG
   *
   * @return 效果推断配置
   */
  private EffectInferenceConfig loadConfig() {
    // 获取配置文件路径（优先使用环境变量）
    var configPath = System.getenv().getOrDefault(
      "ASTER_EFFECT_CONFIG",
      ".aster/effects.json"
    );

    // 尝试加载配置文件
    try {
      var path = Path.of(configPath);
      if (!Files.exists(path)) {
        // 文件不存在，静默使用默认配置
        return DEFAULT_CONFIG;
      }

      var json = Files.readString(path);
      var objectMapper = new ObjectMapper();
      var userConfig = objectMapper.readValue(json, PartialConfig.class);

      // 合并用户配置与默认配置
      return mergeWithDefault(userConfig);
    } catch (IOException | IllegalArgumentException e) {
      // 配置加载失败，静默降级到默认配置
      return DEFAULT_CONFIG;
    }
  }

  /**
   * 深度合并用户配置与默认配置
   * <p>
   * 确保所有必需字段都存在，避免访问 null 字段时抛错。
   * 同时验证数组字段类型，过滤 null 元素。
   *
   * @param userConfig 用户提供的配置
   * @return 完整且经过验证的配置对象
   */
  private EffectInferenceConfig mergeWithDefault(PartialConfig userConfig) {
    var defaultPatterns = DEFAULT_CONFIG.patterns();
    var defaultIo = defaultPatterns.io();
    var userPatterns = userConfig.patterns;

    // 合并 IO 模式
    var userIo = userPatterns != null ? userPatterns.io : null;
    var mergedIo = new IoPatterns(
      validateStringList(userIo != null ? userIo.http : null, defaultIo.http()),
      validateStringList(userIo != null ? userIo.sql : null, defaultIo.sql()),
      validateStringList(userIo != null ? userIo.files : null, defaultIo.files()),
      validateStringList(userIo != null ? userIo.secrets : null, defaultIo.secrets()),
      validateStringList(userIo != null ? userIo.time : null, defaultIo.time())
    );

    // 合并其他模式
    var mergedPatterns = new Patterns(
      mergedIo,
      validateStringList(userPatterns != null ? userPatterns.cpu : null, defaultPatterns.cpu()),
      validateStringList(userPatterns != null ? userPatterns.ai : null, defaultPatterns.ai())
    );

    return new EffectInferenceConfig(mergedPatterns);
  }

  /**
   * 验证并清理字符串列表
   * <p>
   * 过滤 null 元素，如果列表为空或全部元素为 null，则使用默认值。
   *
   * @param value    待验证的列表
   * @param fallback 验证失败时的默认值
   * @return 清理后的字符串列表
   */
  private List<String> validateStringList(List<String> value, List<String> fallback) {
    if (value == null || value.isEmpty()) {
      return fallback;
    }

    // 过滤 null 元素
    var cleaned = value.stream()
      .filter(item -> item != null)
      .toList();

    // 如果所有元素都被过滤掉，使用默认值
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  // ========== 辅助类：部分配置（用于 JSON 反序列化） ==========

  /**
   * 部分配置（用于 Jackson 反序列化）
   * <p>
   * 所有字段都是可空的，以支持部分配置覆盖。
   */
  private static class PartialConfig {
    public PartialPatterns patterns;
  }

  private static class PartialPatterns {
    public PartialIoPatterns io;
    public List<String> cpu;
    public List<String> ai;
  }

  private static class PartialIoPatterns {
    public List<String> http;
    public List<String> sql;
    public List<String> files;
    public List<String> secrets;
    public List<String> time;
  }
}
