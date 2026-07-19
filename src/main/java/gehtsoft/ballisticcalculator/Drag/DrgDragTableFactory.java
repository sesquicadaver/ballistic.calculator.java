package gehtsoft.ballisticcalculator.drag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import gehtsoft.ballisticcalculator.data.Projectile;

/**
 * Builds a custom drag curve (a {@code GC} table) from a standard base table plus an
 * effective-ballistic-coefficient profile that varies with Mach.
 *
 * On the base table's own Mach grid the custom drag coefficient is {@code Cd_base(M) / BC(M)}, where
 * {@code BC(M)} is piecewise-linear between the (Mach-sorted) knots with flat extrapolation outside
 * the end knots.
 */
public final class DrgDragTableFactory {
    private DrgDragTableFactory() {
    }

    /**
     * Builds the custom drag table.
     *
     * @param ammunition metadata (weight/diameter) attached to the result; must not be null
     * @param baseTable a standard drag table id (G1, G7, ...); must not be {@code GC}
     * @param bcCurve the effective-BC profile; at least one knot, any order, every Bc &gt; 0
     * @return a custom {@link DrgFile} tagged {@code GC}
     */
    public static DrgFile build(Projectile ammunition, DragTableId baseTable, List<BcAtMach> bcCurve) {
        if (ammunition == null)
            throw new IllegalArgumentException("The ammunition must not be null");
        if (bcCurve == null)
            throw new IllegalArgumentException("The BC curve must not be null");
        if (baseTable == DragTableId.GC)
            throw new IllegalArgumentException("The base table must be a standard table, not GC");
        if (bcCurve.isEmpty())
            throw new IllegalArgumentException("The BC curve must contain at least one knot");

        List<BcAtMach> knots = new ArrayList<>(bcCurve);
        for (BcAtMach knot : knots) {
            if (knot.getBc() <= 0)
                throw new IllegalArgumentException("Every BC value must be greater than zero");
        }
        knots.sort(Comparator.comparingDouble(BcAtMach::getMach));

        IDragTable base = StandardDragTableFactory.getInstance().getTable(baseTable);
        int count = base.length();
        DragTableDataPoint[] points = new DragTableDataPoint[count];
        for (int i = 0; i < count; i++) {
            IDragTableNode node = base.get(i);
            double mach = node.getMach();
            double bc = interpolateBc(knots, mach);
            points[i] = new DragTableDataPoint(mach, node.getDragCoefficient() / bc);
        }

        return new DrgFile(null, ammunition.getBulletDiameter(), ammunition.getBulletWeight(), points);
    }

    // Piecewise-linear interpolation of the BC profile with flat extrapolation beyond the end knots.
    private static double interpolateBc(List<BcAtMach> knots, double mach) {
        if (mach <= knots.get(0).getMach())
            return knots.get(0).getBc();
        int last = knots.size() - 1;
        if (mach >= knots.get(last).getMach())
            return knots.get(last).getBc();
        for (int i = 1; i <= last; i++) {
            BcAtMach a = knots.get(i - 1);
            BcAtMach b = knots.get(i);
            if (mach <= b.getMach()) {
                double t = (mach - a.getMach()) / (b.getMach() - a.getMach());
                return a.getBc() + t * (b.getBc() - a.getBc());
            }
        }
        return knots.get(last).getBc();
    }
}
