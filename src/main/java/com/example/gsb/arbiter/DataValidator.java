package com.example.gsb.arbiter;

/**
 * 独立校验者。实现方对同一份数据独立给出 {@link Verdict}。
 *
 * <p>实现应当幂等、无副作用；校验在仲裁器的工作线程上执行，
 * 未在超时时间内返回的调用会被按缺席处理（底层线程不可强制中断，
 * 长时间阻塞的实现可能占用工作线程，见 {@link QuorumArbiter} 文档）。
 */
@FunctionalInterface
public interface DataValidator {

    /**
     * 对数据执行校验。
     *
     * @param data 待校验的数据
     * @return 结论与理由
     */
    Verdict validate(String data);
}
