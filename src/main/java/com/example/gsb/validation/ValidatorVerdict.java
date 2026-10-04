package com.example.gsb.validation;

/**
 * 某一轮中某个校验者实际返回的结论（含其权重）。
 *
 * @param validatorId 校验者 id
 * @param weight      该校验者本轮参与仲裁的权重
 * @param verdict     返回的结论与理由
 */
public record ValidatorVerdict(String validatorId, double weight, Verdict verdict) {
}
