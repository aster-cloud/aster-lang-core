@id("NEST-1#1")
@aster_rule("NEST-1")
@aster_module("guard.nested")
@aster_source_hash("test")
@aster_outcome("deny")
@aster_reason("robots are not allowed")
forbid (principal, action, resource)
when { (principal.type == "robot") };

@id("NEST-1#2")
@aster_rule("NEST-1")
@aster_module("guard.nested")
@aster_source_hash("test")
@aster_outcome("require_approval")
@aster_approver("Finance")
@aster_reason("over limit")
forbid (principal, action, resource)
when { !(principal.type == "robot") && (context has fee) && (context has amount && context has fee && ((context.amount + context.fee) > 5000)) };

@id("NEST-1#3")
@aster_rule("NEST-1")
@aster_module("guard.nested")
@aster_source_hash("test")
@aster_outcome("allow")
permit (principal, action, resource)
when { !(principal.type == "robot") && (context has fee) && (context has amount && context has fee && !((context.amount + context.fee) > 5000)) && ((context has trusted && context.trusted) || ((context has trusted && !context.trusted) && (resource.type == "public"))) };

@id("NEST-1#4")
@aster_rule("NEST-1")
@aster_module("guard.nested")
@aster_source_hash("test")
@aster_outcome("escalate")
@aster_reason("untrusted caller")
forbid (principal, action, resource)
when { !(principal.type == "robot") && (context has fee) && (context has amount && context has fee && !((context.amount + context.fee) > 5000)) && ((context has trusted && !context.trusted) && !(resource.type == "public")) };
