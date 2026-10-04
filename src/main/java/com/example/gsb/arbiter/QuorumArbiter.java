package com.example.gsb.arbiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式校验与多数决仲裁器。
 *
 * <p>工作流程：登记若干 {@link ValidatorSpec}（含权重），每轮 {@link #arbitrate(String)}
 * 让全部校验者在独立工作线程上并行校验同一份数据，在超时时间内收集结论后按权重做多数决。
 *
 * <p><b>多数决规则（含偶数处理）</b>：设所有已登记校验者权重之和为 W（缺席者计入分母，
 * 防止少数派借缺席“上位”）。某结论的权重和 s 满足 s &gt; W / 2 时方被采纳。
 * 校验者数量（或权重）为偶数时可能出现恰好对半（s == W / 2），
 * 此时不构成多数，判为分歧并输出 {@link DisagreementReport} 供人工介入——
 * 即“严格过半才采纳，平局不算多数”。为消除浮点误差，比较时含 1e-9 容差。
 *
 * <p><b>超时与缺席</b>：每轮有统一的超时上限（构造时指定）。超时未返回或抛异常的
 * 校验者记为缺席并保留原因，仲裁不会无限等待。注意：阻塞中的工作线程无法被强制
 * 杀死，长期挂起的校验者会持续占用线程；工作线程为守护线程，不阻止 JVM 退出。
 *
 * <p><b>可靠性画像</b>：每轮结束后统计各校验者与多数方的吻合情况
 * （出席轮次、吻合次数、缺席次数），可用于后续调整权重。
 *
 * <p>线程安全：登记与仲裁方法均可并发调用，统计更新在内部加锁。
 */
public final class QuorumArbiter implements AutoCloseable {

    private static final double EPSILON = 1e-9;

    private final Map<String, ValidatorSpec> validators = new LinkedHashMap<>();
    private final Map<String, MutableProfile> profiles = new LinkedHashMap<>();
    private final Duration timeout;
    private final ExecutorService executor;

    private int totalRounds;
    private int majorityRounds;
    private int disagreementRounds;

    public QuorumArbiter(Duration timeout) {
        this(timeout, defaultExecutor());
    }

    /** 允许注入自定义线程池（例如限制并发度）；调用方不拥有其生命周期，close 时会被关闭。 */
    public QuorumArbiter(Duration timeout, ExecutorService executor) {
        Objects.requireNonNull(timeout, "timeout 不能为 null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正: " + timeout);
        }
        this.timeout = timeout;
        this.executor = Objects.requireNonNull(executor, "executor 不能为 null");
    }

    private static ExecutorService defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "quorum-arbiter-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    /** 登记校验者；id 重复时抛出异常。 */
    public synchronized QuorumArbiter register(ValidatorSpec spec) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        if (validators.containsKey(spec.id())) {
            throw new IllegalArgumentException("校验者已登记: " + spec.id());
        }
        validators.put(spec.id(), spec);
        profiles.put(spec.id(), new MutableProfile());
        return this;
    }

    public synchronized QuorumArbiter register(String id, DataValidator validator) {
        return register(ValidatorSpec.of(id, validator));
    }

    public synchronized QuorumArbiter register(String id, DataValidator validator, double weight) {
        return register(ValidatorSpec.of(id, validator, weight));
    }

    public synchronized int validatorCount() {
        return validators.size();
    }

    /**
     * 执行一轮校验与仲裁。所有已登记校验者并行校验同一份数据，
     * 在超时上限内收集结论后按权重做多数决。
     */
    public ArbitrationResult arbitrate(String data) {
        Objects.requireNonNull(data, "data 不能为 null");
        final List<ValidatorSpec> specs;
        final int roundNumber;
        synchronized (this) {
            if (validators.isEmpty()) {
                throw new IllegalStateException("尚未登记任何校验者");
            }
            specs = List.copyOf(validators.values());
            roundNumber = ++totalRounds;
        }

        List<ValidatorVote> votes = collectVotes(specs, data);
        double totalWeight = specs.stream().mapToDouble(ValidatorSpec::weight).sum();

        Map<Boolean, List<ValidatorVote>> byConclusion = new LinkedHashMap<>();
        byConclusion.put(Boolean.TRUE, new ArrayList<>());
        byConclusion.put(Boolean.FALSE, new ArrayList<>());
        for (ValidatorVote vote : votes) {
            if (vote.present()) {
                byConclusion.get(vote.verdict().accepted()).add(vote);
            }
        }

        List<ConclusionGroup> groups = new ArrayList<>();
        for (Map.Entry<Boolean, List<ValidatorVote>> entry : byConclusion.entrySet()) {
            List<ValidatorVote> members = entry.getValue();
            if (members.isEmpty()) {
                continue;
            }
            Verdict verdict = members.get(0).verdict();
            List<String> ids = members.stream().map(ValidatorVote::validatorId).toList();
            double weight = members.stream().mapToDouble(ValidatorVote::weight).sum();
            groups.add(new ConclusionGroup(verdict, ids, weight));
        }

        ConclusionGroup winner = null;
        for (ConclusionGroup group : groups) {
            if (group.totalWeight() > totalWeight / 2.0 + EPSILON) {
                winner = group;
                break;
            }
        }

        ArbitrationResult result;
        if (winner != null) {
            Verdict adopted = new Verdict(winner.verdict().accepted(), mergeReasons(winner));
            result = new ArbitrationResult(roundNumber, data, true, adopted,
                    winner.totalWeight(), totalWeight, votes, null);
        } else {
            double best = groups.stream().mapToDouble(ConclusionGroup::totalWeight).max().orElse(0);
            List<String> absentees = votes.stream()
                    .filter(v -> !v.present()).map(ValidatorVote::validatorId).toList();
            String summary = buildSummary(groups, absentees, totalWeight);
            DisagreementReport report = new DisagreementReport(groups, absentees, summary, totalWeight);
            result = new ArbitrationResult(roundNumber, data, false, null,
                    best, totalWeight, votes, report);
        }

        synchronized (this) {
            if (result.majorityReached()) {
                majorityRounds++;
            } else {
                disagreementRounds++;
            }
            Boolean majoritySide = result.majorityReached()
                    ? result.adoptedVerdict().accepted() : null;
            for (ValidatorVote vote : votes) {
                MutableProfile profile = profiles.get(vote.validatorId());
                profile.rounds++;
                if (vote.present()) {
                    profile.participations++;
                    if (majoritySide != null
                            && vote.verdict().accepted() == majoritySide.booleanValue()) {
                        profile.agreements++;
                    }
                } else {
                    profile.absences++;
                }
            }
        }
        return result;
    }

    private List<ValidatorVote> collectVotes(List<ValidatorSpec> specs, String data) {
        List<Callable<Verdict>> tasks = specs.stream()
                .<Callable<Verdict>>map(spec -> () -> spec.validator().validate(data))
                .toList();
        List<Future<Verdict>> futures;
        try {
            futures = executor.invokeAll(tasks, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("仲裁等待校验结果时被中断", e);
        }
        List<ValidatorVote> votes = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            ValidatorSpec spec = specs.get(i);
            Future<Verdict> future = futures.get(i);
            if (future.isCancelled()) {
                votes.add(ValidatorVote.absent(spec.id(), spec.weight(),
                        "缺席：超过 " + timeout.toMillis() + "ms 未返回"));
                continue;
            }
            try {
                Verdict verdict = future.get();
                if (verdict == null) {
                    votes.add(ValidatorVote.absent(spec.id(), spec.weight(),
                            "缺席：校验者返回 null"));
                } else {
                    votes.add(ValidatorVote.present(spec.id(), spec.weight(), verdict));
                }
            } catch (ExecutionException e) {
                votes.add(ValidatorVote.absent(spec.id(), spec.weight(),
                        "缺席：校验抛出异常 " + e.getCause()));
            } catch (CancellationException e) {
                votes.add(ValidatorVote.absent(spec.id(), spec.weight(), "缺席：任务被取消"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("仲裁收集校验结果时被中断", e);
            }
        }
        return votes;
    }

    private static String mergeReasons(ConclusionGroup winner) {
        String side = winner.verdict().accepted() ? "通过" : "拒绝";
        return "多数决采纳【" + side + "】（权重 " + winner.totalWeight() + "，校验者 "
                + String.join(",", winner.validatorIds()) + "）：" + winner.verdict().reason();
    }

    private static String buildSummary(List<ConclusionGroup> groups, List<String> absentees,
                                       double totalWeight) {
        StringBuilder sb = new StringBuilder("未形成多数决（严格过半才采纳，平局算分歧）。总权重 ")
                .append(totalWeight).append("，需超过 ").append(totalWeight / 2.0).append("。");
        if (groups.isEmpty()) {
            sb.append("无任何校验者出席。");
        } else {
            sb.append("分歧点：");
            for (int i = 0; i < groups.size(); i++) {
                ConclusionGroup g = groups.get(i);
                if (i > 0) {
                    sb.append(" vs ");
                }
                sb.append(g.verdict().accepted() ? "通过" : "拒绝")
                        .append("(权重 ").append(g.totalWeight())
                        .append("，校验者 ").append(String.join(",", g.validatorIds()))
                        .append("，理由：").append(g.verdict().reason()).append(")");
            }
        }
        if (!absentees.isEmpty()) {
            sb.append("；缺席：").append(String.join(",", absentees));
        }
        return sb.toString();
    }

    /** 累计统计快照：轮次、多数决成功次数、分歧次数与各校验者吻合率。 */
    public synchronized ArbitrationStats stats() {
        List<ReliabilityProfile> snapshot = new ArrayList<>();
        for (Map.Entry<String, MutableProfile> entry : profiles.entrySet()) {
            MutableProfile p = entry.getValue();
            snapshot.add(new ReliabilityProfile(entry.getKey(), p.rounds,
                    p.participations, p.agreements, p.absences));
        }
        return new ArbitrationStats(totalRounds, majorityRounds, disagreementRounds, snapshot);
    }

    /** 单个校验者的可靠性画像；未登记时返回 null。 */
    public synchronized ReliabilityProfile reliabilityOf(String validatorId) {
        MutableProfile p = profiles.get(validatorId);
        return p == null ? null
                : new ReliabilityProfile(validatorId, p.rounds, p.participations,
                        p.agreements, p.absences);
    }

    /** 关闭内部线程池；守护线程不阻止 JVM 退出，但建议显式关闭。 */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class MutableProfile {
        int rounds;
        int participations;
        int agreements;
        int absences;
    }
}
