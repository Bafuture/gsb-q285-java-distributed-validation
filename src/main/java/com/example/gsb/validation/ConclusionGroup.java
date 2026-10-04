package com.example.gsb.validation;

import java.util.List;

/**
 * 持有相同结论的一组校验者。
 *
 * @param conclusion   该组共同结论
 * @param totalWeight  该组权重之和
 * @param supporters   该组校验者及其各自结论
 */
public record ConclusionGroup(String conclusion, double totalWeight, List<ValidatorVerdict> supporters) {

    public ConclusionGroup {
        supporters = List.copyOf(supporters);
    }
}
