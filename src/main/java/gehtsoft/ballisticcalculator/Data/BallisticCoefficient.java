package gehtsoft.ballisticcalculator.data;

import java.util.Objects;

import gehtsoft.ballisticcalculator.drag.*;

/**
 * Ballistic coefficient of a projectile
 */
public class BallisticCoefficient {
    private double mValue;
    private IDragTable mDragTable;
    private BallisticCoefficientValueType mType;

    /** 
     * Returns the value of the coefficient
     */
    public double getValue() {
        return mValue;
    }

    /**
     * Returns the drag table.
     */
    public IDragTable getDragTable() {
        return mDragTable;
    }

    /**
     * The type of the coefficient value. 
     */
    public BallisticCoefficientValueType getType() {
        return mType;
    }

    /**
     * Constructor for a standard coefficient
     *
     * @param value The value of the coefficient
     * @param type The type of the coefficient
     */
    public BallisticCoefficient(double value, DragTableId tableId)  {
        mValue = value;
        mType = BallisticCoefficientValueType.COEFFICIENT;
        mDragTable = StandardDragTableFactory.getInstance().getTable(tableId);
    }

    public BallisticCoefficient(double value, BallisticCoefficientValueType type, IDragTable dragTable) {
        mValue = value;
        mType = type;
        mDragTable = dragTable;
    }

    private DragTableId tableId() {
        return mDragTable == null ? null : mDragTable.getID();
    }

    /**
     * Two ballistic coefficients are equal when they have the same value type, the same drag table
     * and a numeric value that matches within a 1e-7 absolute tolerance (avoids round-trip/parse
     * mismatches).
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof BallisticCoefficient))
            return false;
        BallisticCoefficient other = (BallisticCoefficient) obj;
        return mType == other.mType &&
               tableId() == other.tableId() &&
               Math.abs(mValue - other.mValue) < 1e-7;
    }

    /** The hash code intentionally omits the numeric value (which is compared with a tolerance) so the
     * equals/hashCode contract holds. */
    @Override
    public int hashCode() {
        return Objects.hash(mType, tableId());
    }
}