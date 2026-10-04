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

## 实现说明：分布式校验与多数决仲裁组件

实现位于 `src/main/java/com/example/gsb/arbiter`，仅依赖 JDK 17（JUnit 5/AssertJ 仅用于测试）。

### 核心类型

| 类型 | 职责 |
|------|------|
| `DataValidator` | 校验者接口，对一份数据独立返回 `Verdict(结论, 理由)` |
| `ValidatorSpec` | 校验者登记信息：id、校验逻辑、权重（必须为正，默认 1.0） |
| `QuorumArbiter` | 仲裁器：登记、并行校验、超时缺席、权重多数决、分歧上报、可靠性画像、统计 |
| `ArbitrationResult` | 一轮结果：是否多数决、采纳结论、各投票记录、分歧上报 |
| `DisagreementReport` / `ConclusionGroup` | 分歧上报：各组结论、涉及校验者、权重与人类可读分歧点 |
| `ReliabilityProfile` / `ArbitrationStats` | 各校验者吻合率画像与累计统计 |

### 多数决与偶数处理

- 设所有已登记校验者权重之和为 W（缺席者**计入分母**，防止少数派借缺席上位）；某结论权重和 s 满足 **s > W/2** 才被采纳。
- 校验者数量（或权重）为偶数时可能恰好对半（s = W/2），**平局不算多数**，判为分歧并输出 `DisagreementReport` 供人工介入。
- 权重比较带 1e-9 浮点容差。

### 超时与缺席

- 每轮有统一超时（构造 `QuorumArbiter(Duration)`），内部在守护线程池上并行执行全部校验，超时即取消并记录缺席（含原因），仲裁不会无限等待。
- 校验抛异常或返回 null 同样按缺席处理。注意：阻塞在不可中断操作上的线程无法被强制杀死，可能继续占用工作线程。

### 可靠性画像与统计

每轮结束后更新：`吻合率 = 与多数方一致次数 / 出席次数`（缺席轮次不进分母但单独计数）；
`stats()` 提供校验轮次、多数决成功次数、分歧次数与全部画像，可作为后续权重调整依据。

### 用法示例

```java
try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofMillis(500))) {
    arbiter.register("strict", data -> Verdict.reject("签名缺失"), 3.0);
    arbiter.register("lenient-a", data -> Verdict.accept("格式合法"), 1.0);
    arbiter.register("lenient-b", data -> Verdict.accept("格式合法"), 1.0);

    ArbitrationResult result = arbiter.arbitrate(data);
    if (result.majorityReached()) {
        Verdict adopted = result.adoptedVerdict();      // 拒绝（权重 3/5 严格过半）
    } else {
        DisagreementReport report = result.disagreement(); // 平局/不足半数 -> 人工介入
    }
    ArbitrationStats stats = arbiter.stats();          // 轮次、成功、分歧、吻合率
}
```

### 测试

`QuorumArbiterTest`（12 个用例）覆盖：普通多数决、独立结论与理由、权重生效、权重半数平局、
偶数校验者对半分歧、超时缺席不等待、异常缺席、分歧上报内容、可靠性画像（含缺席不入分母）与累计统计。
