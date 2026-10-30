package com.moveinsync.roster.config;

import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.domain.policy.EmployeeMerger;
import com.moveinsync.roster.domain.policy.FieldOwnershipPolicy;
import com.moveinsync.roster.domain.policy.VersionGate;
import com.moveinsync.roster.domain.reconcile.BucketedSnapshotDiffer;
import com.moveinsync.roster.domain.reconcile.SafetyBrake;
import com.moveinsync.roster.domain.reconcile.SnapshotReader;
import com.moveinsync.roster.domain.validation.RecordValidationChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the plain-Java core classes as beans. They have no Spring dependencies themselves. */
@Configuration
public class DomainConfig {

    @Bean
    RecordHasher recordHasher(RosterProperties props) {
        return new RecordHasher(props.buckets());
    }

    @Bean
    VersionGate versionGate(RecordHasher hasher) {
        return new VersionGate(hasher);
    }

    @Bean
    EmployeeMerger employeeMerger(RecordHasher hasher) {
        return new EmployeeMerger(hasher);
    }

    @Bean
    FieldOwnershipPolicy fieldOwnershipPolicy() {
        return new FieldOwnershipPolicy();
    }

    @Bean
    RecordValidationChain recordValidationChain() {
        return RecordValidationChain.standard();
    }

    @Bean
    BucketedSnapshotDiffer bucketedSnapshotDiffer() {
        return new BucketedSnapshotDiffer();
    }

    @Bean
    SafetyBrake safetyBrake() {
        return SafetyBrake.standard();
    }

    @Bean
    SnapshotReader snapshotReader() {
        return new SnapshotReader();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
