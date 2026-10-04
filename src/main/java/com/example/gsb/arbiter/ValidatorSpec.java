package com.example.gsb.arbiter;

import java.util.Objects;

/**
 * 校验者登记信息：唯一标识、校验逻辑与权重。
 *
 * @param id        校验者唯一标识
 * @param validator 校验逻辑
 * @param weight    参与多数决的权重，必须为正数
 */
public record ValidatorSpec(String id, DataValidator validator, double weight) {

    public ValidatorSpec {
        Objects.requireNonNull(id, "id 不能为 null");
        Objects.requireNonNull(validator, "validator 不能为 null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空白");
        }
        if (!(weight > 0) || Double.isNaN(weight) || Double.isInfinite(weight)) {
            throw new IllegalArgumentException("weight 必须为正数: " + weight);
        }
    }

    public static ValidatorSpec of(String id, DataValidator validator) {
        return new ValidatorSpec(id, validator, 1.0);
    }

    public static ValidatorSpec of(String id, DataValidator validator, double weight) {
        return new ValidatorSpec(id, validator, weight);
    }
}
