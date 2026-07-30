package dev.demo.vaadin.aigridfilter.canonicalquery;

import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores one canonical query against one module's filter mechanism.
 * <p>
 * Every AI module's canonical-query IT scores a query on the <b>resulting customer set</b>, not on the
 * shape of the extracted filter: the query goes through that module's mechanism, the returned filter is
 * executed against the seeded database, and the resulting ids are compared with the ids a reference
 * predicate selects. That comparison — and the log line that makes a run readable — is identical
 * everywhere, so it lives here; what differs per module is the {@link Outcome} and how a query string
 * becomes a set of ids, and both are passed in.
 */
public final class CanonicalQueryRunner {

    private CanonicalQueryRunner() {
    }

    /**
     * Runs {@code canonical} through {@code resolveMatchingIds} and asserts the result matches
     * {@code outcome}.
     *
     * @param canonical          the query to score
     * @param outcome            whether the calling module's filter type can express it
     * @param allCustomers       the seeded data, projected onto {@link CanonicalCustomer}
     * @param resolveMatchingIds the module's mechanism: query string in, matching customer ids out
     * @param logger             the calling IT's logger, so each run stays attributable to its variant
     */
    public static void check(CanonicalQuery canonical, Outcome outcome,
                             List<CanonicalCustomer> allCustomers,
                             Function<String, Set<Long>> resolveMatchingIds,
                             Logger logger) {
        Set<Long> actual = resolveMatchingIds.apply(canonical.query());
        List<Set<Long>> acceptable = canonical.acceptableIdSets(allCustomers);

        logger.info("{} [{}] '{}' -> {} of {} customers, acceptable sizes {}",
                canonical.name(), outcome, canonical.query(), actual.size(), allCustomers.size(),
                acceptable.stream().map(Set::size).toList());

        if (outcome == Outcome.PASSES) {
            assertThat(acceptable)
                    .as("%s: the filtered customer set (%d rows) must equal one of the expected sets %s",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .contains(actual);
        } else {
            assertThat(acceptable)
                    .as("%s cannot be expressed by this variant's filter type, yet the filtered customer "
                            + "set (%d rows) matched an expected set %s — an accidental capability worth "
                            + "looking at, not a green test",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .doesNotContain(actual);
        }
    }
}
