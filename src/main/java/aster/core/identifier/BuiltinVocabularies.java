package aster.core.identifier;

/**
 * 内置领域词汇表。
 *
 * 提供常用业务领域的标识符映射。
 */
public final class BuiltinVocabularies {

    private BuiltinVocabularies() {}

    /**
     * 汽车保险领域词汇表（简体中文）。
     */
    public static DomainVocabulary insuranceAutoZhCn() {
        return DomainVocabulary.builder("insurance.auto", "汽车保险", "zh-CN")
            .version("1.0.0")
            .metadata("Aster Team", "2025-01-06", "汽车保险业务领域的中文标识符映射")

            // ==========================================
            // 结构体映射
            // ==========================================
            .addStruct("Driver", "驾驶员", "司机", "驾驶人")
            .addStruct("Vehicle", "车辆", "汽车", "机动车")
            .addStruct("QuoteResult", "报价结果")
            .addStruct("Claim", "理赔", "索赔")
            .addStruct("Policy", "保单", "保险单")
            .addStruct("Coverage", "保障范围", "承保范围")

            // ==========================================
            // Driver 字段映射
            // ==========================================
            .addField("age", "年龄", "Driver")
            .addField("drivingYears", "驾龄", "Driver", "驾驶年限")
            .addField("accidents", "事故次数", "Driver", "事故数")
            .addField("licenseType", "驾照类型", "Driver", "驾驶证类型")
            .addField("name", "姓名", "Driver")

            // ==========================================
            // Vehicle 字段映射
            // ==========================================
            .addField("plateNo", "车牌号", "Vehicle", "车牌", "牌照")
            .addField("brand", "品牌", "Vehicle")
            .addField("model", "型号", "Vehicle", "车型")
            .addField("year", "年份", "Vehicle", "出厂年份")
            .addField("safetyRating", "安全评分", "Vehicle", "安全等级")
            .addField("mileage", "里程", "Vehicle", "行驶里程")
            .addField("engineType", "发动机类型", "Vehicle", "动力类型")

            // ==========================================
            // QuoteResult 字段映射
            // ==========================================
            .addField("approved", "批准", "QuoteResult", "是否批准", "通过")
            .addField("reason", "原因", "QuoteResult", "理由")
            .addField("monthlyPremium", "月保费", "QuoteResult", "每月保费")
            .addField("annualPremium", "年保费", "QuoteResult", "每年保费")
            .addField("deductible", "免赔额", "QuoteResult")
            .addField("coverageAmount", "保额", "QuoteResult", "赔偿金额")

            // ==========================================
            // Policy 字段映射
            // ==========================================
            .addField("policyNumber", "保单号", "Policy", "保险单号")
            .addField("startDate", "生效日期", "Policy", "开始日期")
            .addField("endDate", "到期日期", "Policy", "结束日期")
            .addField("status", "状态", "Policy", "保单状态")

            // ==========================================
            // 函数映射
            // ==========================================
            .addFunction("generateQuote", "生成报价", "计算报价")
            .addFunction("calculatePremium", "计算保费")
            .addFunction("calculateAgeFactor", "计算年龄因子", "年龄因子计算")
            .addFunction("calculateDrivingYearsFactor", "计算驾龄因子")
            .addFunction("assessRisk", "评估风险", "风险评估")
            .addFunction("validateDriver", "验证驾驶员", "驾驶员验证")
            .addFunction("validateVehicle", "验证车辆", "车辆验证")

            // ==========================================
            // 枚举值映射
            // ==========================================
            .addEnumValue("Approved", "批准", "通过")
            .addEnumValue("Rejected", "拒绝", "否决")
            .addEnumValue("Pending", "待审", "待处理")
            .addEnumValue("HighRisk", "高风险")
            .addEnumValue("MediumRisk", "中等风险", "中风险")
            .addEnumValue("LowRisk", "低风险")

            .build();
    }

