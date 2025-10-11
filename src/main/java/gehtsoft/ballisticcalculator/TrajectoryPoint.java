package gehtsoft.ballisticcalculator;

import javax.measure.*;
import javax.measure.quantity.*;

import si.uom.SI;
import tech.units.indriya.quantity.Quantities;

@java.lang.SuppressWarnings("java:S3252") //false positive for java's own SI.*
/**
 * One point of a calculated trajectory.
 */
public class TrajectoryPoint
{
    private Quantity<Length> mDistance;
    private Quantity<Length> mDistanceFlat;
    private Quantity<Speed> mVelocity;
    private double mMach;
    private Quantity<Length> mDrop;
    private Quantity<Length> mDropFlat;
    private Quantity<Length> mWindage;
    private Quantity<Energy> mEnergy;
    private Quantity<Angle> mHold;
    private Quantity<Angle> mWindageAdjustment;
    private Quantity<Time> mTime;

    /**
     * The distance from the start of the trajectory (along line of sight).
     */
    public Quantity<Length> getDistance() {
        return mDistance;
    }

    /**
     * The horizontal distance traveled from the muzzle (flat distance).
     * When shooting at an angle, this is less than the distance along line of sight.
     */
    public Quantity<Length> getDistanceFlat() {
        return mDistanceFlat;
    }

    /**
     * The velocity of the projectile
     */
    public Quantity<Speed> getVelocity() {
        return mVelocity;
    }

    /**
     * The mach number corresponding to the velocity in the current weather conditions.
     * @return
     */
    public double getMach() {
        return mMach;
    }

    /**
     * The drop (vertical deviation) of the projectile vs the line of sight.
     * When shooting at an angle, this is the drop relative to the rotated line of sight.
     *
     * Positive value is over the line of sight, negative value is under the line of sight.
     */
    public Quantity<Length> getDrop() {
        return mDrop;
    }

    /**
     * The vertical drop of the projectile relative to the muzzle (flat drop).
     * This is the actual vertical distance, not rotated for angled shots.
     */
    public Quantity<Length> getDropFlat() {
        return mDropFlat;
    }

    /**
     * The windage (horizontal deviation) of the projectile vs the line of sight.
     *
     * Positive value is to the left of the line of sight, negative value is to the right of the line of sight.
     */
    public Quantity<Length> getWindage() {
        return mWindage;
    }

    /** 
     * The kinetic energy of the projectile.
     */
    public Quantity<Energy> getEnergy() {
        return mEnergy;       
    }
    
    /**
     * Sight adjustment to the drop.
     */
    public Quantity<Angle> getHold() {
        return mHold;
    }

    /**
     * Sight adjustment to the windage.
     */
    public Quantity<Angle> getWindageAdjustment() {
        return mWindageAdjustment;
    }

    /**
     * Sight adjustment to the windage.
     */
    public Quantity<Time> getFlightTime() {
        return mTime;
    }

    /**
     * Constructor (for calculation)
     */
    public TrajectoryPoint(final Quantity<Mass> bulletWeight,
                           final Quantity<Length> distance,
                           final Quantity<Length> distanceFlat,
                           final Quantity<Speed> velocity,
                           final double mach,
                           final Quantity<Length> drop,
                           final Quantity<Length> dropFlat,
                           final Quantity<Length> windage,
                           Quantity<Time> time) {
        mDistance = distance;
        mDistanceFlat = distanceFlat;
        mVelocity = velocity;
        mMach = mach;
        mDrop = drop;
        mDropFlat = dropFlat;
        mWindage = windage;
        mEnergy = calculateEnergy(bulletWeight, velocity);
        mTime = time;

        if (distance.getValue().doubleValue() > 0) {
            mHold = calculateAngle(distance, drop);
            mWindageAdjustment = calculateAngle(distance, windage);
        } else {
            mHold = null;
            mWindageAdjustment = null;
        }
    }

    @java.lang.SuppressWarnings("java:S107") //this is serialization constructor, it has to be this
    /**
     * Constructor (for serialization)
     */
    public TrajectoryPoint(final Quantity<Length> distance,
                           final Quantity<Length> distanceFlat,
                           final Quantity<Speed> velocity,
                           final Quantity<Energy> energy,
                           final double mach,
                           final Quantity<Length> drop,
                           final Quantity<Length> dropFlat,
                           final Quantity<Angle> hold,
                           final Quantity<Length> windage,
                           final Quantity<Angle> windageAdjustment,
                           Quantity<Time> time) {
        mDistance = distance;
        mDistanceFlat = distanceFlat;
        mVelocity = velocity;
        mMach = mach;
        mDrop = drop;
        mDropFlat = dropFlat;
        mWindage = windage;
        mEnergy = energy;
        mTime = time;
        mHold = hold;
        mWindageAdjustment = windageAdjustment;
    }
    
    private static Quantity<Energy> calculateEnergy(Quantity<Mass> mass, Quantity<Speed> velocity) {
        double massInKg = mass.getUnit().getConverterTo(SI.KILOGRAM).convert(mass.getValue()).doubleValue();
        double velocityInMetersPerSecond = velocity.getUnit().getConverterTo(SI.METRE_PER_SECOND).convert(velocity.getValue()).doubleValue();
        double energy = massInKg * velocityInMetersPerSecond * velocityInMetersPerSecond / 2;
        return Quantities.getQuantity(energy, SI.JOULE);
    }

    private static Quantity<Angle> calculateAngle(Quantity<Length> distance, Quantity<Length> deviation) {
        double distanceInMeters = distance.getUnit().getConverterTo(SI.METRE).convert(distance.getValue()).doubleValue();
        double deviationInMeters = deviation.getUnit().getConverterTo(SI.METRE).convert(deviation.getValue()).doubleValue();
        double angleInRadians = Math.atan(deviationInMeters / distanceInMeters);
        return Quantities.getQuantity(angleInRadians, SI.RADIAN);
    }
}