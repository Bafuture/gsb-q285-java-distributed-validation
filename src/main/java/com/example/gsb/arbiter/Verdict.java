package com.example.gsb.arbiter;

import java.util.Objects;

/**
 * 单个校验者对一份数据给出的结论与理由。
 *
 * @param accepted 校验结论：true 表示通过，false 表示拒绝
 * @param reason   支撑该结论的理由说明
 */
public record Verdict(boolean accepted, String reason) {

    public Verdict {
        Objects.requireNonNull(reason, "reason 不能为 null");
    }

    public static Verdict accept(String reason) {
        return new Verdict(true, reason);
    }

    public static Verdict reject(String reason) {
        return new Verdict(false, reason);
    }
}
