package com.example.gsb.validation;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 仲裁组件的累计统计：轮次数、成功/分歧次数与各校验者吻合率。
 */
public class ArbitrationStatistics {

    private long totalRounds;
    private long majorityRounds;
    private long divergentRounds;
    private final Map<String, Counter> counters = new TreeMap<>();

    synchronized void registerValidator(String id, double weight) {
        counters.computeIfAbsent(id, k -> new Counter(weight));
    }

    synchronized void recordRound(boolean majority) {
        totalRounds++;
        if (majority) {
            majorityRounds++;
        } else {
            divergentRounds++;
        }
    }

    synchronized void recordResponse(String validatorId, boolean agreedWithMajority) {
        Counter c = counters.get(validatorId);
        if (c != null) {
            c.participated++;
            if (agreedWithMajority) {
                c.agreements++;
            }
        }
    }

    synchronized void recordAbsence(String validatorId) {
        Counter c = counters.get(validatorId);
        if (c != null) {
            c.absences++;
        }
    }

    public synchronized long totalRounds() {
        return totalRounds;
    }

    public synchronized long majorityRounds() {
        return majorityRounds;
    }

    public synchronized long divergentRounds() {
        return divergentRounds;
    }

    /** 返回各校验者的可靠性画像（按 id 排序）。 */
    public synchronized List<ValidatorReliability> reliabilityProfiles() {
        return counters.entrySet().stream()
                .map(e -> {
                    Counter c = e.getValue();
                    double rate = c.participated == 0 ? 0.0 : (double) c.agreements / c.participated;
                    return new ValidatorReliability(e.getKey(), c.weight,
                            c.participated, c.absences, c.agreements, rate);
                })
                .toList();
    }

    /** 查找单个校验者的可靠性画像。 */
    public synchronized ValidatorReliability reliabilityOf(String validatorId) {
        return reliabilityProfiles().stream()
                .filter(r -> r.validatorId().equals(validatorId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown validator: " + validatorId));
    }

    private static final class Counter {
        final double weight;
        long participated;
        long absences;
        long agreements;

        Counter(double weight) {
            this.weight = weight;
        }
    }
}
