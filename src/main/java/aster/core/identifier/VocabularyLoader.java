package aster.core.identifier;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 词汇表加载器。
 *
 * 支持从 JSON 文件或输入流加载领域词汇表。
 *
 * JSON 格式示例：
 * <pre>
 * {
 *   "id": "insurance.auto",
 *   "name": "汽车保险",
 *   "locale": "zh-CN",
 *   "version": "1.0.0",
 *   "metadata": {
 *     "author": "Aster Team",
 *     "createdAt": "2025-01-06",
 *     "description": "汽车保险业务领域的中文标识符映射"
 *   },
 *   "structs": [
 *     { "canonical": "Driver", "localized": "驾驶员", "aliases": ["司机", "驾驶人"] }
 *   ],
 *   "fields": [
 *     { "canonical": "age", "localized": "年龄", "parent": "Driver" }
 *   ],
 *   "functions": [
 *     { "canonical": "calculatePremium", "localized": "计算保费" }
 *   ],
 *   "enumValues": [
 *     { "canonical": "Approved", "localized": "批准", "aliases": ["通过"] }
 *   ]
 * }
 * </pre>
 */
public final class VocabularyLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VocabularyLoader() {}

    /**
     * 从 JSON 文件加载词汇表。
     *
     * @param path JSON 文件路径
     * @return 领域词汇表
     * @throws IOException 如果读取失败
     */
    public static DomainVocabulary loadFromFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadFromStream(is);
        }
    }

    /**
     * 从输入流加载词汇表。
     *
     * @param inputStream JSON 输入流
     * @return 领域词汇表
     * @throws IOException 如果解析失败
     */
    public static DomainVocabulary loadFromStream(InputStream inputStream) throws IOException {
        JsonVocabulary json = MAPPER.readValue(inputStream, JsonVocabulary.class);
        return convertToVocabulary(json);
    }

    /**
     * 从 Reader 加载词汇表。
     *
     * @param reader JSON Reader
     * @return 领域词汇表
     * @throws IOException 如果解析失败
     */
    public static DomainVocabulary loadFromReader(Reader reader) throws IOException {
        JsonVocabulary json = MAPPER.readValue(reader, JsonVocabulary.class);
        return convertToVocabulary(json);
    }

    /**
     * 从 JSON 字符串加载词汇表。
     *
     * @param jsonString JSON 字符串
     * @return 领域词汇表
     * @throws IOException 如果解析失败
     */
    public static DomainVocabulary loadFromString(String jsonString) throws IOException {
        JsonVocabulary json = MAPPER.readValue(jsonString, JsonVocabulary.class);
        return convertToVocabulary(json);
    }

    /**
     * 从 Map 加载词汇表（适用于已解析的 JSON）。
     *
     * @param map 词汇表数据
     * @return 领域词汇表
     */
    @SuppressWarnings("unchecked")
    public static DomainVocabulary loadFromMap(Map<String, Object> map) {
        JsonVocabulary json = MAPPER.convertValue(map, JsonVocabulary.class);
        return convertToVocabulary(json);
    }

    /**
     * 将词汇表序列化为 JSON 字符串。
     *
     * @param vocabulary 领域词汇表
     * @return JSON 字符串
     * @throws IOException 如果序列化失败
     */
    public static String toJson(DomainVocabulary vocabulary) throws IOException {
        return toJson(vocabulary, false);
    }

    /**
     * 将词汇表序列化为 JSON 字符串。
     *
     * @param vocabulary 领域词汇表
     * @param pretty     是否格式化输出
     * @return JSON 字符串
     * @throws IOException 如果序列化失败
     */
    public static String toJson(DomainVocabulary vocabulary, boolean pretty) throws IOException {
        JsonVocabulary json = convertToJson(vocabulary);
        if (pretty) {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        }
        return MAPPER.writeValueAsString(json);
    }

    // ==========================================
    // 内部转换方法
    // ==========================================

    private static DomainVocabulary convertToVocabulary(JsonVocabulary json) {
        List<IdentifierMapping> structs = convertMappings(json.structs, IdentifierKind.STRUCT);
        List<IdentifierMapping> fields = convertMappings(json.fields, IdentifierKind.FIELD);
        List<IdentifierMapping> functions = convertMappings(json.functions, IdentifierKind.FUNCTION);
        List<IdentifierMapping> enumValues = convertMappings(json.enumValues, IdentifierKind.ENUM_VALUE);

        DomainVocabulary.VocabularyMetadata metadata = null;
        if (json.metadata != null) {
            metadata = new DomainVocabulary.VocabularyMetadata(
                json.metadata.author,
                json.metadata.createdAt,
                json.metadata.description
            );
        }

        return new DomainVocabulary(
            json.id, json.name, json.locale, json.version,
            structs, fields, functions, enumValues, metadata
        );
    }

    private static List<IdentifierMapping> convertMappings(List<JsonMapping> jsonMappings, IdentifierKind kind) {
        if (jsonMappings == null) {
            return List.of();
        }

        List<IdentifierMapping> result = new ArrayList<>();
        for (JsonMapping jm : jsonMappings) {
            result.add(new IdentifierMapping(
                jm.canonical,
                jm.localized,
                kind,
                jm.parent,
                jm.description,
                jm.aliases != null ? List.copyOf(jm.aliases) : List.of()
            ));
        }
        return result;
    }

    private static JsonVocabulary convertToJson(DomainVocabulary vocabulary) {
        JsonVocabulary json = new JsonVocabulary();
        json.id = vocabulary.id();
        json.name = vocabulary.name();
        json.locale = vocabulary.locale();
        json.version = vocabulary.version();

        if (vocabulary.metadata() != null) {
            json.metadata = new JsonMetadata();
            json.metadata.author = vocabulary.metadata().author();
            json.metadata.createdAt = vocabulary.metadata().createdAt();
            json.metadata.description = vocabulary.metadata().description();
        }

        json.structs = convertToJsonMappings(vocabulary.structs());
        json.fields = convertToJsonMappings(vocabulary.fields());
        json.functions = convertToJsonMappings(vocabulary.functions());
        json.enumValues = convertToJsonMappings(vocabulary.enumValues());

        return json;
    }

    private static List<JsonMapping> convertToJsonMappings(List<IdentifierMapping> mappings) {
        List<JsonMapping> result = new ArrayList<>();
        for (IdentifierMapping m : mappings) {
            JsonMapping jm = new JsonMapping();
            jm.canonical = m.canonical();
            jm.localized = m.localized();
            jm.parent = m.parent();
            jm.description = m.description();
            jm.aliases = m.aliases().isEmpty() ? null : new ArrayList<>(m.aliases());
            result.add(jm);
        }
        return result;
    }

    // ==========================================
    // JSON 映射类
    // ==========================================

    private static class JsonVocabulary {
        public String id;
        public String name;
        public String locale;
        public String version;
        public JsonMetadata metadata;
        public List<JsonMapping> structs;
        public List<JsonMapping> fields;
        public List<JsonMapping> functions;
        @JsonProperty("enumValues")
        public List<JsonMapping> enumValues;
    }

    private static class JsonMetadata {
        public String author;
        public String createdAt;
        public String description;
    }

    private static class JsonMapping {
        public String canonical;
        public String localized;
        public String parent;
        public String description;
        public List<String> aliases;
    }
}
