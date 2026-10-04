package com.example.gsb.arbiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuorumArbiterTest {

    private static DataValidator fixed(boolean accepted, String reason) {
        return data -> new Verdict(accepted, reason);
    }

    private static DataValidator slow(long millis) {
        return data -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Verdict.accept("慢校验者结果");
        };
    }

    private static DataValidator exploding() {
        return data -> {
            throw new IllegalStateException("boom");
        };
    }

    @Test
    void majority_overHalfAdoptsVerdictWithReason() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("v1", fixed(true, "格式合法"));
            arbiter.register("v2", fixed(true, "签名有效"));
            arbiter.register("v3", fixed(false, "签名缺失"));

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.roundNumber()).isEqualTo(1);
            assertThat(result.majorityReached()).isTrue();
            assertThat(result.adoptedVerdict()).isNotNull();
            assertThat(result.adoptedVerdict().accepted()).isTrue();
            assertThat(result.adoptedVerdict().reason()).contains("v1", "v2", "多数决");
            assertThat(result.disagreement()).isNull();
            assertThat(result.absentees()).isEmpty();
        }
    }

    @Test
    void majority_eachValidatorReturnsItsOwnVerdictAndReason() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("v1", data -> Verdict.accept("看到A:" + data));
            arbiter.register("v2", data -> Verdict.reject("看到B:" + data));

            ArbitrationResult result = arbiter.arbitrate("XYZ");
            Map<String, ValidatorVote> byId = result.votes().stream()
                    .collect(Collectors.toMap(ValidatorVote::validatorId, v -> v));

            assertThat(byId.get("v1").verdict()).isEqualTo(Verdict.accept("看到A:XYZ"));
            assertThat(byId.get("v2").verdict()).isEqualTo(Verdict.reject("看到B:XYZ"));
        }
    }

    @Test
    void weights_decideQuorumInsteadOfHeadCount() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("reject-heavy", fixed(false, "高权重拒绝"), 3.0);
            arbiter.register("accept-a", fixed(true, "通过A"), 1.0);
            arbiter.register("accept-b", fixed(true, "通过B"), 1.0);

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.totalWeight()).isEqualTo(5.0);
            assertThat(result.winningWeight()).isEqualTo(3.0);
            assertThat(result.majorityReached()).isTrue();
            assertThat(result.adoptedVerdict().accepted()).isFalse();
        }
    }

    @Test
    void weights_exactHalfIsTieAndReportsDisagreement() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("a1", fixed(true, "通过"), 2.0);
            arbiter.register("a2", fixed(true, "通过"), 1.0);
            arbiter.register("r1", fixed(false, "拒绝"), 3.0);

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.totalWeight()).isEqualTo(6.0);
            assertThat(result.majorityReached()).isFalse();
            assertThat(result.adoptedVerdict()).isNull();
            assertThat(result.disagreement()).isNotNull();
            assertThat(result.winningWeight()).isEqualTo(3.0);
        }
    }

    @Test
    void evenValidatorCount_splitVoteIsDisagreementNotArbitraryWinner() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("a1", fixed(true, "通过"));
            arbiter.register("a2", fixed(true, "通过"));
            arbiter.register("r1", fixed(false, "拒绝"));
            arbiter.register("r2", fixed(false, "拒绝"));

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.majorityReached()).isFalse();
            assertThat(result.disagreement()).isNotNull();
            DisagreementReport report = result.disagreement();
            assertThat(report.groups()).hasSize(2);
            assertThat(report.groups()).extracting(g -> g.verdict().accepted())
                    .containsExactlyInAnyOrder(true, false);
            assertThat(report.groups()).allSatisfy(g -> {
                assertThat(g.validatorIds()).hasSize(2);
                assertThat(g.totalWeight()).isEqualTo(2.0);
            });
            assertThat(report.summary()).contains("未形成多数决", "分歧点");
        }
    }

    @Test
    void timeout_slowValidatorMarkedAbsentAndArbiterDoesNotWait() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofMillis(150))) {
            arbiter.register("fast-1", fixed(true, "通过"));
            arbiter.register("fast-2", fixed(true, "通过"));
            arbiter.register("slow", slow(5_000));

            long start = System.nanoTime();
            ArbitrationResult result = arbiter.arbitrate("payload");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(2_000);
            assertThat(result.absentees()).containsExactly("slow");
            ValidatorVote slowVote = result.votes().stream()
                    .filter(v -> v.validatorId().equals("slow")).findFirst().orElseThrow();
            assertThat(slowVote.present()).isFalse();
            assertThat(slowVote.verdict()).isNull();
            assertThat(slowVote.detail()).contains("缺席", "150");
            assertThat(result.majorityReached()).isTrue();
            assertThat(result.adoptedVerdict().accepted()).isTrue();
        }
    }

    @Test
    void absence_throwsAreAlsoRecordedAsAbsentAndCountInDenominator() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("good", fixed(true, "通过"));
            arbiter.register("broken", exploding());

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.absentees()).containsExactly("broken");
            assertThat(result.totalWeight()).isEqualTo(2.0);
            assertThat(result.majorityReached()).isFalse();
            assertThat(result.votes().stream()
                    .filter(v -> v.validatorId().equals("broken")).findFirst().orElseThrow()
                    .detail()).contains("异常");
        }
    }

    @Test
    void disagreementReport_listsGroupsValidatorsAndDisputePoint() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofMillis(150))) {
            arbiter.register("a1", fixed(true, "金额与合同一致"));
            arbiter.register("r1", fixed(false, "金额与合同不符"));
            arbiter.register("slow", slow(5_000));

            ArbitrationResult result = arbiter.arbitrate("payload");

            assertThat(result.majorityReached()).isFalse();
            DisagreementReport report = result.disagreement();
            assertThat(report.absentees()).containsExactly("slow");
            assertThat(report.groups()).hasSize(2);
            assertThat(report.describe()).contains("ACCEPT", "REJECT", "a1", "r1");
            assertThat(report.summary())
                    .contains("分歧点")
                    .contains("金额与合同一致")
                    .contains("金额与合同不符")
                    .contains("slow");
        }
    }

    @Test
    void reliabilityProfile_tracksAgreementWithMajorityAndAbsence() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("always-accept", fixed(true, "始终通过"));
            arbiter.register("always-reject", fixed(false, "始终拒绝"));
            arbiter.register("majority-follower",
                    data -> data.contains("ok") ? Verdict.accept("跟随通过") : Verdict.reject("跟随拒绝"));

            for (String data : List.of("ok", "bad", "ok", "bad")) {
                arbiter.arbitrate(data);
            }

            ReliabilityProfile follower = arbiter.reliabilityOf("majority-follower");
            assertThat(follower.agreementRate()).isEqualTo(1.0);
            assertThat(follower.participationCount()).isEqualTo(4);
            assertThat(follower.absenceCount()).isZero();

            ReliabilityProfile alwaysAccept = arbiter.reliabilityOf("always-accept");
            ReliabilityProfile alwaysReject = arbiter.reliabilityOf("always-reject");
            assertThat(alwaysAccept.agreementRate()).isEqualTo(0.5);
            assertThat(alwaysAccept.agreementCount()).isEqualTo(2);
            assertThat(alwaysReject.agreementRate()).isEqualTo(0.5);
            assertThat(alwaysReject.agreementCount()).isEqualTo(2);
        }
    }

    @Test
    void reliabilityProfile_absentRoundsExcludedFromRateButRecorded() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofMillis(150))) {
            arbiter.register("a", fixed(true, "通过"));
            arbiter.register("slow", slow(5_000));

            arbiter.arbitrate("payload");

            ReliabilityProfile profile = arbiter.reliabilityOf("slow");
            assertThat(profile.registeredRounds()).isEqualTo(1);
            assertThat(profile.absenceCount()).isEqualTo(1);
            assertThat(profile.participationCount()).isZero();
            assertThat(profile.agreementRate()).isNaN();
        }
    }

    @Test
    void stats_countsRoundsMajorityDisagreementAndProfiles() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("a", fixed(true, "通过"));
            arbiter.register("b", fixed(true, "通过"));
            arbiter.register("c", fixed(false, "拒绝"));

            arbiter.arbitrate("payload-1");
            arbiter.arbitrate("payload-2");
            arbiter.register("d", fixed(false, "拒绝"));
            arbiter.arbitrate("payload-3");

            ArbitrationStats stats = arbiter.stats();
            assertThat(stats.totalRounds()).isEqualTo(3);
            assertThat(stats.majorityRounds()).isEqualTo(2);
            assertThat(stats.disagreementRounds()).isEqualTo(1);
            assertThat(stats.profiles()).hasSize(4);
            assertThat(stats.profilesById()).containsKeys("a", "b", "c", "d");
            assertThat(stats.agreementRate("a")).contains(2.0 / 3.0);
            assertThat(stats.agreementRate("c")).contains(0.0);
            assertThat(stats.agreementRate("d")).contains(0.0);
        }
    }

    @Test
    void registration_duplicateAndEmptyArbiterAreRejected() {
        try (QuorumArbiter arbiter = new QuorumArbiter(Duration.ofSeconds(1))) {
            arbiter.register("v1", fixed(true, "通过"));
            assertThatThrownBy(() -> arbiter.register("v1", fixed(false, "拒绝")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QuorumArbiter(Duration.ZERO).close())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> arbiter.register("bad", fixed(true, "x"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
