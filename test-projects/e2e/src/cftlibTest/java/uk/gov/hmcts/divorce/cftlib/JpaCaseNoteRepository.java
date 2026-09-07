package uk.gov.hmcts.divorce.cftlib;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaCaseNoteRepository extends JpaRepository<JpaCaseNote, Long> {
}
