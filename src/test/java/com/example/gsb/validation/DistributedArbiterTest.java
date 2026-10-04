package com.example.gsb.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DistributedArbiterTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final List<DistributedArbiter> arbiters = new ArrayList<>();

    @AfterEach
    void tearDown() {
        arbiters.forEach(DistributedArbiter::close);
    }

    private DistributedArbiter newArbiter() {
        DistributedArbiter arbiter = new DistributedArbiter();
        arbiters.add(arbiter);
        return arbiter;
    }

    private static DataValidator fixed(String id, String conclusion, String reason) {
        return new DataValidator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Verdict validate(String data) {
                return new Verdict(conclusion, reason);
            }
        };
    }

    private static DataValidator slow(String id, String conclusion, Duration delay) {
        return new DataValidator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Verdict validate(String data) throws InterruptedException {
                Thread.sleep(delay.toMillis());
                return new Verdict(conclusion, "too late");
            }
        };
    }

    // ---------- 1) 登记多个校验者并分别执行校验 ----------

    @Test
    void eachValidatorReceivesSameDataAndReturnsVerdictWithReason() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "checksum ok"));
        a.register(fixed("v2", "VALID", "schema ok"));
        a.register(fixed("v3", "INVALID", "signature mismatch"));

        ArbitrationOutcome outcome = a.arbitrate("payload-42", TIMEOUT);

        assertThat(outcome.isMajority()).isTrue();
        assertThat(outcome.groups()).hasSize(2);
        ConclusionGroup valid = outcome.groups().stream()
                .filter(g -> g.conclusion().equals("VALID")).findFirst().orElseThrow();
        assertThat(valid.supporters()).extracting(ValidatorVerdict::validatorId)
                .containsExactlyInAnyOrder("v1", "v2");
        assertThat(valid.supporters()).extracting(v -> v.verdict().reason())
                .containsExactlyInAnyOrder("checksum ok", "schema ok");
    }

    // ---------- 2) 多数决仲裁（含偶数校验者平票处理） ----------

    @Test
    void majorityConclusionIsAdopted() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "VALID", "r2"));
        a.register(fixed("v3", "INVALID", "r3"));

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.MAJORITY);
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
        assertThat(outcome.divergenceReport()).isNull();
    }

    @Test
    void evenNumberOfValidatorsWithTieYieldsNoMajority() {
        // 偶数校验者 2:2 平票：严格多数（> 50%）无人达到，判为分歧
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "VALID", "r2"));
        a.register(fixed("v3", "INVALID", "r3"));
        a.register(fixed("v4", "INVALID", "r4"));

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.DIVERGENT);
        assertThat(outcome.adoptedConclusion()).isNull();
        assertThat(outcome.divergenceReport()).isNotNull();
    }

    @Test
    void evenNumberOfValidatorsStillResolvesWhenNotTied() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "VALID", "r2"));
        a.register(fixed("v3", "VALID", "r3"));
        a.register(fixed("v4", "INVALID", "r4"));

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.MAJORITY);
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
    }

    // ---------- 3) 校验者权重 ----------

    @Test
    void weightsDecideMajority() {
        // 人数上 1:1 平票，但权重 3:1，高权重方超过总权重半数而胜出
        DistributedArbiter a = newArbiter();
        a.register(fixed("expert", "VALID", "trusted source"), 3.0);
        a.register(fixed("rookie", "INVALID", "suspicious"), 1.0);

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.MAJORITY);
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
        ConclusionGroup valid = outcome.groups().stream()
                .filter(g -> g.conclusion().equals("VALID")).findFirst().orElseThrow();
        assertThat(valid.totalWeight()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void equalWeightsWithSameSplitWouldBeDivergent() {
        // 对照：同样的 1:1 分裂，等权重时无法形成多数
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "INVALID", "r2"));

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.DIVERGENT);
    }

    @Test
    void invalidWeightIsRejected() {
        DistributedArbiter a = newArbiter();
        assertThatThrownBy(() -> a.register(fixed("v1", "X", "r"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.register(fixed("v2", "X", "r"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 4) 超时与缺席 ----------

    @Test
    void slowValidatorIsMarkedAbsentAndArbitrationDoesNotBlock() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "VALID", "r2"));
        a.register(slow("slow-poke", "INVALID", Duration.ofSeconds(30)));

        long start = System.nanoTime();
        ArbitrationOutcome outcome = a.arbitrate("data", Duration.ofMillis(300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome.absentValidators()).containsExactly("slow-poke");
        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.MAJORITY);
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void absentValidatorsAreExcludedFromVoteCount() {
        // 3 人中 1 人缺席，到场 2 人 1:1 平票 -> 分歧（缺席票不计入）
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "INVALID", "r2"));
        a.register(slow("v3", "VALID", Duration.ofSeconds(30)));

        ArbitrationOutcome outcome = a.arbitrate("data", Duration.ofMillis(300));

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.DIVERGENT);
        assertThat(outcome.absentValidators()).containsExactly("v3");
    }

    @Test
    void failingValidatorIsTreatedAsAbsent() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r1"));
        a.register(fixed("v2", "VALID", "r2"));
        a.register(new DataValidator() {
            @Override
            public String id() {
                return "crasher";
            }

            @Override
            public Verdict validate(String data) {
                throw new IllegalStateException("boom");
            }
        });

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.absentValidators()).containsExactly("crasher");
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
    }

    // ---------- 5) 分歧上报 ----------

    @Test
    void divergentOutcomeReportsGroupsValidatorsAndReasons() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "checksum ok"));
        a.register(fixed("v2", "INVALID", "signature mismatch"));
        a.register(fixed("v3", "UNKNOWN", "cannot reach source"));

        ArbitrationOutcome outcome = a.arbitrate("data", TIMEOUT);

        assertThat(outcome.status()).isEqualTo(ArbitrationOutcome.Status.DIVERGENT);
        DivergenceReport report = outcome.divergenceReport();
        assertThat(report.groups()).hasSize(3);
        assertThat(report.groups()).extracting(ConclusionGroup::conclusion)
                .containsExactlyInAnyOrder("VALID", "INVALID", "UNKNOWN");
        assertThat(report.summary())
                .contains("VALID", "INVALID", "UNKNOWN")
                .contains("v1", "v2", "v3")
                .contains("checksum ok", "signature mismatch", "cannot reach source");
    }

    // ---------- 6) 可靠性画像 ----------

    @Test
    void reliabilityProfileTracksAgreementWithMajority() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("always-right", "VALID", "r"));
        a.register(fixed("sidekick", "VALID", "r"));
        a.register(fixed("always-wrong", "INVALID", "r"));

        a.arbitrate("d1", TIMEOUT);
        a.arbitrate("d2", TIMEOUT);

        ValidatorReliability right = a.statistics().reliabilityOf("always-right");
        assertThat(right.participated()).isEqualTo(2);
        assertThat(right.agreements()).isEqualTo(2);
        assertThat(right.agreementRate()).isCloseTo(1.0, within(1e-9));

        ValidatorReliability wrong = a.statistics().reliabilityOf("always-wrong");
        assertThat(wrong.participated()).isEqualTo(2);
        assertThat(wrong.agreements()).isZero();
        assertThat(wrong.agreementRate()).isZero();
    }

    @Test
    void divergentRoundIsExcludedFromReliabilityBase() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r"));
        a.register(fixed("v2", "VALID", "r"));
        DataValidator odd = new DataValidator() {
            private boolean first = true;

            @Override
            public String id() {
                return "odd";
            }

            @Override
            public Verdict validate(String data) {
                if (first) {
                    first = false;
                    return new Verdict("INVALID", "dissent");
                }
                return new Verdict("VALID", "agree");
            }
        };
        a.register(odd);

        a.arbitrate("majority", TIMEOUT); // VALID 多数，odd 不吻合

        // 构造一轮三方分歧：重新登记一个全新 arbiter 太啰嗦，直接用分歧场景验证
        DistributedArbiter b = newArbiter();
        b.register(fixed("x1", "A", "r"));
        b.register(fixed("x2", "B", "r"));
        b.register(fixed("x3", "C", "r"));
        b.arbitrate("divergent", TIMEOUT);

        ValidatorReliability x1 = b.statistics().reliabilityOf("x1");
        assertThat(x1.participated()).isZero(); // 分歧轮不计入可评判轮次
        assertThat(x1.agreementRate()).isZero();

        ValidatorReliability oddRel = a.statistics().reliabilityOf("odd");
        assertThat(oddRel.participated()).isEqualTo(1);
        assertThat(oddRel.agreements()).isZero();
    }

    @Test
    void absenceIsTrackedInReliabilityProfile() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r"));
        a.register(fixed("v2", "VALID", "r"));
        a.register(slow("absentee", "INVALID", Duration.ofSeconds(30)));

        a.arbitrate("data", Duration.ofMillis(300));

        ValidatorReliability r = a.statistics().reliabilityOf("absentee");
        assertThat(r.absences()).isEqualTo(1);
        assertThat(r.participated()).isZero();
        assertThat(r.agreementRate()).isZero();
    }

    // ---------- 7) 统计 ----------

    @Test
    void statisticsAccumulateAcrossRounds() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "VALID", "r"));
        a.register(fixed("v2", "VALID", "r"));
        a.register(fixed("v3", "INVALID", "r"));

        a.arbitrate("round-1", TIMEOUT); // 多数
        a.arbitrate("round-2", TIMEOUT); // 多数

        ArbitrationStatistics stats = a.statistics();
        assertThat(stats.totalRounds()).isEqualTo(2);
        assertThat(stats.majorityRounds()).isEqualTo(2);
        assertThat(stats.divergentRounds()).isZero();
        assertThat(stats.reliabilityProfiles())
                .extracting(ValidatorReliability::validatorId)
                .containsExactlyInAnyOrder("v1", "v2", "v3");
    }

    @Test
    void statisticsCountDivergentRounds() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "A", "r"));
        a.register(fixed("v2", "B", "r"));

        a.arbitrate("round-1", TIMEOUT);
        a.arbitrate("round-2", TIMEOUT);

        ArbitrationStatistics stats = a.statistics();
        assertThat(stats.totalRounds()).isEqualTo(2);
        assertThat(stats.majorityRounds()).isZero();
        assertThat(stats.divergentRounds()).isEqualTo(2);
    }

    // ---------- 防御性校验 ----------

    @Test
    void arbitrationRequiresRegisteredValidators() {
        DistributedArbiter a = newArbiter();
        assertThatThrownBy(() -> a.arbitrate("data", TIMEOUT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateValidatorIdIsRejected() {
        DistributedArbiter a = newArbiter();
        a.register(fixed("v1", "A", "r"));
        assertThatThrownBy(() -> a.register(fixed("v1", "B", "r")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arbitrationWaitsForValidatorsWithinTimeout() throws Exception {
        // 校验者在超时内返回时应被正常计入
        DistributedArbiter a = newArbiter();
        CountDownLatch started = new CountDownLatch(1);
        a.register(fixed("v1", "VALID", "r"));
        a.register(new DataValidator() {
            @Override
            public String id() {
                return "moderate";
            }

            @Override
            public Verdict validate(String data) throws InterruptedException {
                started.countDown();
                Thread.sleep(100);
                return new Verdict("VALID", "needed some time");
            }
        });

        ArbitrationOutcome outcome = a.arbitrate("data", Duration.ofSeconds(5));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.absentValidators()).isEmpty();
        assertThat(outcome.adoptedConclusion()).isEqualTo("VALID");
    }
}
