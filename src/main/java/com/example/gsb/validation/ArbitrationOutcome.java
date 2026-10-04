package com.example.gsb.validation;

import java.util.List;

/**
 * 一轮仲裁的结果。
 *
 * @param status            仲裁状态
 * @param adoptedConclusion 被采纳的结论（仅当 status 为 MAJORITY 时非空）
 * @param groups            本轮全部结论分组
 * @param absentValidators  本轮缺席（超时未返回）的校验者 id
 * @param divergenceReport  分歧上报（仅当 status 为 DIVERGENT 时非空）
 */
public record ArbitrationOutcome(Status status,
                                 String adoptedConclusion,
                                 List<ConclusionGroup> groups,
                                 List<String> absentValidators,
                                 DivergenceReport divergenceReport) {

    public enum Status {
        /** 某一结论获得超过半数的权重，被采纳。 */
        MAJORITY,
        /** 没有任何结论获得超过半数的权重，需要人工介入。 */
        DIVERGENT
    }

    public ArbitrationOutcome {
        groups = List.copyOf(groups);
        absentValidators = List.copyOf(absentValidators);
    }

    public boolean isMajority() {
        return status == Status.MAJORITY;
    }
}
