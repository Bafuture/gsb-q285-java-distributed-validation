# 分布式校验与多数决仲裁组件

Pair-wise GSB 标注任务仓库（第 17 批 / 285）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的关键数据要由多个独立校验者分别检查，结果不一致时需要能仲裁出可信结论。请从零实现一个分布式校验与多数决仲裁组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持登记多个校验者并按同一份数据分别执行校验，各自返回结论与理由；2) 支持多数决仲裁：相同结论超过半数即采纳，需说明校验者数量为偶数时如何处理；3) 支持校验者权重：不同校验者可配置不同权重，权重之和参与多数决；4) 支持超时与缺席：未在规定时间内返回的校验者按缺席处理并记录，不得让仲裁一直等待；5) 支持分歧上报：未形成多数决时要输出各组结论、涉及校验者与分歧点，供人工介入；6) 支持校验者可靠性画像：统计各校验者历史与多数的吻合率，用于后续权重调整；7) 提供统计：校验轮次、多数决成功次数、分歧次数与各校验者吻合率；8) 测试覆盖多数决、权重生效、超时缺席、分歧上报、可靠性画像与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

## 实现说明（分支 B）

### 组件结构

| 类 | 职责 |
|----|------|
| `DataValidator` | 校验者接口：`id()` + `validate(data)` 返回 `Verdict`（结论 + 理由） |
| `DistributedArbiter` | 仲裁引擎：登记校验者（含权重）、并发执行校验、超时缺席处理、多数决仲裁 |
| `ArbitrationOutcome` | 一轮仲裁结果：`MAJORITY`（含采纳结论）或 `DIVERGENT`（含分歧上报） |
| `ConclusionGroup` | 相同结论的分组：结论、权重之和、组内校验者及各自理由 |
| `DivergenceReport` | 分歧上报：各组结论、涉及校验者、分歧点摘要、缺席名单 |
| `ArbitrationStatistics` | 累计统计：轮次、多数决成功数、分歧数、各校验者可靠性画像 |
| `ValidatorReliability` | 可靠性画像：参与轮次、缺席次数、与多数吻合率 |

### 仲裁规则

- **多数决**：按结论分组累加权重，某组权重**严格大于**到场校验者总权重的 1/2 时采纳该结论。
- **偶数校验者**：采用严格多数（> 50%）而非 ≥ 50%，因此偶数人数等权平票（如 2:2）时无任何一组过半，判为分歧并输出 `DivergenceReport` 供人工介入；若权重不等使一方权重过半，仍可正常裁决。
- **权重**：登记时可配置正数权重（缺省 1.0），权重之和参与多数决计票。
- **超时与缺席**：所有校验并发执行，对每个结果只等待到统一截止时间；超时或执行抛异常的校验者取消任务并记入缺席名单，仲裁绝不无限等待。缺席者不参与计票。
- **可靠性画像**：仅在已形成多数决且校验者到场的轮次统计吻合率（吻合次数 / 可评判轮次），分歧轮次不计入，供后续权重调整参考。

### 使用示例

```java
try (DistributedArbiter arbiter = new DistributedArbiter()) {
    arbiter.register(validatorA);          // 缺省权重 1
    arbiter.register(validatorB, 3.0);     // 高可信校验者，权重 3
    arbiter.register(validatorC);

    ArbitrationOutcome outcome = arbiter.arbitrate("关键数据", Duration.ofMillis(500));
    if (outcome.isMajority()) {
        System.out.println("采纳结论: " + outcome.adoptedConclusion());
    } else {
        System.out.println(outcome.divergenceReport().summary()); // 人工介入
    }

    ArbitrationStatistics stats = arbiter.statistics();
    stats.reliabilityProfiles().forEach(r ->
        System.out.printf("%s 吻合率=%.2f 缺席=%d%n", r.validatorId(), r.agreementRate(), r.absences()));
}
```

### 验证

```bash
./mvnw -q verify   # 19 个测试覆盖多数决、偶数平票、权重、超时缺席、分歧上报、可靠性画像与统计
```
