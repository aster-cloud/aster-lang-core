package aster.core.parser;

import aster.core.ast.*;
import aster.core.inference.TypeInference;
import aster.core.util.StringEscapes;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AST 构建访问器
 * <p>
 * 将 ANTLR4 Parse Tree 转换为 Aster Lang 的不可变 AST 数据结构。
 * 每个 visit 方法对应一个语法规则，返回相应的 AST 节点。
 */
public class AstBuilder extends AsterParserBaseVisitor<Object> {

    private static final Set<String> BUILTIN_TYPE_NAMES = Set.of(
        "Int", "Bool", "Text", "Long", "Double", "Decimal", "Number", "Float", "Option", "Result", "List", "Map"
    );

    private final Set<String> declaredTypeNames = new HashSet<>();
    /**
     * 可【构造】的类型名（Data/Enum 记录类型），不含 type alias。
     * 仅用于「位置式构造禁止」检查——type alias 别名标量（如 Int），本就不该用
     * {@code with..set to} 构造，故不纳入；与 aster-lang-ts 行为对齐。
     */
    private final Set<String> declaredRecordTypeNames = new HashSet<>();

    private record TypeWithAnnotations(Type type, List<Annotation> annotations) {}

    /**
     * 红队 P2-H：表达式嵌套深度上限（防深嵌套 CNL 递归 StackOverflow DoS）。
     * 与 aster-lang-ts 的 MAX_RECURSION_DEPTH=300 对齐，维持双引擎行为一致。
     * 每进入一层 {@link #visitExpr} 计数，超限即抛（可恢复的解析错误，由错误恢复层
     * 转成 Diagnostic）。ANTLR 生成解析器自身的解析期递归另由 64KB 源长度上限约束
     * （见 SourcePolicyRequest @Size / CnlSourceLimits），此处守 AstBuilder 访问期递归。
     */
    static final int MAX_EXPR_DEPTH = 300;
    private int exprDepth = 0;

    // ============================================================
    // 模块和顶层声明
    // ============================================================

    @Override
    public aster.core.ast.Module visitModule(AsterParser.ModuleContext ctx) {
        String name = null;
        if (ctx.moduleHeader() != null) {
            // 新旧语法均通过 qualifiedName 提取模块名
            name = visitQualifiedName(ctx.moduleHeader().qualifiedName());
        }

        declaredTypeNames.clear();
        declaredRecordTypeNames.clear();
        List<AsterParser.TopLevelDeclContext> declContexts = ctx.topLevelDecl();
        if (declContexts == null) {
            declContexts = List.of();
        }

        declContexts.forEach(declCtx -> {
            if (declCtx.dataDecl() != null) {
                // 防御性 null-check：正常 dataDecl 一定有 TYPE_IDENT，但若文法
                // 误路由（历史上 funcDecl 歧义导致过），这里宁可跳过也不 NPE。
                TerminalNode typeName = declCtx.dataDecl().TYPE_IDENT();
                if (typeName != null) {
                    declaredTypeNames.add(typeName.getText());
                    declaredRecordTypeNames.add(typeName.getText());  // 可构造
                }
            } else if (declCtx.enumDecl() != null) {
                TerminalNode typeName = declCtx.enumDecl().TYPE_IDENT();
                if (typeName != null) {
                    declaredTypeNames.add(typeName.getText());
                    declaredRecordTypeNames.add(typeName.getText());  // 可构造
                }
            } else if (declCtx.typeDecl() != null) {
                // type alias 别名标量，不可构造 → 只进 declaredTypeNames，不进 record 集
                AsterParser.TypeDeclContext typeDeclCtx = declCtx.typeDecl();
                // ADR 0019 G1：类型别名名也可为软关键字（小写结构词）。与 visitTypeDecl
                // 的取名分支保持一致，避免 declaredTypeNames 预扫描漏登记。
                String aliasName = typeDeclCtx.TYPE_IDENT() != null
                    ? typeDeclCtx.TYPE_IDENT().getText()
                    : typeDeclCtx.IDENT() != null ? typeDeclCtx.IDENT().getText()
                    : typeDeclCtx.structKeywordName() != null ? typeDeclCtx.structKeywordName().getText() : null;
                if (aliasName != null && !aliasName.isEmpty()) {
                    declaredTypeNames.add(aliasName);
                }
            }
        });

        List<Decl> decls = declContexts.stream()
            .map(this::visitTopLevelDecl)
            .map(obj -> (Decl) obj)
            .collect(Collectors.toList());

        return new aster.core.ast.Module(name, decls, spanFrom(ctx));
    }

    @Override
    public String visitQualifiedName(AsterParser.QualifiedNameContext ctx) {
        return ctx.qualifiedSegment().stream()
            // 段可能是 IDENT / TYPE_IDENT，也可能是软关键字 AND/OR/NOT
            // （模块名/Use 路径里这些词当普通标识符用，见 grammar qualifiedSegment）。
            // 直接取段文本，避免按 token 类型逐一判空导致 NPE。
            .map(segment -> segment.getText())
            .collect(Collectors.joining("."));
    }

    @Override
    public Decl visitTopLevelDecl(AsterParser.TopLevelDeclContext ctx) {
        if (ctx.funcDecl() != null) {
            return visitFuncDecl(ctx.funcDecl());
        } else if (ctx.dataDecl() != null) {
            return visitDataDecl(ctx.dataDecl());
        } else if (ctx.enumDecl() != null) {
            return visitEnumDecl(ctx.enumDecl());
        } else if (ctx.typeDecl() != null) {
            return visitTypeDecl(ctx.typeDecl());
        } else if (ctx.importDecl() != null) {
            return visitImportDecl(ctx.importDecl());
        }
        throw new IllegalStateException("Unknown top level declaration");
    }

    // ============================================================
    // 函数声明
    // ============================================================

    @Override
    public Decl.Func visitFuncDecl(AsterParser.FuncDeclContext ctx) {
        List<Annotation> annotations = ctx.annotation() == null ? List.of()
            : ctx.annotation().stream().map(this::visitAnnotation).collect(Collectors.toList());

        // 函数名可以是 IDENT 或 TYPE_IDENT（支持 CJK 字符）
        if (ctx.nameIdent() == null) {
            throw new IllegalStateException("Rule 声明缺失名称");
        }
        String name = nameIdentText(ctx.nameIdent());

        List<String> typeParams = new ArrayList<>();
        if (ctx.typeParamList() != null) {
            typeParams = ctx.typeParamList().typeParam().stream()
                .map(tpCtx -> {
                    if (tpCtx.TYPE_IDENT() != null) {
                        return tpCtx.TYPE_IDENT().getText();
                    } else if (tpCtx.IDENT() != null) {
                        return tpCtx.IDENT().getText();
                    } else if (tpCtx.structKeywordName() != null) {
                        // ADR 0019 G1：类型参数名为软关键字（小写结构词）。
                        return tpCtx.structKeywordName().getText();
                    }
                    return null;
                })
                .filter(paramName -> paramName != null && !paramName.isEmpty())
                .collect(Collectors.toList());
        }

        // 参数列表
        List<Decl.Parameter> params = new ArrayList<>();
        if (ctx.givenParamList() != null) {
            params = ctx.givenParamList().param().stream()
                .map(this::visitParam)
                .collect(Collectors.toList());
        }

        // 返回类型
        Type retType;
        List<Annotation> retAnnotations;

        if (ctx.annotatedType() != null) {
            // 显式返回类型
            TypeWithAnnotations retInfo = extractAnnotatedType(ctx.annotatedType());
            retType = retInfo.type();
            retAnnotations = retInfo.annotations();
        } else {
            // 隐式返回类型 - 使用类型推断
            // 对于 generateQuote，尝试推断为对应的数据类型（首字母大写）
            String inferredTypeName = inferReturnTypeName(name);
            retType = new Type.TypeName(inferredTypeName, List.of(), spanFrom(ctx));
            retAnnotations = List.of();
        }

        if (typeParams.isEmpty()) {
            LinkedHashSet<String> inferred = new LinkedHashSet<>();
            for (Decl.Parameter param : params) {
                collectTypeParamCandidates(param.type(), inferred);
            }
            collectTypeParamCandidates(retType, inferred);
            typeParams = new ArrayList<>(inferred);
        }

        List<String> finalTypeParams = typeParams.isEmpty() ? List.of() : List.copyOf(typeParams);

        // 能力标注（默认无副作用）
        List<String> effects = List.of();
        List<String> effectCaps = List.of();
        boolean effectCapsExplicit = false;
        if (ctx.capabilityAnnotation() != null) {
            AsterParser.CapabilityAnnotationContext capCtx = ctx.capabilityAnnotation();
            // 提取效应名称（如 "io"）
            String effectName = capCtx.IDENT().getText();
            effects = List.of(effectName);

            // 提取能力列表（如 ["Http", "Sql"]）
            List<String> caps = capCtx.TYPE_IDENT().stream()
                .map(TerminalNode::getText)
                .collect(Collectors.toList());
            effectCaps = caps.isEmpty() ? List.of() : List.copyOf(caps);

            // 只有显式列出能力时才标记为 explicit
            effectCapsExplicit = !caps.isEmpty();
        }

        // 函数体（可能为 null）
        // ADR 0028：函数体经 funcBody（缩进块 block 或显式块 explicitBlock）。
        Block body = null;
        if (ctx.funcBody() != null) {
            AsterParser.FuncBodyContext fb = ctx.funcBody();
            if (fb.block() != null) {
                body = visitBlock(fb.block());
            } else if (fb.explicitBlock() != null) {
                body = visitExplicitBlock(fb.explicitBlock());
            }
        }

        Span nameSpan = spanFrom(ctx.nameIdent());

        List<Annotation> finalAnnotations = annotations.isEmpty() ? List.of() : List.copyOf(annotations);
        List<Annotation> finalRetAnnotations = retAnnotations.isEmpty() ? List.of() : List.copyOf(retAnnotations);

        return new Decl.Func(
            name,
            nameSpan,
            finalTypeParams,
            params,
            retType,
            finalAnnotations,
            finalRetAnnotations,
            body,
            effects,
            effectCaps,
            effectCapsExplicit,
            spanFrom(ctx)
        );
    }

