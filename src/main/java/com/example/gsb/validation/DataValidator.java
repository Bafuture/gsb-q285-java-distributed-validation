package com.example.gsb.validation;

/**
 * 独立校验者。每个校验者对同一份数据独立给出 {@link Verdict}。
 */
public interface DataValidator {

    /** 校验者唯一标识。 */
    String id();

    /**
     * 对给定数据执行校验。
     *
     * @param data 待校验数据
     * @return 结论与理由
     * @throws Exception 校验过程中发生的任意异常（将被视为缺席之外的失败并记录理由）
     */
    Verdict validate(String data) throws Exception;
}
