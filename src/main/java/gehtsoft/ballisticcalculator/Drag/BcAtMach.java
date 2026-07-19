package gehtsoft.ballisticcalculator.drag;

/**
 * One knot of an effective ballistic-coefficient-vs-Mach profile.
 *
 * Used by {@link DrgDragTableFactory} to synthesize a custom drag curve from a standard base table
 * and a BC that varies with Mach. Immutable.
 */
public class BcAtMach {
    private final double mMach;
    private final double mBc;

    /** The Mach number at which the ballistic coefficient applies (>= 0). */
    public double getMach() {
        return mMach;
    }

    /** The effective ballistic coefficient at that Mach, relative to the base curve (> 0). */
    public double getBc() {
        return mBc;
    }

    /**
     * Constructor
     * @param mach the Mach number at which the coefficient applies
     * @param bc the effective ballistic coefficient at that Mach
     */
    public BcAtMach(double mach, double bc) {
        mMach = mach;
        mBc = bc;
    }
}