    /**
     * 贷款金融领域词汇表（简体中文）。
     */
    public static DomainVocabulary financeLoanZhCn() {
        return DomainVocabulary.builder("finance.loan", "贷款金融", "zh-CN")
            .version("1.0.0")
            .metadata("Aster Team", "2025-01-06", "贷款金融业务领域的中文标识符映射")

            // ==========================================
            // 结构体映射
            // ==========================================
            .addStruct("Applicant", "申请人", "借款人", "贷款人")
            .addStruct("LoanRequest", "贷款申请", "借款申请", "申请")
            .addStruct("ApprovalResult", "审批结果", "批准结果", "审核结果")
            .addStruct("CreditReport", "信用报告", "征信报告", "信用记录")
            .addStruct("Collateral", "抵押物", "担保物", "抵押品")
            .addStruct("RepaymentPlan", "还款计划", "还款方案")

            // ==========================================
            // Applicant 字段映射
            // ==========================================
            .addField("id", "编号", "Applicant", "标识", "ID", "申请人编号")
            .addField("age", "年龄", "Applicant")
            .addField("income", "收入", "Applicant", "年收入", "月收入")
            .addField("creditScore", "信用评分", "Applicant", "信用分", "征信分")
            .addField("workYears", "工作年限", "Applicant", "工龄", "从业年限")
            .addField("debtRatio", "负债率", "Applicant", "负债比", "债务比率")
            .addField("employer", "雇主", "Applicant", "工作单位", "公司")
            .addField("name", "姓名", "Applicant")
            .addField("idNumber", "身份证号", "Applicant", "证件号")

            // ==========================================
            // LoanRequest 字段映射
            // ==========================================
            .addField("amount", "金额", "LoanRequest", "贷款金额", "申请金额")
            .addField("termMonths", "期限", "LoanRequest", "贷款期限", "还款期限", "月数")
            .addField("purpose", "用途", "LoanRequest", "贷款用途", "借款用途")
            .addField("loanType", "贷款类型", "LoanRequest", "类型", "产品类型")

            // ==========================================
            // ApprovalResult 字段映射
            // ==========================================
            .addField("approved", "批准", "ApprovalResult", "是否批准", "通过")
            .addField("reason", "原因", "ApprovalResult", "理由", "审批意见")
            .addField("interestRate", "利率", "ApprovalResult", "年利率", "贷款利率")
            .addField("monthlyPayment", "月供", "ApprovalResult", "月还款额", "每月还款")
            .addField("approvedAmount", "批准金额", "ApprovalResult", "核准金额", "授信额度")

            // ==========================================
            // CreditReport 字段映射
            // ==========================================
            .addField("score", "评分", "CreditReport", "分数", "信用分")
            .addField("level", "等级", "CreditReport", "信用等级", "级别")
            .addField("latePayments", "逾期次数", "CreditReport", "逾期记录", "违约次数")
            .addField("totalDebt", "总负债", "CreditReport", "负债总额", "债务总额")

            // ==========================================
            // Collateral 字段映射
            // ==========================================
            .addField("type", "类型", "Collateral", "抵押物类型")
            .addField("value", "价值", "Collateral", "评估价值")
            .addField("description", "描述", "Collateral", "说明")

            // ==========================================
            // 函数映射
            // ==========================================
            .addFunction("evaluateLoan", "评估贷款", "贷款评估", "审核贷款")
            .addFunction("checkBasicQualification", "检查基础资格", "基础资格检查", "资格审核")
            .addFunction("calculateCreditLevel", "计算信用等级", "信用等级计算")
            .addFunction("calculateInterestRate", "计算利率", "利率计算")
            .addFunction("calculateMonthlyPayment", "计算月供", "月供计算")
            .addFunction("checkDebtRatio", "检查负债率", "负债率检查")
            .addFunction("assessCollateral", "评估抵押物", "抵押物评估")

            // ==========================================
            // 枚举值映射
            // ==========================================
            .addEnumValue("Excellent", "优秀", "优")
            .addEnumValue("Good", "良好", "良")
            .addEnumValue("Fair", "一般", "中")
            .addEnumValue("Poor", "较差", "差")
            .addEnumValue("Personal", "个人贷款", "消费贷")
            .addEnumValue("Mortgage", "房贷", "房屋贷款", "按揭贷款")
            .addEnumValue("Business", "经营贷款", "企业贷款", "商业贷款")
            .addEnumValue("Auto", "车贷", "汽车贷款")

            .build();
    }
}
