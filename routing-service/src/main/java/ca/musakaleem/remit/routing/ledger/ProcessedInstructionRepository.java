package ca.musakaleem.remit.routing.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the idempotency ledger. */
public interface ProcessedInstructionRepository
    extends JpaRepository<ProcessedInstruction, String> {}
