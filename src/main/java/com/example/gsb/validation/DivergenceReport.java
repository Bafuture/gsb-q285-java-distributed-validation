package com.example.gsb.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 未形成多数决时的分歧上报，供人工介入。
 *
 * @param groups           各结论分组（含涉及校验者与各自理由）
 * @param absentValidators 本轮缺席的校验者 id
 * @param summary          人类可读的分歧点摘要
 */
public record DivergenceReport(List<ConclusionGroup> groups,
                               List<String> absentValidators,
                               String summary) {

    public DivergenceReport {
        groups = List.copyOf(groups);
        absentValidators = List.copyOf(absentValidators);
    }

    static String buildSummary(List<ConclusionGroup> groups, List<String> absent) {
        String points = groups.stream()
                .map(g -> "\"%s\"（权重 %.1f，校验者 %s，理由：%s）".formatted(
                        g.conclusion(),
                        g.totalWeight(),
                        g.supporters().stream().map(ValidatorVerdict::validatorId).toList(),
                        g.supporters().stream()
                                .map(v -> v.validatorId() + ": " + v.verdict().reason())
                                .collect(Collectors.joining("; "))))
                .collect(Collectors.joining(" 对 "));
        String absentPart = absent.isEmpty() ? "" : "；缺席校验者：" + absent;
        return "未形成多数决，分歧点：" + points + absentPart;
    }
}
