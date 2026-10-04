package com.example.gsb.validation;

/**
 * 单个校验者的可靠性画像。
 *
 * <p>吻合率 = 与多数决结论一致的轮次 / 该实际返回（未缺席）的轮次。
 * 未形成多数决（分歧）的轮次不计入吻合率，因为没有可对齐的多数结论。</p>
 *
 * @param validatorId      校验者 id
 * @param weight           当前配置权重
 * @param participated     实际返回结论的轮次数
 * @param absences         缺席（超时或执行失败）的轮次数
 * @param agreements       与多数决结论吻合的轮次数
 * @param agreementRate    吻合率（0~1）；没有参与记录时为 0
 */
public record ValidatorReliability(String validatorId,
                                   double weight,
                                   long participated,
                                   long absences,
                                   long agreements,
                                   double agreementRate) {
}
