package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * 语句节点（sealed interface）
 * <p>
 * 包含 Let、Set、Return、If、Match、Start、Wait、Block 以及表达式语句。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Stmt.Let.class, name = "Let"),
    @JsonSubTypes.Type(value = Stmt.Set.class, name = "Set"),
    @JsonSubTypes.Type(value = Stmt.Return.class, name = "Return"),
    @JsonSubTypes.Type(value = Stmt.If.class, name = "If"),
    @JsonSubTypes.Type(value = Stmt.Match.class, name = "Match"),
    @JsonSubTypes.Type(value = Stmt.Start.class, name = "Start"),
    @JsonSubTypes.Type(value = Stmt.Wait.class, name = "Wait"),
    @JsonSubTypes.Type(value = Stmt.Workflow.class, name = "workflow"),
    @JsonSubTypes.Type(value = Block.class, name = "Block")
})
public sealed interface Stmt extends AstNode
    permits Stmt.Let, Stmt.Set, Stmt.Return, Stmt.If, Stmt.Match, Stmt.Start, Stmt.Wait, Stmt.Workflow, Block {

    /**
     * Let 绑定语句（不可变变量声明）
     *
     * @param name 变量名称
     * @param expr 初始化表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Let")
    record Let(
        @JsonProperty("name") String name,
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "Let";
        }
    }

    /**
     * Set 赋值语句（可变变量赋值）
     *
     * @param name 变量名称
     * @param expr 赋值表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Set")
    record Set(
        @JsonProperty("name") String name,
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "Set";
        }
    }

    /**
     * Return 返回语句
     *
     * @param expr 返回表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Return")
    record Return(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Stmt, Case.CaseBody {
        @Override
        public String kind() {
            return "Return";
        }
    }

    /**
     * If 条件语句
     *
     * @param cond      条件表达式
     * @param thenBlock then 分支块
     * @param elseBlock else 分支块（可能为 null）
     * @param span      源码位置信息
     */
    @JsonTypeName("If")
    record If(
        @JsonProperty("cond") Expr cond,
        @JsonProperty("thenBlock") Block thenBlock,
        @JsonProperty("elseBlock") Block elseBlock,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "If";
        }
    }

    /**
     * Match 模式匹配语句
     *
     * @param expr  待匹配表达式
     * @param cases 匹配分支列表
     * @param span  源码位置信息
     */
    @JsonTypeName("Match")
    record Match(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("cases") List<Case> cases,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "Match";
        }
    }

    /**
     * Case 匹配分支（用于 Match 语句）
     *
     * @param pattern 模式
     * @param body    分支体（Return 或 Block）
     * @param span    源码位置信息
     */
    record Case(
        @JsonProperty("pattern") Pattern pattern,
        @JsonProperty("body") CaseBody body,
        @JsonProperty("span") Span span
    ) {
        /**
         * Case 分支体类型（Return 或 Block）
         */
        public sealed interface CaseBody permits Stmt.Return, Block {}
    }

    /**
     * Start 异步任务启动语句
     *
     * @param name 任务名称
     * @param expr 要异步执行的表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Start")
    record Start(
        @JsonProperty("name") String name,
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "Start";
        }
    }

    /**
     * Wait 等待语句（等待异步任务完成）
     *
     * @param names 要等待的任务名称列表
     * @param span  源码位置信息
     */
    @JsonTypeName("Wait")
    record Wait(
        @JsonProperty("names") List<String> names,
        @JsonProperty("span") Span span
    ) implements Stmt {
        @Override
        public String kind() {
            return "Wait";
        }
    }

    /**
     * Workflow 语句
     *
     * @param steps   步骤列表
     * @param retry   重试策略（可选）
     * @param timeout 超时配置（可选）
     * @param span    源码位置信息
     */
    @JsonTypeName("workflow")
    record Workflow(
        @JsonProperty("steps") List<WorkflowStep> steps,
        @JsonProperty("retry") RetryPolicy retry,
        @JsonProperty("timeout") Timeout timeout,
        @JsonProperty("span") Span span
    ) implements Stmt {
        public Workflow {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        @Override
        public String kind() {
            return "workflow";
        }
    }

    /**
     * Workflow 中的 step 定义
     *
     * @param name         步骤名称
     * @param body         主体代码块
     * @param compensate   补偿代码块（可选）
     * @param dependencies 依赖步骤列表
     * @param span         源码位置信息
     */
    @JsonTypeName("step")
    record WorkflowStep(
        @JsonProperty("name") String name,
        @JsonProperty("body") Block body,
        @JsonProperty("compensate") Block compensate,
        @JsonProperty("dependencies") List<String> dependencies,
        @JsonProperty("span") Span span
    ) implements AstNode {
        public WorkflowStep {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        @Override
        public String kind() {
            return "step";
        }
    }

    /**
     * Workflow Retry 配置
     *
     * @param maxAttempts 最大尝试次数
     * @param backoff     回退模式（exponential/linear）
     */
    record RetryPolicy(
        @JsonProperty("maxAttempts") int maxAttempts,
        @JsonProperty("backoff") String backoff
    ) {}

    /**
     * Workflow Timeout 配置
     *
     * @param milliseconds 超时时长（毫秒）
     */
    record Timeout(
        @JsonProperty("milliseconds") long milliseconds
    ) {}
}
