package co.xenastudios.xvote.api;

/**
 * A stretch of time votes are totalled over. Days, weeks and months start at midnight
 * in the time zone the server owner set, and weeks on the day they chose.
 *
 * @since 1.0.0
 */
public enum Period {
    /** Today. */
    DAY,
    /** This week. */
    WEEK,
    /** This month. */
    MONTH,
    /** Every vote ever counted. */
    ALL
}
