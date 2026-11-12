package aster.core.parser;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 词法期的类型嵌套深度计数（aster-lang-core#250），供 {@link AsterCustomLexer} 在 ANTLR 解析之前拦下过深的类型。
 *
 * <p><b>计数单位</b>与 aster-lang-ts {@code parseType} 相同：每进入一次类型解析计 1，即语法规则 {@code type}
 * 的一次调用。{@code list of} 的元素、{@code Map K to V} 的键和值、{@code result of A and B} 的每个分量、
 * {@code option of} 的参数、函数类型的参数与返回值、括号类型都各占一层；叶子类型名本身也占一层。
 * 所以 {@code list of Int} 的深度是 2，{@code Map Text to list of Int} 的深度是 3。
 *
 * <p><b>做法</b>：按 token 顺序跑一个与 {@code AsterParser.g4} 的 {@code type} 规则同构的小下推自动机。
 * 只在类型位置（{@code as} / {@code produce} 之后）工作，遇到类型里不会出现的 token 就退出；栈里每一帧是
 * 一个尚未结束的复合类型，新的一次类型解析发生在「栈深 + 1」层。识别不了的写法一律退出类型模式，不报错——
 * 判语法对错是解析器的事，这里只负责在递归开始前数清层数。
 *
 * <p>分隔词按语法的贪婪规则归属：{@code and} / {@code ,} 交给最内层已满足的 {@code X of} 帧（ANTLR 的
 * {@code ((AND | COMMA) annotatedType)*} 是贪婪的），{@code to} 交给最内层等键的 {@code Map} 帧，
 * {@code )} / {@code >} / {@code ->} 交给对应的括号 / 泛型帧；途经的已满足帧随之出栈。
 */
final class TypeNestingTracker {

    /** 帧状态：OPEN 结尾的状态表示「下一个 token 应当开始一个新的类型」。 */
    private enum Frame {
        /** {@code X of} 之后，等第一个参数。 */
        OF_OPEN,
        /**
         * {@code and} / {@code ,} 之后，可能是 {@code X of} 的下一个参数，也可能只是参数表 / 字段表的分隔符
         * （{@code given x as list of Int, produce …}）——两种都合法，所以不像其余 OPEN 状态那样「必须接类型」。
         */
        OF_MORE,
        /** {@code X of A} 已满足，还可接 {@code and} / {@code ,} 追加参数。 */
        OF_DONE,
        /** {@code Map} 之后，等键类型。 */
        MAP_KEY,
        /** 键类型结束，等 {@code to}。 */
        MAP_TO,
        /** {@code to} 之后，等值类型；值类型结束即整帧结束。 */
        MAP_VAL,
        /** {@code X <} 之后，等参数。 */
        LT_OPEN,
        /** 泛型参数已满足，等 {@code ,} 或 {@code >}。 */
        LT_DONE,
        /** {@code (} 之后，等参数。 */
        PAREN_OPEN,
        /** 括号内参数已满足，等 {@code ,} 或 {@code )}。 */
        PAREN_DONE,
        /** {@code )} 之后：后接 {@code ->} 是函数类型，否则是括号类型，已可结束。 */
        PAREN_CLOSED,
        /** {@code ->} 之后，等返回类型；返回类型结束即整帧结束。 */
        RET_OPEN;

        boolean expectsType() {
            return this == OF_OPEN || this == OF_MORE || this == MAP_KEY || this == MAP_VAL
                || this == LT_OPEN || this == PAREN_OPEN || this == RET_OPEN;
        }
    }

    /** 注解（{@code @pii(L1, email)}）跳过状态：不改变类型层数。 */
    private enum Annotation { NONE, AFTER_AT, AFTER_NAME, IN_ARGS }

