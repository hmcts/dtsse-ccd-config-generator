package uk.gov.hmcts.example.model.common;

/**
 * A CLASS that a definition {@code FixedLists} ID ({@code crossKindFixedList}) is declared as. The kinds
 * disagree, so {@link RetrofitTypeBinder} refuses the binding: {@code FixedListGenerator} selects on
 * {@code isEnum} and {@code ComplexTypeGenerator} on the absence of it, so pinning the list ID here would
 * name a type the OTHER generator emits — the list would still have no rows and the class's own would move
 * to an ID the definition uses for something else.
 */
public class CrossKindPayload {

  private String detail;
}
