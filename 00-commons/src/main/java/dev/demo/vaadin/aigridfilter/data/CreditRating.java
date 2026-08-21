package dev.demo.vaadin.aigridfilter.data;

/** Creditworthiness rating, derived from a normalized credit score (0–100). */
public enum CreditRating {

    GOOD("Creditworthy"),
    MEDIUM("Limited"),
    POOR("At risk");

    /** Minimum score (inclusive) to be considered {@link #GOOD}. */
    static final int GOOD_THRESHOLD = 70;

    /** Minimum score (inclusive) to be considered {@link #MEDIUM}; below this is {@link #POOR}. */
    static final int MEDIUM_THRESHOLD = 40;

    private final String label;

    CreditRating(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Maps a normalized credit score (0-100) to a rating. */
    public static CreditRating fromScore(int score) {
        if (score >= GOOD_THRESHOLD) {
            return GOOD;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return MEDIUM;
        }
        return POOR;
    }

    /** Lowest score (inclusive) that falls into this rating — used to filter by rating. */
    public int minScoreInclusive() {
        return switch (this) {
            case GOOD -> GOOD_THRESHOLD;
            case MEDIUM -> MEDIUM_THRESHOLD;
            case POOR -> Integer.MIN_VALUE;
        };
    }

    /** Highest score (inclusive) that falls into this rating — used to filter by rating. */
    public int maxScoreInclusive() {
        return switch (this) {
            case GOOD -> Integer.MAX_VALUE;
            case MEDIUM -> GOOD_THRESHOLD - 1;
            case POOR -> MEDIUM_THRESHOLD - 1;
        };
    }
}