    private final int limit;
    private final Deque<Frame> frames = new ArrayDeque<>();
    /**
     * 初始即处于类型位置：直接用 {@code parser.type()} / {@code annotatedType()} 解析一段类型文本时，
     * token 流以类型开头；模块源码以 {@code Module} 等关键词开头，第一个 token 就会退出类型模式。
     */
    private boolean typeMode = true;
    /** 类型位置刚开始、尚未读到第一个类型。 */
    private boolean atStart = true;
    /** 前两个有意义 token 的类型（{@code prev1} 为紧邻的那个），用来区分 {@code as} 后面是类型还是表达式。 */
    private int prev1 = -1;
    private int prev2 = -1;
    /** 已读到一个类型名，要看下一个 token 才知道它是叶子还是 {@code X of} / {@code X <} 的头。 */
    private boolean pendingName = false;
    private Annotation annotation = Annotation.NONE;
    private int annotationParens = 0;

    TypeNestingTracker(int limit) {
        this.limit = limit;
    }

    /**
     * 处理一个有意义的 token。
     *
     * @return {@code true} 表示这个 token 开始的类型解析超过上限，调用方应报 E054
     */
    boolean onToken(int type) {
        int before2 = prev2;
        if (type != AsterParser.INDENT && type != AsterParser.DEDENT) {
            prev2 = prev1;
            prev1 = type;
        }
        if (typeMode && mustContinue() && !startsType(type)) {
            // 合法源码在这里只能接类型；别的 token 只会出现在语法错误里。ANTLR 的错误恢复
            // （单 token 删除 / 同步时跳过若干 token）会越过它们继续在同一层往下递归，
            // 所以这里不退出、不清零，否则 `list of + list of + …` 就能每段从零数起绕过上限。
            return false;
        }
        if (type == AsterParser.AS || type == AsterParser.PRODUCE) {
            reset();
            // `Define x as <expr>.` / `Start x as <expr>.` 的 as 后面是表达式（defineStmt / startStmt），不是类型
            boolean exprAfterAs = type == AsterParser.AS
                && (before2 == AsterParser.DEFINE || before2 == AsterParser.START);
            typeMode = !exprAfterAs;
            atStart = !exprAfterAs;
            return false;
        }
        if (!typeMode || type == AsterParser.INDENT || type == AsterParser.DEDENT) {
            return false;
        }
        if (annotation != Annotation.NONE && skipAnnotation(type)) {
            return false;
        }
        if (pendingName) {
            pendingName = false;
            if (type == AsterParser.OF) {
                frames.push(Frame.OF_OPEN);
                return false;
            }
            if (type == AsterParser.LT) {
                frames.push(Frame.LT_OPEN);
                return false;
            }
            childDone();
        }
        return switch (type) {
            case AsterParser.TYPE_IDENT, AsterParser.IDENT -> enter(null);
            case AsterParser.MAP -> enter(Frame.MAP_KEY);
            case AsterParser.LPAREN -> enter(Frame.PAREN_OPEN);
            case AsterParser.AT -> {
                annotation = Annotation.AFTER_AT;
                yield false;
            }
            case AsterParser.QUESTION -> false;
            case AsterParser.TO_WORD -> resume(Frame.MAP_TO, Frame.MAP_VAL);
            case AsterParser.AND -> resume(Frame.OF_DONE, Frame.OF_MORE);
            case AsterParser.COMMA -> resumeAfterComma();
            case AsterParser.GT -> resume(Frame.LT_DONE, null);
            case AsterParser.RPAREN -> resume(Frame.PAREN_DONE, Frame.PAREN_CLOSED);
            case AsterParser.ARROW -> resume(Frame.PAREN_CLOSED, Frame.RET_OPEN);
            default -> {
                reset();
                yield false;
            }
        };
    }

    /** 栈顶帧必须接一个类型（{@code X of}、{@code Map}、{@code to}、{@code <}、{@code (}、{@code ->} 之后）。 */
    private boolean mustContinue() {
        return !pendingName && annotation == Annotation.NONE && !frames.isEmpty()
            && frames.peek().expectsType() && frames.peek() != Frame.OF_MORE;
    }

    private static boolean startsType(int type) {
        return type == AsterParser.TYPE_IDENT || type == AsterParser.IDENT || type == AsterParser.MAP
            || type == AsterParser.LPAREN || type == AsterParser.AT
            || type == AsterParser.INDENT || type == AsterParser.DEDENT;
    }

