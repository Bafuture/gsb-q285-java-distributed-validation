package com.example.gsb.validation;

import java.util.Objects;

/**
 * 单个校验者对一份数据给出的校验结论与理由。
 *
 * @param conclusion 结论标识（例如 "VALID" / "INVALID"），相同字符串视为同一结论
 * @param reason     支撑该结论的理由说明，用于分歧上报与人工介入
 */
public record Verdict(String conclusion, String reason) {

    public Verdict {
        Objects.requireNonNull(conclusion, "conclusion must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
