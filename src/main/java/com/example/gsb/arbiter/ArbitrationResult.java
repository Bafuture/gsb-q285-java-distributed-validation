package com.example.gsb.arbiter;

import java.util.List;

/**
 * 一轮校验仲裁的结果。
 *
 * @param roundNumber     轮次序号（从 1 开始）
 * @param data            本轮被校验的数据
 * @param majorityReached 是否形成多数决
 * @param adoptedVerdict  被采纳的结论（聚合多数方理由）；未形成多数决时为空
 * @param winningWeight   胜出方权重和（未形成多数决时为最大分组权重）
 * @param totalWeight     参与多数决的总权重（所有已登记校验者，含缺席者）
 * @param votes           全部校验者的投票/缺席记录
 * @param disagreement    分歧上报；形成多数决时为空
 */
public record ArbitrationResult(int roundNumber, String data, boolean majorityReached,
                                Verdict adoptedVerdict, double winningWeight,
                                double totalWeight, List<ValidatorVote> votes,
                                DisagreementReport disagreement) {

    public ArbitrationResult {
        votes = List.copyOf(votes);
        if (majorityReached == (disagreement != null)) {
            throw new IllegalArgumentException("多数决成功与分歧上报互斥");
        }
    }

    /** 缺席校验者标识（超时或抛异常）。 */
    public List<String> absentees() {
        return votes.stream().filter(v -> !v.present()).map(ValidatorVote::validatorId).toList();
    }
}
