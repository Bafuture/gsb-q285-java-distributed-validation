package com.example.gsb.arbiter;

/**
 * 单个校验者的可靠性画像（统计快照，不可变）。
 *
 * <p>吻合率 = 吻合次数 / 出席次数；从未出席时吻合率为 {@code NaN}。
 * 缺席轮次不参与吻合率分母，但单独统计供权重调整参考。
 *
 * @param validatorId       校验者标识
 * @param registeredRounds  被登记后经历的总轮次
 * @param participationCount 实际出席并给出结论的次数
 * @param agreementCount    结论与多数方一致的次数
 * @param absenceCount      缺席次数（超时或异常）
 */
public record ReliabilityProfile(String validatorId, int registeredRounds,
                                 int participationCount, int agreementCount,
                                 int absenceCount) {

    /** 历史与多数的吻合率；尚无出席记录时返回 NaN。 */
    public double agreementRate() {
        return participationCount == 0 ? Double.NaN : (double) agreementCount / participationCount;
    }
}
