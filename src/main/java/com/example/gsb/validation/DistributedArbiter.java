package com.example.gsb.validation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 分布式校验与多数决仲裁组件。
 *
 * <h2>仲裁规则</h2>
 * <ul>
 *   <li>登记多个 {@link DataValidator}（可配置权重，缺省权重为 1），一轮仲裁中各自对同一份数据
 *       并发执行校验并返回结论与理由。</li>
 *   <li><b>多数决（加权严格多数）</b>：按结论分组并累加权重，某组权重严格大于所有<b>到场</b>
 *       校验者总权重的 1/2 时采纳该结论。缺席者不参与计票。</li>
 *   <li><b>偶数校验者</b>：因为采用「严格超过半数」（&gt; 50%）而非「不低于半数」（≥ 50%），
 *       偶数人数出现 1:1 或等权重分裂（如 2 票对 2 票）时，任何一组都不超过半数，判定为未形成多数决，
 *       进入分歧上报。若两组权重不等（如 3:2 的权重分布），超过半数的一方仍可正常胜出。</li>
 *   <li><b>超时与缺席</b>：所有校验并发执行，对每个结果只等待到统一截止时间；超时（或执行抛异常）
 *       的校验者取消任务并记入缺席名单，仲裁绝不无限等待。</li>
 *   <li><b>分歧上报</b>：未形成多数决时输出 {@link DivergenceReport}，包含各结论分组、
 *       组内校验者、各自理由与缺席者。</li>
 *   <li><b>可靠性画像与统计</b>：每轮结束更新累计统计；对每个校验者统计其在可评判轮次
 *       （已形成多数决且其到场）中与多数结论的吻合率，供后续权重调整。</li>
 * </ul>
 */
public final class DistributedArbiter implements AutoCloseable {

    private static final double DEFAULT_WEIGHT = 1.0;

    private final Map<String, RegisteredValidator> validators = new LinkedHashMap<>();
    private final ArbitrationStatistics statistics = new ArbitrationStatistics();
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public DistributedArbiter() {
        this(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "distributed-arbiter-validator");
            t.setDaemon(true);
            return t;
        }), true);
    }

    private DistributedArbiter(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 以缺省权重 1 登记一个校验者。 */
    public void register(DataValidator validator) {
        register(validator, DEFAULT_WEIGHT);
    }

    /** 以指定权重登记一个校验者（权重必须为正数）。 */
    public void register(DataValidator validator, double weight) {
        Objects.requireNonNull(validator, "validator must not be null");
        if (weight <= 0 || Double.isNaN(weight) || Double.isInfinite(weight)) {
            throw new IllegalArgumentException("weight must be a finite positive number: " + weight);
        }
        String id = Objects.requireNonNull(validator.id(), "validator id must not be null");
        if (validators.containsKey(id)) {
            throw new IllegalArgumentException("validator already registered: " + id);
        }
        validators.put(id, new RegisteredValidator(validator, weight));
        statistics.registerValidator(id, weight);
    }

    public int validatorCount() {
        return validators.size();
    }

    public ArbitrationStatistics statistics() {
        return statistics;
    }

    /**
     * 执行一轮仲裁。
     *
     * @param data    待校验数据（同一份数据分发给全部已登记校验者）
     * @param timeout 单个校验者的最长等待时长，超时按缺席处理
     * @return 仲裁结果（多数决或分歧）
     */
    public ArbitrationOutcome arbitrate(String data, Duration timeout) {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (validators.isEmpty()) {
            throw new IllegalStateException("no validators registered");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        Map<String, Future<Verdict>> futures = new LinkedHashMap<>();
        for (RegisteredValidator rv : validators.values()) {
            futures.put(rv.validator.id(), executor.submit(() -> rv.validator.validate(data)));
        }

        List<ValidatorVerdict> verdicts = new ArrayList<>();
        List<String> absent = new ArrayList<>();

        for (Map.Entry<String, Future<Verdict>> entry : futures.entrySet()) {
            String id = entry.getKey();
            double weight = validators.get(id).weight;
            long remaining = deadlineNanos - System.nanoTime();
            try {
                if (remaining <= 0) {
                    throw new TimeoutException();
                }
                Verdict verdict = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                verdicts.add(new ValidatorVerdict(id, weight, verdict));
            } catch (TimeoutException te) {
                entry.getValue().cancel(true);
                absent.add(id);
            } catch (Exception e) {
                // 执行异常/被取消等无法给出结论的情况，与超时一样按缺席处理
                entry.getValue().cancel(true);
                absent.add(id);
            }
        }

        return resolve(verdicts, absent);
    }

    private ArbitrationOutcome resolve(List<ValidatorVerdict> verdicts, List<String> absent) {
        Map<String, ConclusionGroup> grouped = new LinkedHashMap<>();
        double respondedWeight = 0.0;
        for (ValidatorVerdict vv : verdicts) {
            respondedWeight += vv.weight();
            grouped.compute(vv.verdict().conclusion(),
                    (k, g) -> {
                        if (g == null) {
                            List<ValidatorVerdict> list = new ArrayList<>();
                            list.add(vv);
                            return new ConclusionGroup(k, vv.weight(), list);
                        }
                        List<ValidatorVerdict> list = new ArrayList<>(g.supporters());
                        list.add(vv);
                        return new ConclusionGroup(k, g.totalWeight() + vv.weight(), list);
                    });
        }
        List<ConclusionGroup> groups = List.copyOf(grouped.values());

        ConclusionGroup winner = null;
        for (ConclusionGroup g : groups) {
            if (g.totalWeight() > respondedWeight / 2.0) {
                winner = g;
                break;
            }
        }

        boolean majority = winner != null;
        statistics.recordRound(majority);
        for (String id : absent) {
            statistics.recordAbsence(id);
        }

        if (majority) {
            String adopted = winner.conclusion();
            for (ValidatorVerdict vv : verdicts) {
                statistics.recordResponse(vv.validatorId(), vv.verdict().conclusion().equals(adopted));
            }
            return new ArbitrationOutcome(ArbitrationOutcome.Status.MAJORITY,
                    adopted, groups, absent, null);
        }

        DivergenceReport report = new DivergenceReport(groups, absent,
                DivergenceReport.buildSummary(groups, absent));
        return new ArbitrationOutcome(ArbitrationOutcome.Status.DIVERGENT,
                null, groups, absent, report);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private record RegisteredValidator(DataValidator validator, double weight) {
    }
}
