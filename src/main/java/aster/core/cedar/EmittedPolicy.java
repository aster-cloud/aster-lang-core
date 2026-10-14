package aster.core.cedar;

/** 一个规则分支生成的一条 Cedar 策略；{@code reason} / {@code approver} 无值时为 null。 */
public record EmittedPolicy(String id, String effect, String outcome, String reason, String approver, String condition) {}
