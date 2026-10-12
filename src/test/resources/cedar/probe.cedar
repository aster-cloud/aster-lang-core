@id("PROBE-001#1")
@aster_rule("PROBE-001")
@aster_module("guard.probe")
@aster_source_hash("test")
@aster_outcome("require_approval")
@aster_approver("Data Protection Officer")
@aster_reason("customer record deletion")
forbid (principal, action, resource)
when { (action == Aster::Action::"delete_customer_record") };

@id("PROBE-001#2")
@aster_rule("PROBE-001")
@aster_module("guard.probe")
@aster_source_hash("test")
@aster_outcome("allow")
permit (principal, action, resource)
when { !(action == Aster::Action::"delete_customer_record") };
