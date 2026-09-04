package uk.gov.hmcts.divorce.cftlib;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "case_notes")
public class JpaCaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "case_notes_sequence")
    @SequenceGenerator(name = "case_notes_sequence", sequenceName = "case_notes_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private long reference;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private String note;

    protected JpaCaseNote() {
    }

    public JpaCaseNote(long reference, String author, String note) {
        this.reference = reference;
        this.author = author;
        this.note = note;
    }
}
