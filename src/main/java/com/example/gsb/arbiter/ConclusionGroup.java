package com.example.gsb.arbiter;

import java.util.List;

/**
 * 持有相同结论的一组校验者。
 *
 * @param verdict      该组共同的结论
 * @param validatorIds 组内校验者标识
 * @param totalWeight  组内权重之和
 */
public record ConclusionGroup(Verdict verdict, List<String> validatorIds, double totalWeight) {

    public ConclusionGroup {
        validatorIds = List.copyOf(validatorIds);
    }
}