    @Override
    public Decl.Parameter visitParam(AsterParser.ParamContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        Span span = spanFrom(ctx);

        Type type;
        List<Annotation> typeAnnotations;

        // 检查是否有显式类型标注
        if (ctx.annotatedType() != null) {
            // 显式类型路径
            TypeWithAnnotations typeInfo = extractAnnotatedType(ctx.annotatedType());
            type = typeInfo.type();
            typeAnnotations = typeInfo.annotations();
        } else {
            // 隐式类型路径 - 使用类型推断
            type = TypeInference.inferFieldType(name, span);
            typeAnnotations = List.of();
        }

        List<Annotation> prefixAnnotations = ctx.annotation() == null
            ? List.of()
            : ctx.annotation().stream().map(this::visitAnnotation).collect(Collectors.toList());
        List<Annotation> allAnnotations = new ArrayList<>(prefixAnnotations);
        allAnnotations.addAll(typeAnnotations);
        List<Annotation> finalAnnotations = allAnnotations.isEmpty() ? List.of() : List.copyOf(allAnnotations);
        return new Decl.Parameter(name, type, finalAnnotations, span);
    }

    private void collectTypeParamCandidates(Type type, Set<String> acc) {
        if (type == null) {
            return;
        }
        if (type instanceof Type.TypeVar typeVar) {
            String name = typeVar.name();
            if (name != null && !name.isEmpty()) {
                acc.add(name);
            }
            return;
        }
        if (type instanceof Type.TypeName typeName) {
            String name = typeName.name();
            if (looksLikeTypeParam(name)
                && !BUILTIN_TYPE_NAMES.contains(name)
                && !declaredTypeNames.contains(name)) {
                acc.add(name);
            }
            return;
        }
        if (type instanceof Type.TypeApp typeApp) {
            typeApp.args().forEach(arg -> collectTypeParamCandidates(arg, acc));
            return;
        }
        if (type instanceof Type.Result resultType) {
            collectTypeParamCandidates(resultType.ok(), acc);
            collectTypeParamCandidates(resultType.err(), acc);
            return;
        }
        if (type instanceof Type.Maybe maybeType) {
            collectTypeParamCandidates(maybeType.type(), acc);
            return;
        }
        if (type instanceof Type.Option optionType) {
            collectTypeParamCandidates(optionType.type(), acc);
            return;
        }
        if (type instanceof Type.List listType) {
            collectTypeParamCandidates(listType.type(), acc);
            return;
        }
        if (type instanceof Type.Map mapType) {
            collectTypeParamCandidates(mapType.key(), acc);
            collectTypeParamCandidates(mapType.val(), acc);
            return;
        }
        if (type instanceof Type.FuncType funcType) {
            funcType.params().forEach(paramType -> collectTypeParamCandidates(paramType, acc));
            collectTypeParamCandidates(funcType.ret(), acc);
        }
    }

    private boolean looksLikeTypeParam(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isUpperCase(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * 从函数名推断返回类型
     *
     * 规则：
     * 1. 如果函数名以 "generate"、"create"、"build" 等前缀开头，
     *    提取后缀并首字母大写作为类型名
     *    例如: generateQuote → Quote, buildResult → Result
     * 2. 如果函数名以 "calculate"、"compute" 等计算前缀开头，默认返回 Int
     * 3. 如果函数名以 "is"、"has"、"can" 等布尔前缀开头，返回 Bool
     * 4. 其他情况默认返回 Text
     */
    private String inferReturnTypeName(String funcName) {
        if (funcName == null || funcName.isEmpty()) {
            return "Text";
        }

        // 生成/创建类前缀 - 提取后缀作为类型名
        String[] generatorPrefixes = {"generate", "create", "build", "make", "new", "get", "fetch", "load"};
        for (String prefix : generatorPrefixes) {
            if (funcName.toLowerCase(Locale.ROOT).startsWith(prefix) && funcName.length() > prefix.length()) {
                String suffix = funcName.substring(prefix.length());
                // 首字母大写
                if (!suffix.isEmpty()) {
                    return Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
                }
            }
        }

        // 计算类前缀 - 返回 Int
        String[] calculatePrefixes = {"calculate", "compute", "count", "sum", "total"};
        for (String prefix : calculatePrefixes) {
            if (funcName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return "Int";
            }
        }

        // 布尔前缀 - 返回 Bool
        String[] boolPrefixes = {"is", "has", "can", "should", "will", "did", "does", "allow", "enable", "disable", "check", "validate"};
        for (String prefix : boolPrefixes) {
            if (funcName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return "Bool";
            }
        }

        // 查找类前缀 - 返回 Text
        String[] findPrefixes = {"find", "search", "lookup"};
        for (String prefix : findPrefixes) {
            if (funcName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return "Text";
            }
        }

        // 默认返回 Text
        return "Text";
    }

    // ============================================================
    // 数据类型和枚举声明
    // ============================================================

    @Override
    public Decl.Data visitDataDecl(AsterParser.DataDeclContext ctx) {
        String name = ctx.TYPE_IDENT().getText();
        List<Decl.Field> fields = ctx.fieldList().field().stream()
            .map(this::visitField)
            .collect(Collectors.toList());
        return new Decl.Data(name, fields, spanFrom(ctx));
    }

    @Override
    public Decl.Field visitField(AsterParser.FieldContext ctx) {
        String name = nameIdentText(ctx.nameIdent());

        Type type;
        List<Annotation> typeAnnotations;

        // 检查是否有显式类型标注
        if (ctx.annotatedType() != null) {
            // 显式类型路径
            TypeWithAnnotations typeInfo = extractAnnotatedType(ctx.annotatedType());
            type = typeInfo.type();
            typeAnnotations = typeInfo.annotations();
        } else {
            // 隐式类型路径 - 使用类型推断
            Span fieldSpan = spanFrom(ctx);
            type = TypeInference.inferFieldType(name, fieldSpan);
            typeAnnotations = List.of();
        }

        List<Annotation> prefixAnnotations = ctx.annotation() == null
            ? List.of()
            : ctx.annotation().stream().map(this::visitAnnotation).collect(Collectors.toList());
        List<Annotation> allAnnotations = new ArrayList<>(prefixAnnotations);
        allAnnotations.addAll(typeAnnotations);
        List<Annotation> finalAnnotations = allAnnotations.isEmpty() ? List.of() : List.copyOf(allAnnotations);
        return new Decl.Field(name, type, finalAnnotations);
    }

    private String nameIdentText(AsterParser.NameIdentContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("name 标识符上下文不能为空");
        }
        // nameIdent 是单 token（IDENT/TYPE_IDENT 或软关键字 TYPE/VERSION/MAX），
        // 直接取文本，避免按 token 类型逐一判空，新增软关键字时无需同步改这里。
        return ctx.getText();
    }

    private String ofGenericTypeName(AsterParser.OfGenericTypeContext ctx) {
        if (ctx.TYPE_IDENT() != null) {
            return ctx.TYPE_IDENT().getText();
        }
        if (ctx.IDENT() != null) {
            return ctx.IDENT().getText();
        }
        throw new IllegalStateException("泛型类型缺失名称");
    }

    @Override
    public Decl.Enum visitEnumDecl(AsterParser.EnumDeclContext ctx) {
        String name = ctx.TYPE_IDENT().getText();
        List<String> variants = ctx.variantList().TYPE_IDENT().stream()
            .map(TerminalNode::getText)
            .collect(Collectors.toList());
        return new Decl.Enum(name, variants, spanFrom(ctx));
    }

    @Override
    public Decl.TypeAlias visitTypeDecl(AsterParser.TypeDeclContext ctx) {
        List<AsterParser.AnnotationContext> annotationCtxs = ctx.annotation();
        if (annotationCtxs == null) {
            annotationCtxs = List.of();
        }
        List<String> annotations = annotationCtxs.stream()
            .map(this::annotationName)
            .collect(Collectors.toList());
        List<String> finalAnnotations = annotations.isEmpty() ? List.of() : List.copyOf(annotations);
        String name = ctx.TYPE_IDENT() != null
            ? ctx.TYPE_IDENT().getText()
            : ctx.IDENT() != null ? ctx.IDENT().getText()
            // ADR 0019 G1：类型别名名为软关键字（小写结构词）。
            : ctx.structKeywordName() != null ? ctx.structKeywordName().getText() : null;
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("Type alias 缺失名称");
        }
        Type type = extractAnnotatedType(ctx.annotatedType()).type();
        return new Decl.TypeAlias(finalAnnotations, name, type, spanFrom(ctx));
    }

