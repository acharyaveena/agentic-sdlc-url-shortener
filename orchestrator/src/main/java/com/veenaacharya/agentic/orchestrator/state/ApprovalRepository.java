package com.veenaacharya.agentic.orchestrator.state;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    /** Latest approval for the stage; a re-planned stage has one per revision. */
    Optional<Approval> findFirstByRunIdAndStageIdOrderByIdDesc(String runId, String stageId);

    List<Approval> findByRunIdOrderByIdAsc(String runId);
}
