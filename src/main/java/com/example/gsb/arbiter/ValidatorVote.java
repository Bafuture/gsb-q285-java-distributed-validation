package com.example.gsb.arbiter;

import java.util.Objects;

/**
 * 一次校验轮次中单个校验者的投票记录。
 *
 * @param validatorId 校验者标识
 * @param weight      该校验者本轮使用的权重
 * @param present     是否在超时前返回（false 表示缺席）
 * @param verdict     结论；缺席时为 null
 * @param detail      出席时为理由，缺席时为缺席原因（如超时、异常）
 */
public record ValidatorVote(String validatorId, double weight, boolean present,
                            Verdict verdict, String detail) {

    public ValidatorVote {
        Objects.requireNonNull(validatorId, "validatorId 不能为 null");
        Objects.requireNonNull(detail, "detail 不能为 null");
        if (present && verdict == null) {
            throw new IllegalArgumentException("出席投票必须携带 verdict");
        }
        if (!present && verdict != null) {
            throw new IllegalArgumentException("缺席投票不得携带 verdict");
        }
    }

    public static ValidatorVote present(String validatorId, double weight, Verdict verdict) {
        return new ValidatorVote(validatorId, weight, true, verdict, verdict.reason());
    }

    public static ValidatorVote absent(String validatorId, double weight, String cause) {
        return new ValidatorVote(validatorId, weight, false, null, cause);
    }
}