    @Override
    public Annotation visitAnnotation(AsterParser.AnnotationContext ctx) {
        String name = annotationName(ctx);
        Map<String, Object> params = new LinkedHashMap<>();
        if (ctx.annotationArgs() != null) {
            int positionalIndex = 0;
            for (AsterParser.AnnotationArgContext argCtx : ctx.annotationArgs().annotationArg()) {
                if (argCtx instanceof AsterParser.NamedAnnotationArgContext namedCtx) {
                    // ADR 0019 G1：注解参数名可为软关键字（小写结构词）。
                    String key = namedCtx.IDENT() != null
                        ? namedCtx.IDENT().getText()
                        : namedCtx.structKeywordName().getText();
                    Object value = parseAnnotationValue(namedCtx.annotationValue());
                    params.put(key, value);
                } else if (argCtx instanceof AsterParser.PositionalAnnotationArgContext positionalCtx) {
                    String key = "$" + positionalIndex++;
                    Object value = parseAnnotationValue(positionalCtx.annotationValue());
                    params.put(key, value);
                }
            }
        }
        Map<String, Object> finalParams = params.isEmpty() ? Map.of() : Map.copyOf(params);
        return new Annotation(name, finalParams);
    }

    private String annotationName(AsterParser.AnnotationContext ctx) {
        if (ctx.IDENT() != null) {
            return ctx.IDENT().getText();
        }
        if (ctx.TYPE_IDENT() != null) {
            return ctx.TYPE_IDENT().getText();
        }
        // ADR 0019 G1：注解名为软关键字（小写结构词）。
        if (ctx.structKeywordName() != null) {
            return ctx.structKeywordName().getText();
        }
        throw new IllegalStateException("注解缺失名称");
    }

    private Object parseAnnotationValue(AsterParser.AnnotationValueContext ctx) {
        if (ctx.STRING_LITERAL() != null) {
            String raw = ctx.STRING_LITERAL().getText();
            String inner = raw.substring(1, raw.length() - 1);
            return StringEscapes.unescape(inner);
        }
        if (ctx.INT_LITERAL() != null) {
            return LiteralParsing.parseInt(ctx.INT_LITERAL().getText());
        }
        if (ctx.FLOAT_LITERAL() != null) {
            return LiteralParsing.parseDouble(ctx.FLOAT_LITERAL().getText());
        }
        if (ctx.LONG_LITERAL() != null) {
            String text = ctx.LONG_LITERAL().getText();
            String digits = text.substring(0, text.length() - 1);
            return LiteralParsing.parseLong(digits);
        }
        if (ctx.BOOL_LITERAL() != null) {
            return Boolean.parseBoolean(ctx.BOOL_LITERAL().getText());
        }
        if (ctx.IDENT() != null) {
            return ctx.IDENT().getText();
        }
        if (ctx.TYPE_IDENT() != null) {
            return ctx.TYPE_IDENT().getText();
        }
        // ADR 0019 G1：注解值为软关键字（小写结构词）。
        if (ctx.structKeywordName() != null) {
            return ctx.structKeywordName().getText();
        }
        throw new IllegalStateException("无法解析注解参数值");
    }

    // ============================================================
    // 导入声明
    // ============================================================

    @Override
    public Decl.Import visitImportDecl(AsterParser.ImportDeclContext ctx) {
        String path = visitQualifiedName(ctx.qualifiedName());
        Integer version = ctx.INT_LITERAL() == null
            ? null
            : LiteralParsing.parseInt(ctx.INT_LITERAL().getText());
        String alias = null;
        if (ctx.importAlias() != null) {
            AsterParser.ImportAliasContext aliasCtx = ctx.importAlias();
            if (aliasCtx.TYPE_IDENT() != null) {
                alias = aliasCtx.TYPE_IDENT().getText();
            } else if (aliasCtx.IDENT() != null) {
                alias = aliasCtx.IDENT().getText();
            } else if (aliasCtx.structKeywordName() != null) {
                // ADR 0019 G1：导入别名为软关键字（小写结构词）。
                alias = aliasCtx.structKeywordName().getText();
            }
        }
        return new Decl.Import(path, version, alias, spanFrom(ctx));
    }

    // ============================================================
    // 类型表达式
    // ============================================================