    /** 开始一次类型解析：层数为栈深 + 1。{@code push} 为 null 表示类型名（是否为头要看下一个 token）。 */
    private boolean enter(Frame push) {
        boolean expecting = frames.isEmpty() ? atStart : frames.peek().expectsType();
        if (!expecting) {
            reset();
            return false;
        }
        atStart = false;
        if (frames.size() + 1 > limit) {
            reset();
            return true;
        }
        if (push == null) {
            pendingName = true;
        } else {
            frames.push(push);
        }
        return false;
    }

    private boolean resumeAfterComma() {
        // `,` 可延续 X of 参数、泛型参数或括号 / 函数参数，交给最内层能接的那一帧
        Frame target = popToAcceptor(Frame.OF_DONE, Frame.LT_DONE, Frame.PAREN_DONE);
        if (target == null) {
            reset();
            return false;
        }
        frames.pop();
        frames.push(switch (target) {
            case OF_DONE -> Frame.OF_MORE;
            case LT_DONE -> Frame.LT_OPEN;
            default -> Frame.PAREN_OPEN;
        });
        return false;
    }

    /**
     * 分隔词：出栈已满足的帧直到 {@code accept} 帧，然后把它换成 {@code next}；
     * {@code next} 为 null 表示该帧随之结束。找不到可接的帧说明类型已经结束，退出类型模式。
     */
    private boolean resume(Frame accept, Frame next) {
        Frame target = popToAcceptor(accept, null, null);
        if (target == null) {
            reset();
            return false;
        }
        frames.pop();
        if (next == null) {
            childDone();
        } else {
            frames.push(next);
        }
        return false;
    }

    /** 出栈可以就此结束的帧（{@code X of A}、括号类型），直到栈顶是可接的帧；返回该帧，找不到为 null。 */
    private Frame popToAcceptor(Frame a, Frame b, Frame c) {
        while (!frames.isEmpty()) {
            Frame top = frames.peek();
            if (top == a || top == b || top == c) {
                return top;
            }
            if (top != Frame.OF_DONE && top != Frame.PAREN_CLOSED) {
                return null;
            }
            frames.pop();
            childDone();
        }
        return null;
    }

    /** 栈顶帧的当前子类型结束。 */
    private void childDone() {
        if (frames.isEmpty()) {
            return;
        }
        Frame top = frames.peek();
        Frame next = switch (top) {
            case OF_OPEN, OF_MORE -> Frame.OF_DONE;
            case MAP_KEY -> Frame.MAP_TO;
            case LT_OPEN -> Frame.LT_DONE;
            case PAREN_OPEN -> Frame.PAREN_DONE;
            // 值类型 / 返回类型结束即整帧结束，再通知外层
            case MAP_VAL, RET_OPEN -> null;
            // 已满足的帧不会再收到子类型结束，保持原状
            default -> top;
        };
        frames.pop();
        if (next == null) {
            childDone();
        } else {
            frames.push(next);
        }
    }

    /** 注解 {@code @name} 或 {@code @name(...)}：返回 true 表示 token 已被注解吃掉。 */
    private boolean skipAnnotation(int type) {
        switch (annotation) {
            case AFTER_AT -> {
                annotation = Annotation.AFTER_NAME;
                return true;
            }
            case AFTER_NAME -> {
                if (type == AsterParser.LPAREN) {
                    annotation = Annotation.IN_ARGS;
                    annotationParens = 1;
                    return true;
                }
                annotation = Annotation.NONE;
                return false;
            }
            case IN_ARGS -> {
                if (type == AsterParser.LPAREN) {
                    annotationParens++;
                } else if (type == AsterParser.RPAREN && --annotationParens == 0) {
                    annotation = Annotation.NONE;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void reset() {
        typeMode = false;
        atStart = false;
        pendingName = false;
        annotation = Annotation.NONE;
        annotationParens = 0;
        frames.clear();
    }
}
