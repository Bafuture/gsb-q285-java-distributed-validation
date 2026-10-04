package com.example.gsb.arbiter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 分歧上报：未形成多数决时输出各组结论、涉及校验者与分歧点，供人工介入。
 *
 * @param groups       按结论分组的校验者及其权重
 * @param absentees    缺席（超时/异常）的校验者标识
 * @param summary      人类可读的分歧点描述
 * @param totalWeight  本轮参与多数决的总权重
 */
public record DisagreementReport(List<ConclusionGroup> groups, List<String> absentees,
                                 String summary, double totalWeight) {

    public DisagreementReport {
        groups = List.copyOf(groups);
        absentees = List.copyOf(absentees);
    }

    /** 各组结论的简要文本，例如 "ACCEPT(权重3.0: v1,v2) vs REJECT(权重2.0: v3)"。 */
    public String describe() {
        return groups.stream()
                .map(g -> (g.verdict().accepted() ? "ACCEPT" : "REJECT")
                        + "(权重" + g.totalWeight() + ": " + String.join(",", g.validatorIds()) + ")")
                .collect(Collectors.joining(" vs "));
    }
}
