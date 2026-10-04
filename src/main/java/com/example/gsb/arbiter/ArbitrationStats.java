package com.example.gsb.arbiter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仲裁器累计统计快照。
 *
 * @param totalRounds        校验轮次
 * @param majorityRounds     多数决成功次数
 * @param disagreementRounds 分歧次数
 * @param profiles           各校验者可靠性画像
 */
public record ArbitrationStats(int totalRounds, int majorityRounds, int disagreementRounds,
                               List<ReliabilityProfile> profiles) {

    public ArbitrationStats {
        profiles = List.copyOf(profiles);
    }

    /** 按校验者标识索引的画像。 */
    public Map<String, ReliabilityProfile> profilesById() {
        return profiles.stream()
                .collect(Collectors.toUnmodifiableMap(ReliabilityProfile::validatorId,
                        Function.identity()));
    }

    /** 某校验者的吻合率；无出席记录或未登记时为空。 */
    public Optional<Double> agreementRate(String validatorId) {
        ReliabilityProfile profile = profilesById().get(validatorId);
        if (profile == null || profile.participationCount() == 0) {
            return Optional.empty();
        }
        return Optional.of(profile.agreementRate());
    }
}