    private TypeWithAnnotations extractAnnotatedType(AsterParser.AnnotatedTypeContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("annotated type 上下文不能为空");
        }
        List<Annotation> annotations = ctx.annotation().stream()
            .map(this::visitAnnotation)
            .collect(Collectors.toList());
        List<Annotation> finalAnnotations = annotations.isEmpty() ? List.of() : List.copyOf(annotations);
        Type baseType = visitType(ctx.type());
        Type annotatedType = finalAnnotations.isEmpty() ? baseType : withTypeAnnotations(baseType, finalAnnotations);
        return new TypeWithAnnotations(annotatedType, finalAnnotations);
    }

    private Type withTypeAnnotations(Type baseType, List<Annotation> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return baseType;
        }
        List<Annotation> existing = baseType.annotations() == null ? List.of() : baseType.annotations();
        List<Annotation> merged;
        if (existing.isEmpty()) {
            merged = List.copyOf(annotations);
        } else {
            List<Annotation> combined = new ArrayList<>(existing);
            combined.addAll(annotations);
            merged = List.copyOf(combined);
        }
        if (baseType instanceof Type.TypeName typeName) {
            return new Type.TypeName(typeName.name(), merged, typeName.span());
        }
        if (baseType instanceof Type.TypeVar typeVar) {
            return new Type.TypeVar(typeVar.name(), merged, typeVar.span());
        }
        if (baseType instanceof Type.TypeApp typeApp) {
            return new Type.TypeApp(typeApp.base(), merged, typeApp.args(), typeApp.span());
        }
        if (baseType instanceof Type.Result result) {
            return new Type.Result(result.ok(), result.err(), merged, result.span());
        }
        if (baseType instanceof Type.Maybe maybe) {
            return new Type.Maybe(maybe.type(), merged, maybe.span());
        }
        if (baseType instanceof Type.Option option) {
            return new Type.Option(option.type(), merged, option.span());
        }
        if (baseType instanceof Type.List list) {
            return new Type.List(list.type(), merged, list.span());
        }
        if (baseType instanceof Type.Map map) {
            return new Type.Map(map.key(), map.val(), merged, map.span());
        }
        if (baseType instanceof Type.FuncType funcType) {
            return new Type.FuncType(funcType.params(), funcType.ret(), merged, funcType.span());
        }
        throw new IllegalStateException("无法为类型附加注解: " + baseType.getClass().getSimpleName());
    }

    @Override
    public Type visitTypeName(AsterParser.TypeNameContext ctx) {
        String name = ctx.TYPE_IDENT().getText();
        return new Type.TypeName(name, List.of(), spanFrom(ctx));
    }

    @Override
    public Type visitGenericType(AsterParser.GenericTypeContext ctx) {
        String name = ctx.TYPE_IDENT().getText();
        List<Type> args = ctx.typeList().annotatedType().stream()
            .map(this::extractAnnotatedType)
            .map(TypeWithAnnotations::type)
            .collect(Collectors.toList());
        return new Type.TypeApp(name, List.of(), args, spanFrom(ctx));
    }

    @Override
    public Type visitOfGenericType(AsterParser.OfGenericTypeContext ctx) {
        String name = ofGenericTypeName(ctx);
        List<Type> args = ctx.annotatedType().stream()
            .map(this::extractAnnotatedType)
            .map(TypeWithAnnotations::type)
            .collect(Collectors.toList());
        if ("list".equalsIgnoreCase(name) && !args.isEmpty()) {
            Type inner = args.get(0);
            return new Type.List(inner, List.of(), spanFrom(ctx));
        }
        return new Type.TypeApp(name, List.of(), args, spanFrom(ctx));
    }

    @Override
    public Type visitParenType(AsterParser.ParenTypeContext ctx) {
        return visitType(ctx.type());
    }

    @Override
    public Type visitMaybeType(AsterParser.MaybeTypeContext ctx) {
        Type innerType = visitType(ctx.type());
        return new Type.Maybe(innerType, List.of(), spanFrom(ctx));
    }

    @Override
    public Type visitFuncType(AsterParser.FuncTypeContext ctx) {
        List<Type> params = ctx.typeList().annotatedType().stream()
            .map(this::extractAnnotatedType)
            .map(TypeWithAnnotations::type)
            .collect(Collectors.toList());
        Type ret = visitType(ctx.type());
        return new Type.FuncType(params, ret, List.of(), spanFrom(ctx));
    }

    @Override
    public Type visitMapType(AsterParser.MapTypeContext ctx) {
        List<AsterParser.AnnotatedTypeContext> types = ctx.annotatedType();
        if (types.size() != 2) {
            throw new IllegalStateException("Map 类型需要键和值两种类型");
        }
        Type key = extractAnnotatedType(types.get(0)).type();
        Type val = extractAnnotatedType(types.get(1)).type();
        return new Type.Map(key, val, List.of(), spanFrom(ctx));
    }

    private Type visitType(AsterParser.TypeContext ctx) {
        return (Type) visit(ctx);
    }

    // ============================================================
    // 语句
    // ============================================================

    @Override
    public Block visitBlock(AsterParser.BlockContext ctx) {
        List<Stmt> stmts = ctx.stmt().stream()
            .map(this::visitStmt)
            .map(obj -> (Stmt) obj)
            .collect(Collectors.toList());
        Span blockSpan = spanFrom(ctx);
        if (!stmts.isEmpty()) {
            Span first = stmts.get(0).span();
            Span last = stmts.get(stmts.size() - 1).span();
            if (first != null && last != null) {
                blockSpan = new Span(first.start(), last.end());
            }
        }
        return new Block(stmts, blockSpan);
    }

    // ADR 0028：显式块与缩进块产出**同款 Block AST**（仅结束方式不同：DEDENT vs BLOCK_END）。
    // 逻辑与 visitBlock 一致——从 stmt 列表构造 Block，span 取首末语句。这保证显式块与缩进块
    // 编译到字节级一致的 Core IR（与 TS 引擎对齐）。
    @Override
    public Block visitExplicitBlock(AsterParser.ExplicitBlockContext ctx) {
        List<Stmt> stmts = ctx.stmt().stream()
            .map(this::visitStmt)
            .map(obj -> (Stmt) obj)
            .collect(Collectors.toList());
        Span blockSpan = spanFrom(ctx);
        if (!stmts.isEmpty()) {
            Span first = stmts.get(0).span();
            Span last = stmts.get(stmts.size() - 1).span();
            if (first != null && last != null) {
                blockSpan = new Span(first.start(), last.end());
            }
        }
        return new Block(stmts, blockSpan);
    }

    @Override
    public Stmt visitStmt(AsterParser.StmtContext ctx) {
        return (Stmt) super.visitStmt(ctx);
    }

    @Override
    public Stmt.Let visitLetExprStmt(AsterParser.LetExprStmtContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        Expr expr = (Expr) visit(ctx.expr());
        return new Stmt.Let(name, expr, spanFrom(ctx));
    }

    @Override
    public Stmt.Let visitLetLambdaStmt(AsterParser.LetLambdaStmtContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        Expr lambda = (Expr) visit(ctx.lambdaExpr());
        return new Stmt.Let(name, lambda, spanFrom(ctx));
    }

    @Override
    public Stmt.Let visitDefineStmt(AsterParser.DefineStmtContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        Expr expr = (Expr) visit(ctx.expr());
        return new Stmt.Let(name, expr, spanFrom(ctx));
    }

    @Override
    public Expr visitLogicalNot(AsterParser.LogicalNotContext ctx) {
        // 新 grammar：LogicalNot 的操作数是 notExpr（NOT notExpr，右结合），不再是 unaryExpr。
        // not 现松于比较（`not x greater than y` = not(x greater than y)），对齐 TS。
        Expr operand = (Expr) visit(ctx.notExpr());
        Expr.Name notName = new Expr.Name("not", spanFrom(ctx.NOT().getSymbol()));
        return new Expr.Call(notName, List.of(operand), spanFrom(ctx));
    }

    @Override
    public Stmt.Start visitStartStmt(AsterParser.StartStmtContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        Expr task = (Expr) visit(ctx.expr());
        if (ctx.ASYNC() != null) {
            Span asyncSpan = spanFrom(ctx.ASYNC().getSymbol());
            Span taskSpan = task.span();
            Span callSpan = mergeSpans(asyncSpan, taskSpan);
            Expr.Name asyncName = new Expr.Name("async", asyncSpan);
            task = new Expr.Call(asyncName, List.of(task), callSpan);
        }
        return new Stmt.Start(name, task, spanFrom(ctx));
    }

    @Override
    public Stmt.Wait visitWaitStmt(AsterParser.WaitStmtContext ctx) {
        List<String> names = ctx.nameIdent().stream()
            .map(this::nameIdentText)
            .collect(Collectors.toList());
        List<String> finalNames = names.isEmpty() ? List.of() : List.copyOf(names);
        return new Stmt.Wait(finalNames, spanFrom(ctx));
    }

    @Override
    public Stmt.Workflow visitWorkflowStmt(AsterParser.WorkflowStmtContext ctx) {
        AsterParser.WorkflowBodyContext bodyCtx = ctx.workflowBody();
        if (bodyCtx == null) {
            throw new IllegalStateException("workflow 语句缺失主体");
        }
        List<Stmt.WorkflowStep> steps = bodyCtx.workflowStep().stream()
            .map(this::visitWorkflowStep)
            .collect(Collectors.toList());
        if (steps.isEmpty()) {
            throw new IllegalStateException("workflow 至少需要声明一个 step");
        }
        Stmt.RetryPolicy retry = bodyCtx.retrySection() != null ? visitRetrySection(bodyCtx.retrySection()) : null;
        Stmt.Timeout timeout = bodyCtx.timeoutSection() != null ? visitTimeoutSection(bodyCtx.timeoutSection()) : null;
        return new Stmt.Workflow(steps, retry, timeout, spanFrom(ctx));
    }

    @Override
    public Stmt.WorkflowStep visitWorkflowStep(AsterParser.WorkflowStepContext ctx) {
        String name = nameIdentText(ctx.nameIdent());
        List<String> dependencies = ctx.stepDependencies() != null
            ? extractDependencies(ctx.stepDependencies())
            : List.of();
        Block body = visitBlock(ctx.block());
        Block compensate = null;
        if (ctx.compensateSection() != null) {
            compensate = visitBlock(ctx.compensateSection().block());
        }
        return new Stmt.WorkflowStep(name, body, compensate, dependencies, spanFrom(ctx));
    }

    private List<String> extractDependencies(AsterParser.StepDependenciesContext ctx) {
        if (ctx == null || ctx.stringList() == null) {
            return List.of();
        }
        return ctx.stringList().STRING_LITERAL().stream()
            .map(this::parseStringLiteral)
            .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Stmt.RetryPolicy visitRetrySection(AsterParser.RetrySectionContext ctx) {
        Integer maxAttempts = null;
        String backoff = null;
        for (var directive : ctx.retryDirective()) {
            if (directive.MAX() != null) {
                int attempts = LiteralParsing.parseInt(directive.INT_LITERAL().getText());
                if (attempts <= 0) {
                    throw new IllegalStateException("Retry `max attempts` 必须大于 0");
                }
                maxAttempts = attempts;
            } else if (directive.BACKOFF() != null) {
                String raw = directive.IDENT() != null
                    ? directive.IDENT().getText()
                    : directive.TYPE_IDENT().getText();
                String normalized = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
                if (!normalized.equals("exponential") && !normalized.equals("linear")) {
                    throw new IllegalStateException("Retry `backoff` 仅支持 exponential 或 linear");
                }
                backoff = normalized;
            }
        }
        if (maxAttempts == null) {
            throw new IllegalStateException("Retry 区块必须声明 max attempts");
        }
        if (backoff == null) {
            throw new IllegalStateException("Retry 区块必须声明 backoff");
        }
        return new Stmt.RetryPolicy(maxAttempts, backoff);
    }

    @Override
    public Stmt.Timeout visitTimeoutSection(AsterParser.TimeoutSectionContext ctx) {
        long seconds = LiteralParsing.parseLong(ctx.INT_LITERAL().getText());
        if (seconds < 0) {
            throw new IllegalStateException("timeout 数值必须为非负整数");
        }
        return new Stmt.Timeout(seconds * 1000L);
    }

    @Override
    public Stmt.Return visitReturnStmt(AsterParser.ReturnStmtContext ctx) {
        Expr expr = (Expr) visit(ctx.expr());
        return new Stmt.Return(expr, spanFrom(ctx));
    }

    @Override
    public Stmt.If visitIfStmt(AsterParser.IfStmtContext ctx) {
        Expr cond = (Expr) visit(ctx.expr());
        Block thenBlock = visitBlock(ctx.block(0));
        Block elseBlock = null;
        if (ctx.block().size() > 1) {
            elseBlock = visitBlock(ctx.block(1));
        }
        return new Stmt.If(cond, thenBlock, elseBlock, spanFrom(ctx));
    }

    /**
     * ADR 0019 G2a：内联 if 语句降级为与块式 ifStmt 相同的 {@link Stmt.If}——单语句
     * 分支包成单元素 {@link Block}，else-if 链右递归成嵌套 If 的 else 分支。**不引入新
     * Core 节点**，下游 typecheck/eval/lowering 沿用既有 statement-If 路径。
     */
    @Override
    public Stmt.If visitInlineIfStmt(AsterParser.InlineIfStmtContext ctx) {
        Expr cond = (Expr) visit(ctx.expr());
        InlineBranches branches = lowerThenBranch(ctx.inlineThenBranch());
        return new Stmt.If(cond, branches.thenBlock(), branches.elseBlock(), spanFrom(ctx));
    }

    /** then 分支 + 可选 else 分支的降级结果。 */
    private record InlineBranches(Block thenBlock, Block elseBlock) {}

    private InlineBranches lowerThenBranch(AsterParser.InlineThenBranchContext ctx) {
        if (ctx.inlineReturn() != null) {
            // 形如 `then return X else <...>`：then 分支无 DOT，else 必存在。
            Block thenBlock = singleStmtBlock(lowerInlineReturn(ctx.inlineReturn()));
            Block elseBlock;
            if (ctx.inlineElseIf() != null) {
                // else if 链：递归成嵌套 If，作为 else 分支的唯一语句。
                Stmt.If nested = lowerInlineElseIf(ctx.inlineElseIf());
                elseBlock = singleStmtBlock(nested);
            } else {
                // else return Z.（最末分支带 DOT）。
                elseBlock = singleStmtBlock(visitReturnStmt(ctx.returnStmt()));
            }
            return new InlineBranches(thenBlock, elseBlock);
        }
        // 无 else：`if cond then return X.`（returnStmt 带 DOT）。
        Block thenBlock = singleStmtBlock(visitReturnStmt(ctx.returnStmt()));
        return new InlineBranches(thenBlock, null);
    }

    private Stmt.If lowerInlineElseIf(AsterParser.InlineElseIfContext ctx) {
        Expr cond = (Expr) visit(ctx.expr());
        InlineBranches branches = lowerThenBranch(ctx.inlineThenBranch());
        return new Stmt.If(cond, branches.thenBlock(), branches.elseBlock(), spanFrom(ctx));
    }

    private Stmt.Return lowerInlineReturn(AsterParser.InlineReturnContext ctx) {
        Expr expr = (Expr) visit(ctx.expr());
        return new Stmt.Return(expr, spanFrom(ctx));
    }

    private Block singleStmtBlock(Stmt stmt) {
        return new Block(List.of(stmt), stmt.span());
    }

    @Override
    public Stmt.Match visitMatchStmt(AsterParser.MatchStmtContext ctx) {
        Expr expr = (Expr) visit(ctx.expr());
        List<Stmt.Case> cases = ctx.matchCase().stream()
            .map(this::visitMatchCase)
            .collect(Collectors.toList());
        return new Stmt.Match(expr, cases, spanFrom(ctx));
    }

    public Stmt.Case visitMatchCase(AsterParser.MatchCaseContext ctx) {
        Pattern pattern = visitPattern(ctx.pattern());
        Stmt.Case.CaseBody body;
        if (ctx.returnStmt() != null) {
            body = visitReturnStmt(ctx.returnStmt());
        } else {
            body = visitBlock(ctx.block());
        }
        return new Stmt.Case(pattern, body, spanFrom(ctx));
    }

    private Pattern visitPattern(AsterParser.PatternContext ctx) {
        return (Pattern) visit(ctx);
    }

    @Override
    public Pattern visitPatternNull(AsterParser.PatternNullContext ctx) {
        return new Pattern.PatternNull(spanFrom(ctx));
    }

    @Override
    public Pattern visitPatternName(AsterParser.PatternNameContext ctx) {
        String name = ctx.IDENT().getText();
        return new Pattern.PatternName(name, spanFrom(ctx));
    }

    /**
     * ADR 0019 G1：match 绑定名为软关键字（小写结构词，如 `when return, ...`）。
     * 与 PatternName 同构，仅 token 来源不同。
     */
    @Override
    public Pattern visitPatternStructKeywordName(AsterParser.PatternStructKeywordNameContext ctx) {
        String name = ctx.structKeywordName().getText();
        return new Pattern.PatternName(name, spanFrom(ctx));
    }

    @Override
    public Pattern visitPatternInt(AsterParser.PatternIntContext ctx) {
        int value = LiteralParsing.parseInt(ctx.INT_LITERAL().getText());
        return new Pattern.PatternInt(value, spanFrom(ctx));
    }

    @Override
    public Pattern visitPatternCtor(AsterParser.PatternCtorContext ctx) {
        String typeName = ctx.TYPE_IDENT().getText();
        List<Pattern> args = ctx.pattern().stream()
            .map(this::visitPattern)
            .collect(Collectors.toList());
        // Names are extracted from nested PatternName patterns
        List<String> names = args.stream()
            .filter(p -> p instanceof Pattern.PatternName)
            .map(p -> ((Pattern.PatternName) p).name())
            .collect(Collectors.toList());
        return new Pattern.PatternCtor(typeName, names, args, spanFrom(ctx));
    }

    @Override
    public Stmt visitExprStmt(AsterParser.ExprStmtContext ctx) {
        // 表达式语句：将表达式包装为 Return 语句
        // 注意：Aster CNL 中表达式语句实际上被当作隐式返回
        Expr expr = (Expr) visit(ctx.expr());
        return new Stmt.Return(expr, spanFrom(ctx));
    }

    // ============================================================
    // 表达式
    // ============================================================

    // expr : ifExpr | orExpr —— 分派到对应子节点。默认 visitChildren 在多 alt 下不可靠，
    // 显式取存在的那个分支。
    @Override
    public Expr visitExpr(AsterParser.ExprContext ctx) {
        // 红队 P2-H：表达式嵌套深度守卫。每个表达式子树都经此入口（paren/if-expr/运算数
        // 都递归回 visitExpr），故在此计数即覆盖全部嵌套形态。超限抛可恢复解析错误。
        if (++exprDepth > MAX_EXPR_DEPTH) {
            exprDepth--; // 抛前回退，保持计数对称（错误恢复后可能继续访问同级）
            throw new IllegalStateException(
                "表达式嵌套过深（> " + MAX_EXPR_DEPTH + "），拒绝以防栈溢出");
        }
        try {
            if (ctx.ifExpr() != null) {
                return (Expr) visit(ctx.ifExpr());
            }
            return (Expr) visit(ctx.orExpr());
        } finally {
            exprDepth--;
        }
    }

    // ADR 0019 G2b：表达式级 if —— `IF cond=orExpr inlineThen thenE=expr inlineElseSep elseE=expr`
    // → Expr.IfExpr{cond, thenExpr, elseExpr}。thenE/elseE 是 expr → 天然支持嵌套 if-expr。
    @Override
    public Expr visitIfExpr(AsterParser.IfExprContext ctx) {
        Expr cond = (Expr) visit(ctx.cond);
        Expr thenE = (Expr) visit(ctx.thenE);
        Expr elseE = (Expr) visit(ctx.elseE);
        return new Expr.IfExpr(cond, thenE, elseE, spanFrom(ctx));
    }

    // 逻辑或：andExpr (OR andExpr)* → 左结合的 Call{Name "or", [l, r]}
    @Override
    public Expr visitOrExpr(AsterParser.OrExprContext ctx) {
        Expr left = (Expr) visit(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            Expr right = (Expr) visit(ctx.andExpr(i));
            Expr.Name op = new Expr.Name("or", spanFrom(ctx.OR(i - 1).getSymbol()));
            left = new Expr.Call(op, List.of(left, right), spanFrom(ctx));
        }
        return left;
    }

    // 逻辑与：notExpr (AND notExpr)* → 左结合的 Call{Name "and", [l, r]}
    // （andExpr 操作数现为 notExpr——not 已上移到比较之上，对齐 TS）
    @Override
    public Expr visitAndExpr(AsterParser.AndExprContext ctx) {
        Expr left = (Expr) visit(ctx.notExpr(0));
        for (int i = 1; i < ctx.notExpr().size(); i++) {
            Expr right = (Expr) visit(ctx.notExpr(i));
            Expr.Name op = new Expr.Name("and", spanFrom(ctx.AND(i - 1).getSymbol()));
            left = new Expr.Call(op, List.of(left, right), spanFrom(ctx));
        }
        return left;
    }

    @Override
    public Expr visitComparisonExpr(AsterParser.ComparisonExprContext ctx) {
        Expr left = (Expr) visit(ctx.additiveExpr(0));
        if (ctx.op != null && ctx.additiveExpr().size() > 1) {
            Expr right = (Expr) visit(ctx.additiveExpr(1));
            String op = normalizeOperator(ctx.op.getText());
            return new Expr.Call(
                new Expr.Name(op, spanFrom(ctx.op)),
                List.of(left, right),
                spanFrom(ctx)
            );
        }
        // 软比较词分支：`under` / `over`（前置可选 `is`），ADR 0013 #1b-i 对齐 TS。
        if (ctx.softComparator() != null && ctx.additiveExpr().size() > 1) {
            Expr right = (Expr) visit(ctx.additiveExpr(1));
            String word = ctx.softComparator().word.getText();
            String op = normalizeOperator(word);  // under → <, over → >
            return new Expr.Call(
                new Expr.Name(op, spanFrom(ctx.softComparator().word)),
                List.of(left, right),
                spanFrom(ctx)
            );
        }
        return left;
    }

    /**
     * 将自然语言运算符转换为符号运算符
     */
    private String normalizeOperator(String op) {
        if (op == null) {
            return op;
        }
        // 去除多余空白并转小写进行匹配
        String normalized = op.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        // 剥离可选的 `is ` 连接词前缀（ADR 0013 #1b-i）：lexer 把
        // `is at least` 整体识别为 GREATER_THAN_OR_EQUAL_WORD 等比较词 token，
        // token 文本带 is 前缀；这里统一剥离后再映射，使 `is at least` 与
        // `at least` 归一到同一符号。`is equal to` / `is not equal to` 是
        // 整体短语（其语义就含 is），不剥离——它们在下面单列分支。
        String stripped = normalized;
        if ((normalized.startsWith("is ") && !normalized.equals("is equal to")
                && !normalized.equals("is not equal to"))) {
            stripped = normalized.substring(3).trim();
        }
        return switch (stripped) {
            case "less than" -> "<";
            case "greater than" -> ">";
            case "more than" -> ">";
            case "under" -> "<";
            case "over" -> ">";
            case "less than or equal to" -> "<=";
            case "at most" -> "<=";
            case "greater than or equal to" -> ">=";
            case "at least" -> ">=";
            case "equals to" -> "==";
            case "is equal to" -> "==";
            case "not equal to" -> "!=";
            case "is not equal to" -> "!=";
            case "=" -> "==";
            case "plus" -> "+";
            case "minus" -> "-";
            case "times" -> "*";
            case "divided by" -> "/";
            case "//", "integer divided by" -> "//";  // 整除（截断取整）
            case "modulo" -> "%";                      // 取模
            default -> op;  // 保持原样（符号运算符或未知运算符）
        };
    }

    @Override
    public Expr visitAdditiveExpr(AsterParser.AdditiveExprContext ctx) {
        Expr result = (Expr) visit(ctx.multiplicativeExpr(0));

        // 收集所有加减运算符（包括符号和自然语言形式）
        List<TerminalNode> plusNodes = ctx.PLUS();
        List<TerminalNode> minusNodes = ctx.MINUS();
        List<TerminalNode> plusWordNodes = ctx.PLUS_WORD();
        List<TerminalNode> minusWordNodes = ctx.MINUS_WORD();

        // 合并所有运算符并按token索引排序
        List<Token> allOperators = new ArrayList<>();
        plusNodes.forEach(n -> allOperators.add(n.getSymbol()));
        minusNodes.forEach(n -> allOperators.add(n.getSymbol()));
        plusWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        minusWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        allOperators.sort((a, b) -> Integer.compare(a.getTokenIndex(), b.getTokenIndex()));

        // Iterate through all operands after the first
        for (int i = 1; i < ctx.multiplicativeExpr().size(); i++) {
            Expr right = (Expr) visit(ctx.multiplicativeExpr(i));
            Token opToken = allOperators.get(i - 1);
            String op = normalizeOperator(opToken.getText());

            result = new Expr.Call(
                new Expr.Name(op, spanFrom(opToken)),
                List.of(result, right),
                spanFrom(ctx)
            );
        }
        return result;
    }

    @Override
    public Expr visitMultiplicativeExpr(AsterParser.MultiplicativeExprContext ctx) {
        Expr result = (Expr) visit(ctx.unaryExpr(0));

        // 收集所有乘除运算符（包括符号和自然语言形式）
        List<TerminalNode> starNodes = ctx.STAR();
        List<TerminalNode> slashNodes = ctx.SLASH();
        List<TerminalNode> timesWordNodes = ctx.TIMES_WORD();
        List<TerminalNode> dividedByWordNodes = ctx.DIVIDED_BY_WORD();
        List<TerminalNode> intDividedByWordNodes = ctx.INTEGER_DIVIDED_BY_WORD();
        List<TerminalNode> moduloWordNodes = ctx.MODULO_WORD();

        // 合并所有运算符并按token索引排序
        List<Token> allOperators = new ArrayList<>();
        starNodes.forEach(n -> allOperators.add(n.getSymbol()));
        slashNodes.forEach(n -> allOperators.add(n.getSymbol()));
        timesWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        dividedByWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        intDividedByWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        moduloWordNodes.forEach(n -> allOperators.add(n.getSymbol()));
        allOperators.sort((a, b) -> Integer.compare(a.getTokenIndex(), b.getTokenIndex()));

        for (int i = 1; i < ctx.unaryExpr().size(); i++) {
            Expr right = (Expr) visit(ctx.unaryExpr(i));
            Token opToken = allOperators.get(i - 1);
            String op = normalizeOperator(opToken.getText());

            result = new Expr.Call(
                new Expr.Name(op, spanFrom(opToken)),
                List.of(result, right),
                spanFrom(ctx)
            );
        }
        return result;
    }

    @Override
    public Expr visitPostfixExpr(AsterParser.PostfixExprContext ctx) {
        Expr current = (Expr) visit(ctx.primaryExpr());
        // `Map` has its own lexer token (MAP) so it parses as MapIdentExpr, not
        // TypeIdentExpr — but for qualified stdlib calls like `Map.get(m, k)` it must
        // behave like a type qualifier (yielding the call target `Map.get`), exactly
        // as `List.get` does. Without this, the `Map` qualifier is dropped and the
        // target collapses to a bare `get`, which fails to resolve.
        boolean baseIsTypeIdent = ctx.primaryExpr() instanceof AsterParser.TypeIdentExprContext
            || ctx.primaryExpr() instanceof AsterParser.MapIdentExprContext;
        List<MemberSegment> pendingMembers = new ArrayList<>();

        for (AsterParser.PostfixSuffixContext suffixCtx : ctx.postfixSuffix()) {
            if (suffixCtx instanceof AsterParser.CallSuffixContext callCtx) {
                List<Expr> args = callCtx.argumentList() == null
                    ? List.of()
                    : callCtx.argumentList().expr().stream()
                        .map(exprCtx -> (Expr) visit(exprCtx))
                        .collect(Collectors.toList());
                current = applyCallSuffix(current, baseIsTypeIdent, pendingMembers, args, callCtx);
                pendingMembers.clear();
                baseIsTypeIdent = false;
            } else if (suffixCtx instanceof AsterParser.WithCallSuffixContext withCtx) {
                // 处理自然语言函数调用: func with arg1, arg2
                List<Expr> args = withCtx.argumentList() == null
                    ? List.of()
                    : withCtx.argumentList().expr().stream()
                        .map(exprCtx -> (Expr) visit(exprCtx))
                        .collect(Collectors.toList());
                current = applyWithCallSuffix(current, pendingMembers, args, withCtx);
                pendingMembers.clear();
                baseIsTypeIdent = false;
            } else if (suffixCtx instanceof AsterParser.MemberSuffixContext memberCtx) {
                // ADR 0019 G1：成员名可为 IDENT/TYPE_IDENT 或软关键字（小写结构词，
                // 如 `x.let`）。软关键字走 structKeywordName 规则，取其文本与跨度。
                if (memberCtx.structKeywordName() != null) {
                    var sk = memberCtx.structKeywordName();
                    pendingMembers.add(new MemberSegment(sk.getText(), spanFrom(sk)));
                } else {
                    TerminalNode idNode = memberCtx.IDENT() != null ? memberCtx.IDENT() : memberCtx.TYPE_IDENT();
                    pendingMembers.add(new MemberSegment(idNode.getText(), spanFrom(idNode)));
                }
            }
        }

        if (!pendingMembers.isEmpty()) {
            current = applyTrailingMembers(current, pendingMembers);
        }
        if (ctx.postfixSuffix().isEmpty() && pendingMembers.isEmpty() && current instanceof Expr.Name name) {
            if ("none".equalsIgnoreCase(name.name())) {
                return new Expr.None(name.span());
            }
        }
        return current;
    }

    /**
     * 处理自然语言函数调用: func with arg1, arg2
     */
    private Expr applyWithCallSuffix(
        Expr target,
        List<MemberSegment> pendingMembers,
        List<Expr> args,
        AsterParser.WithCallSuffixContext withCtx
    ) {
        // 如果有待处理的成员访问，先构建完整的目标名称
        if (!pendingMembers.isEmpty() && target instanceof Expr.Name baseName) {
            String qualified = combineName(baseName.name(), pendingMembers);
            Span nameSpan = mergeSpans(baseName.span(), pendingMembers.get(pendingMembers.size() - 1).span());
            target = new Expr.Name(qualified, nameSpan);
        } else if (!pendingMembers.isEmpty()) {
            // 对于非 Name 表达式的成员访问，先应用成员
            target = applyTrailingMembers(target, pendingMembers);
        }

        Span callSpan = mergeSpans(target.span(), spanFrom(withCtx));
        return new Expr.Call(target, args, callSpan);
    }

    @Override
    public Expr visitOperatorCallExpr(AsterParser.OperatorCallExprContext ctx) {
        return visitOperatorCall(ctx.operatorCall());
    }

    @Override
    public Expr visitConstructExprAlt(AsterParser.ConstructExprAltContext ctx) {
        return visitConstructExpr(ctx.constructExpr());
    }

    @Override
    public Expr visitConstructExpr(AsterParser.ConstructExprContext ctx) {
        String typeName = ctx.TYPE_IDENT().getText();
        List<Expr.Construct.ConstructField> fields = ctx.constructFieldList().constructField().stream()
            .map(this::visitConstructField)
            .collect(Collectors.toList());
        List<Expr.Construct.ConstructField> finalFields = fields.isEmpty() ? List.of() : List.copyOf(fields);
        return new Expr.Construct(typeName, finalFields, spanFrom(ctx));
    }

    public Expr.Construct.ConstructField visitConstructField(AsterParser.ConstructFieldContext ctx) {
        // 支持 IDENT 和 TYPE_IDENT（中文字段名以 CJK 字符开头会被识别为 TYPE_IDENT）；
        // ADR 0019 G1：还支持软关键字字段名（小写结构词，如 `User with let set to x`）。
        String name;
        if (ctx.structKeywordName() != null) {
            name = ctx.structKeywordName().getText();
        } else {
            name = ctx.IDENT() != null ? ctx.IDENT().getText() : ctx.TYPE_IDENT().getText();
        }
        Expr value = (Expr) visit(ctx.expr());
        return new Expr.Construct.ConstructField(name, value);
    }

    @Override
    public Expr visitOperatorCall(AsterParser.OperatorCallContext ctx) {
        List<AsterParser.ExprContext> argsCtx = ctx.argumentList() == null
            ? List.of()
            : ctx.argumentList().expr();
        if (argsCtx.size() != 2) {
            throw new IllegalStateException("前缀操作符调用需要 2 个参数，但实际为 " + argsCtx.size());
        }
        Expr left = (Expr) visit(argsCtx.get(0));
        Expr right = (Expr) visit(argsCtx.get(1));
        Token opToken = ctx.op;
        Expr.Name opName = new Expr.Name(opToken.getText(), spanFrom(opToken));
        return new Expr.Call(opName, List.of(left, right), spanFrom(ctx));
    }

    @Override
    public Expr visitListLiteralExpr(AsterParser.ListLiteralExprContext ctx) {
        AsterParser.ListLiteralContext literalCtx = ctx.listLiteral();
        List<Expr> items = literalCtx.expr().stream()
            .map(exprCtx -> (Expr) visit(exprCtx))
            .collect(Collectors.toList());
        List<Expr> finalItems = items.isEmpty() ? List.of() : List.copyOf(items);
        return new Expr.ListLiteral(finalItems, spanFrom(ctx));
    }

    @Override
    public Expr visitVarExpr(AsterParser.VarExprContext ctx) {
        String name = ctx.IDENT().getText();
        return new Expr.Name(name, spanFrom(ctx));
    }

    /**
     * ADR 0019 G1：结构关键词的小写形式在表达式位置当变量引用（如 `Return let.`）。
     * 与 VarExpr 同构，仅 token 来源不同（structKeywordName 软关键字而非 IDENT）。
     */
    @Override
    public Expr visitStructKeywordVarExpr(AsterParser.StructKeywordVarExprContext ctx) {
        String name = ctx.structKeywordName().getText();
        return new Expr.Name(name, spanFrom(ctx));
    }

    @Override
    public Expr visitTypeIdentExpr(AsterParser.TypeIdentExprContext ctx) {
        String name = ctx.TYPE_IDENT().getText();
        return new Expr.Name(name, spanFrom(ctx));
    }

    @Override
    public Expr visitMapIdentExpr(AsterParser.MapIdentExprContext ctx) {
        String name = ctx.MAP().getText();
        return new Expr.Name(name, spanFrom(ctx));
    }

    @Override
    public Expr visitWrapExprAlt(AsterParser.WrapExprAltContext ctx) {
        return visitWrapExpr(ctx.wrapExpr());
    }

    @Override
    public Expr visitWrapExpr(AsterParser.WrapExprContext ctx) {
        String name = ctx.IDENT().getText();
        Expr inner = (Expr) visit(ctx.expr());
        Span span = spanFrom(ctx);
        if ("ok".equalsIgnoreCase(name)) {
            return new Expr.Ok(inner, span);
        }
        if ("err".equalsIgnoreCase(name)) {
            return new Expr.Err(inner, span);
        }
        if ("some".equalsIgnoreCase(name)) {
            return new Expr.Some(inner, span);
        }
        Expr.Name target = new Expr.Name(name, spanFrom(ctx.IDENT()));
        return new Expr.Call(target, List.of(inner), span);
    }

    /**
     * ADR 0027：无括号单参调用 `apply <fn> to <arg>`，lower 成与 `fn(arg)` 完全一致的
     * Call(Name, [arg])——零新 IR 节点。fn 是 callTarget（裸名或限定名点链），arg 取顶层
     * expr（贪婪），故 `apply gather to stars less 1` 等价 `gather(stars less 1)`。
     */
    @Override
    public Expr visitApplyCallExpr(AsterParser.ApplyCallExprContext ctx) {
        AsterParser.ApplyExprContext apply = ctx.applyExpr();
        Expr.Name target = visitCallTargetName(apply.callTarget());
        Expr arg = (Expr) visit(apply.expr());
        // 复用普通调用构造路径（Codex 审查 019f1639 致命问题 #2）：createCallExpression 对
        // Ok/Err/Some/None 内置变体作 AST 规范化（→ Expr.Some 等），故 `apply Some to x` 与
        // `Some(x)` lower 到**完全相同**节点——这正是 ADR 0027「等价 fn(arg)」不变式所要求。
        return createCallExpression(target, List.of(arg), spanFrom(ctx));
    }

    /**
     * callTarget = 裸名/限定名点链（不含调用后缀），拼成单个点号分隔的 Expr.Name，
     * 与后缀调用路径（combineName）及 TS parseCallTargetName 的命名口径一致。
     */
    private Expr.Name visitCallTargetName(AsterParser.CallTargetContext ctx) {
        List<MemberSegment> segments = new ArrayList<>();
        for (AsterParser.CallTargetSegmentContext seg : ctx.callTargetSegment()) {
            segments.add(new MemberSegment(seg.getText(), spanFrom(seg)));
        }
        String qualified = combineName(null, segments);
        return new Expr.Name(qualified, spanFrom(ctx));
    }

    /**
     * Lambda 表达式的 alternative 入口
     * 语法: function with x: T, produce R: body
     */
    @Override
    public Expr visitLambdaExprAlt(AsterParser.LambdaExprAltContext ctx) {
        return visitLambdaExpr(ctx.lambdaExpr());
    }

    /**
     * Lambda 表达式主规则
     * 语法: function with x: T, produce R: Return expr. 或 function with x: T, produce R: NEWLINE block
     */
    @Override
    public Expr visitLambdaExpr(AsterParser.LambdaExprContext ctx) {
        // 提取参数列表（with 或 given 引导，同义）
        List<Decl.Parameter> params = new ArrayList<>();
        if (ctx.lambdaParamList() != null) {
            params = ctx.lambdaParamList().param().stream()
                .map(this::visitParam)
                .collect(Collectors.toList());
        }

        // 提取返回类型：produce 后类型可省，省略时推断为 Unknown（与 TS 对齐）
        Type retType = ctx.annotatedType() != null
            ? extractAnnotatedType(ctx.annotatedType()).type()
            : new Type.TypeName("Unknown", List.of(), spanFrom(ctx));

        // 提取函数体
        Block body;
        if (ctx.returnStmt() != null) {
            // 单个 return 语句，包装成 block
            Stmt.Return returnStmt = visitReturnStmt(ctx.returnStmt());
            body = new Block(List.of(returnStmt), spanFrom(ctx));
        } else {
            // 多语句的 block
            body = visitBlock(ctx.block());
        }

        return new Expr.Lambda(params, retType, body, spanFrom(ctx));
    }

    @Override
    public Expr visitStringExpr(AsterParser.StringExprContext ctx) {
        String text = ctx.STRING_LITERAL().getText();
        String raw = text.substring(1, text.length() - 1);
        String value;
        try {
            value = StringEscapes.unescape(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("字符串转义解析失败: " + ex.getMessage(), ex);
        }
        return new Expr.String(value, spanFrom(ctx));
    }

    @Override
    public Expr visitIntExpr(AsterParser.IntExprContext ctx) {
        int value = LiteralParsing.parseInt(ctx.INT_LITERAL().getText());
        return new Expr.Int(value, spanFrom(ctx));
    }

    @Override
    public Expr visitFloatExpr(AsterParser.FloatExprContext ctx) {
        double value = LiteralParsing.parseDouble(ctx.FLOAT_LITERAL().getText());
        return new Expr.Double(value, spanFrom(ctx));
    }

    @Override
    public Expr visitDecimalExpr(AsterParser.DecimalExprContext ctx) {
        // Decimal 字面量（ADR 0025）：去 m/M 后缀，归一为 canonical 十进制字符串
        // （去前导/尾零、零归 "0"），与 TS canonicalizeDecimal、truffle BigDecimal
        // toPlainString 逐位一致——双引擎 Core IR value 必须字节相同。
        String raw = ctx.DECIMAL_LITERAL().getText();
        String digits = raw.substring(0, raw.length() - 1); // 去掉末尾 m/M
        String canonical = canonicalizeDecimal(digits);
        // ADR 0025 v1：Decimal 有效位 ≤38。超限会让 TS decimal.js（precision 80）乘法静默舍入、
        // BigDecimal 精确 → 双引擎分歧（Codex 审查 P1）。硬拒以保 parity 与确定性。
        int sigDigits = canonical.replaceAll("[.-]", "").replaceFirst("^0+(?=\\d)", "").length();
        if (sigDigits > 38) {
            throw new IllegalStateException(
                "Decimal literal has " + sigDigits + " significant digits; the v1 maximum is 38 (ADR 0025).");
        }
        return new Expr.Decimal(canonical, spanFrom(ctx));
    }

    /** 把十进制数字串归一为 canonical 形：去前导零、去尾零，零归 "0"（不产生 "-0"/指数）。 */
    private static String canonicalizeDecimal(String digits) {
        int dot = digits.indexOf('.');
        String intPart = dot < 0 ? digits : digits.substring(0, dot);
        String fracPart = dot < 0 ? "" : digits.substring(dot + 1);
        intPart = intPart.replaceFirst("^0+(?=\\d)", "");
        if (intPart.isEmpty()) intPart = "0";
        fracPart = fracPart.replaceFirst("0+$", "");
        String out = fracPart.isEmpty() ? intPart : intPart + "." + fracPart;
        return out.equals("-0") || out.isEmpty() ? "0" : out;
    }

    @Override
    public Expr visitLongExpr(AsterParser.LongExprContext ctx) {
        String text = ctx.LONG_LITERAL().getText();
        // 移除 'L' 或 'l' 后缀
        long value = LiteralParsing.parseLong(text.substring(0, text.length() - 1));
        return new Expr.Long(value, spanFrom(ctx));
    }

    @Override
    public Expr visitBoolExpr(AsterParser.BoolExprContext ctx) {
        boolean value = Boolean.parseBoolean(ctx.BOOL_LITERAL().getText());
        return new Expr.Bool(value, spanFrom(ctx));
    }

    @Override
    public Expr visitNullExpr(AsterParser.NullExprContext ctx) {
        return new Expr.Null(spanFrom(ctx));
    }

    @Override
    public Expr visitParenExpr(AsterParser.ParenExprContext ctx) {
        return (Expr) visit(ctx.expr());
    }


    // ============================================================
    // 辅助方法：源码位置提取
    // ============================================================

    /**
     * 从 ParserRuleContext 提取 Span 信息
     */
    private Span spanFrom(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        return new Span(
            new Span.Position(start.getLine(), start.getCharPositionInLine() + 1),
            new Span.Position(stop.getLine(), stop.getCharPositionInLine() + stop.getText().length() + 1)
        );
    }

    /**
     * 从 Token 提取 Span 信息
     */
    private Span spanFrom(Token token) {
        return new Span(
            new Span.Position(token.getLine(), token.getCharPositionInLine() + 1),
            new Span.Position(token.getLine(), token.getCharPositionInLine() + token.getText().length() + 1)
        );
    }

    /**
     * 从 TerminalNode 提取 Span 信息
     */
    private Span spanFrom(TerminalNode node) {
        return spanFrom(node.getSymbol());
    }

    private Span mergeSpans(Span first, Span second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new Span(first.start(), second.end());
    }

    private String parseStringLiteral(TerminalNode literalNode) {
        if (literalNode == null) {
            throw new IllegalStateException("依赖名称必须为字符串字面量");
        }
        String raw = literalNode.getText();
        if (raw == null || raw.length() < 2) {
            throw new IllegalStateException("字符串字面量格式非法");
        }
        String inner = raw.substring(1, raw.length() - 1);
        return StringEscapes.unescape(inner);
    }

    private Expr applyCallSuffix(
        Expr base,
        boolean baseIsTypeIdent,
        List<MemberSegment> pendingMembers,
        List<Expr> rawArgs,
        AsterParser.CallSuffixContext callCtx
    ) {
        List<Expr> args = List.copyOf(rawArgs);
        Span callSpan = mergeSpans(base.span(), spanFrom(callCtx));

        if (pendingMembers.isEmpty()) {
            return createCallExpression(base, args, callSpan);
        }

        if (base instanceof Expr.Name baseName && baseIsTypeIdent) {
            String qualified = combineName(baseName.name(), pendingMembers);
            Span nameSpan = mergeSpans(baseName.span(), pendingMembers.get(pendingMembers.size() - 1).span());
            Expr.Name target = new Expr.Name(qualified, nameSpan);
            return createCallExpression(target, args, callSpan);
        }

        String methodName = combineName(null, pendingMembers);
        Span methodSpan = pendingMembers.get(pendingMembers.size() - 1).span();
        Expr.Name target = new Expr.Name(methodName, methodSpan);
        List<Expr> methodArgs = new ArrayList<>();
        methodArgs.add(base);
        methodArgs.addAll(args);
        return new Expr.Call(target, methodArgs, callSpan);
    }

    private Expr applyTrailingMembers(Expr base, List<MemberSegment> members) {
        if (members.isEmpty()) {
            return base;
        }
        if (base instanceof Expr.Name baseName) {
            String qualified = combineName(baseName.name(), members);
            Span nameSpan = mergeSpans(baseName.span(), members.get(members.size() - 1).span());
            return new Expr.Name(qualified, nameSpan);
        }
        // ★非 Name base 的尾随成员：报错，绝不静默丢弃（issue #127 body）。
        //
        //   文法 `postfixExpr: primaryExpr postfixSuffix*` 允许 MemberSuffix 跟在
        //   CallSuffix 之后，于是 `getConfig().timeout` 能解析通过；但此处此前直接
        //   `return base;`，把 `.timeout` 整个扔掉且不产生任何诊断——
        //   `Let t be getConfig().timeout.` 会把 t 绑成整个 getConfig() 的值，
        //   **语义无声改变**。实测 `Return double(x).bar.` 得到的 AST 里 `.bar` 不存在。
        //
        //   AST 暂无成员访问节点，无法正确表达该语义；在补上之前，唯一诚实的做法是
        //   fail-fast。抛 IllegalStateException 与本文件既有的可恢复解析错误同一机制
        //   （见 visitExpr 的表达式深度守卫）。
        String dropped = combineName(null, members);
        throw new IllegalStateException(
            "暂不支持在调用/字面量等非名称表达式上做成员访问（`." + dropped + "`）。"
                + "请先用 Let 绑定中间结果，再对其取成员。");
    }

    private Expr createCallExpression(Expr target, List<Expr> args, Span span) {
        if (target instanceof Expr.Name name) {
            String funcName = name.name();
            // 位置式 `TypeName(args)` 对【已声明类型】不是合法语法——结构体构造必须用命名字段
            // `TypeName with field set to expr and ...`。原先位置式被静默降级成函数调用，
            // 后续才在执行期暴露为「未定义函数」；在此于构建期就报错并给出正确写法。
            // 此检查置于 Ok/Err/Some/None 内置变体之前：若用户声明了同名记录类型（如 Define Ok），
            // 以用户声明优先报错，与 TS 引擎行为一致（declaredRecordTypes 优先），保证 parity。
            // 仅对【记录类型】报错，不含 type alias（别名标量本就不可构造）。
            if (declaredRecordTypeNames.contains(funcName)) {
                throw new IllegalStateException(
                    "Cannot construct '" + funcName + "' with positional arguments. "
                    + "Use named fields instead: '" + funcName
                    + " with <field> set to <value> and ...'");
            }
            switch (funcName) {
                case "Ok" -> {
                    if (args.size() == 1) {
                        return new Expr.Ok(args.get(0), span);
                    }
                }
                case "Err" -> {
                    if (args.size() == 1) {
                        return new Expr.Err(args.get(0), span);
                    }
                }
                case "Some" -> {
                    if (args.size() == 1) {
                        return new Expr.Some(args.get(0), span);
                    }
                }
                case "None" -> {
                    if (args.isEmpty()) {
                        return new Expr.None(span);
                    }
                }
                default -> {
                    // fall through
                }
            }
        }
        return new Expr.Call(target, args, span);
    }

    private String combineName(String baseName, List<MemberSegment> segments) {
        if (segments.isEmpty()) {
            return baseName == null ? "" : baseName;
        }
        String suffix = segments.stream()
            .map(MemberSegment::name)
            .collect(Collectors.joining("."));
        if (baseName == null || baseName.isEmpty()) {
            return suffix;
        }
        return baseName + "." + suffix;
    }

    private record MemberSegment(String name, Span span) {}
}
