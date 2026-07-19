package gehtsoft.ballisticcalculator;

import javax.measure.*;
import javax.measure.quantity.*;

import gehtsoft.ballisticcalculator.data.*;
import gehtsoft.ballisticcalculator.drag.*;
import gehtsoft.ballisticcalculator.units.*;
import si.uom.SI;
import systems.uom.common.Imperial;
import systems.uom.unicode.CLDR;
import tech.units.indriya.quantity.Quantities;

@java.lang.SuppressWarnings("java:S3252") //false positive for java's own SI.*
public class TrajectoryCalculator {
    /** The maximum step size of the calculation.
      * The default value is 10cm
      */
    private Quantity<Length> mMaximumCalculationStepSize = Quantities.getQuantity(0.1, SI.METRE);

    /** The maximum drop value to stop further calculation */
    private Quantity<Length> mMaximumDrop = Quantities.getQuantity(-1000, SI.METRE);

    /** The minimum velocity to stop the calculation */
    private Quantity<Speed> mMinimumVelocity = Quantities.getQuantity(1, SI.METRE_PER_SECOND);


    /** PIR = (PI/8)*(RHO0/144) */
    private static final double PIR = 2.08551e-04;

    @java.lang.SuppressWarnings("java:S3776") //splitting this method into smaller ones would affect performance
    /**
      * Calculates the sight angle for the specified zero distance
      */
    public Quantity<Angle> calculateSightAngle(Projectile ammunition, Weapon rifle, Atmosphere atmosphere) {
        var rangeTo = rifle.getZeroingInformation().getZeroDistance().multiply(2);
        var step = rifle.getZeroingInformation().getZeroDistance().multiply(0.01);
        var calculationStep = UnitUtils.in(getCalculationStep(step), CLDR.FOOT);
        var dragTable = ammunition.getBallisticCoefficient().getDragTable();

        //calculation in imperial units:
        //distance & altitude - feet
        //velocity - fps
        //angles - radians

        if (atmosphere == null)
            atmosphere = new Atmosphere();

        var alt0 = UnitUtils.in(atmosphere.getAltitude(), CLDR.FOOT);
        var altDelta = 3.280839895;    //1 meter (matches the C# >1 m density/sound-speed refresh)

        double densityFactor = 0;
        double drag;
        var mach = 0.0;

        var sightAngle = UnitUtils.in(Quantities.getQuantity(150, BCUnits.MOA), SI.RADIAN);
        var barrelAzimuth = 0;  //0 radians

        var zeroDistance = UnitUtils.in(rifle.getZeroingInformation().getZeroDistance(), CLDR.FOOT);

        for (int approximation = 0; approximation < 100; approximation++) {
            var barrelElevation = sightAngle;

            var velocity = UnitUtils.in(ammunition.getMuzzleVelocity(), BCUnits.FEET_PER_SECOND);

            //x - distance towards target,
            //y - drop and
            //z - windage
            var rangeVector = new Vector(
                0,
                UnitUtils.in(rifle.getZeroingInformation().getSightHeight().negate(), CLDR.FOOT),
                0);

            var velocityVector = new Vector(
                velocity * Math.cos(barrelElevation) * Math.cos(barrelAzimuth),
                velocity * Math.sin(barrelElevation),
                velocity * Math.cos(barrelElevation) * Math.sin(barrelAzimuth));

            var maximumRange = rangeTo;
            var lastAtAltitude = -10000.0;
            IDragTableNode dragTableNode = null;

            double ballisticFactor = 1.0 / ammunition.getBallisticCoefficientValue();
            var accumulatedFactor = PIR * ballisticFactor;
            var earthGravity = 32.17405;
            var rawMaximumRange = UnitUtils.in(maximumRange, CLDR.FOOT);
            var rawMaximumDrop = UnitUtils.in(mMaximumDrop, CLDR.FOOT);
            var rawMinimumVelocity = UnitUtils.in(mMinimumVelocity, BCUnits.FEET_PER_SECOND);

            //run all the way down the range
            while (rangeVector.getX() <= rawMaximumRange &&
                   velocity >= rawMinimumVelocity &&
                   rangeVector.getY() >= rawMaximumDrop) {
                var alt = alt0 + rangeVector.getY();

                if (Math.abs(alt - lastAtAltitude) > altDelta) {
                    var a = Quantities.getQuantity(alt, CLDR.FOOT);
                    var t = atmosphere.getTemperatureAtAltitude(a);
                    densityFactor = atmosphere.getDensityFactorForTemperature(a, t);
                    mach = UnitUtils.in(atmosphere.getSpeedOfSound(t), BCUnits.FEET_PER_SECOND);
                    lastAtAltitude = alt;
                }

                double deltaTime = calculationStep / velocityVector.getX();
                double currentMach = velocity / mach;

                //find Mach node for the first time
                if (dragTableNode == null)
                    dragTableNode = dragTable.find(currentMach);

                //walk towards the beginning of the table as the air-relative speed drops
                while (dragTableNode.getMach() > currentMach)
                    dragTableNode = dragTableNode.getPrevious();
                //walk forward again when the air-relative speed rises (inclined/transonic shots)
                while (dragTableNode.getNext() != null && dragTableNode.getNext().getMach() <= currentMach)
                    dragTableNode = dragTableNode.getNext();

                var cd = dragTableNode.calculateDrag(currentMach);
                drag = accumulatedFactor * densityFactor *
                       cd *
                       velocity;

                velocityVector = new Vector(
                    velocityVector.getX() - velocityVector.getX() * (deltaTime * drag),
                    velocityVector.getY() - velocityVector.getY() * (deltaTime * drag)
                                          - (earthGravity * deltaTime),
                    velocityVector.getZ() - velocityVector.getZ() * (deltaTime * drag));

                var deltaRangeVector = new Vector(calculationStep,
                        velocityVector.getY() * deltaTime,
                        velocityVector.getZ() * deltaTime);

                rangeVector = rangeVector.add(deltaRangeVector);

                if (Math.abs(rangeVector.getX() - zeroDistance) <= calculationStep) {
                    if (Math.abs(rangeVector.getY()) < 0.0001)
                        return Quantities.getQuantity(sightAngle, SI.RADIAN);

                    var adj = Quantities.getQuantity(UnitUtils.in(Quantities.getQuantity(rangeVector.getY(), CLDR.FOOT), SI.METRE) * 100 / (UnitUtils.in(rifle.getZeroingInformation().getZeroDistance(), SI.METRE) / 100),
                              BCUnits.CENTIMETERS_PER_100METRES);

                    sightAngle = sightAngle - UnitUtils.in(adj, SI.RADIAN);
                    break;
                }

                velocity = velocityVector.getMagnitude();
            }
        }
        throw new IllegalArgumentException("Cannot find zero parameters for the specified zeroing information");
    }

    @java.lang.SuppressWarnings("java:S3776") //splitting this method into smaller ones would affect performance
    /**
      * Calculates the trajectory for the specified parameters.
      */
    public TrajectoryPoint[] calculate(Projectile ammunition, Weapon rifle, Atmosphere atmosphere, ShotParameters shot, Wind wind)
    {
        var rangeTo = UnitUtils.in(shot.getMaximumDistance(), CLDR.FOOT);
        var step = UnitUtils.in(shot.getStep(), CLDR.FOOT);
        var calculationStep = UnitUtils.in(getCalculationStep(rifle.getZeroingInformation().getZeroDistance().multiply(0.01)), CLDR.FOOT);
        var dragTable = ammunition.getBallisticCoefficient().getDragTable();

        if (atmosphere == null)
            atmosphere = new Atmosphere();

        var alt0 = UnitUtils.in(atmosphere.getAltitude(), CLDR.FOOT);
        var altDelta = 3.280839895;    //1 meter (matches the C# >1 m density/sound-speed refresh)

        double densityFactor = 0;
        double drag;
        var mach = 0.0;

        double stabilityCoefficient = 1;
        Boolean calculateDrift;

        if (rifle.getRifling() != null &&
            ammunition.getBulletDiameter() != null &&
            ammunition.getBulletLength() != null)
        {
            stabilityCoefficient = calculateStabilityCoefficient(ammunition, rifle, atmosphere);
            calculateDrift = true;
        }
        else
        {
            calculateDrift = false;
        }

        TrajectoryPoint[] trajectoryPoints = new TrajectoryPoint[(int)(Math.floor(rangeTo / step)) + 1];

        double barrelAzimuth;
        double barrelElevation = UnitUtils.in(shot.getSightAngle(), SI.RADIAN);

        if (shot.getBarrelAzimuth() != null)
            barrelAzimuth = UnitUtils.in(shot.getBarrelAzimuth(), SI.RADIAN);
        else
            barrelAzimuth = 0;

        if (shot.getShotAngle() != null)
            barrelElevation += UnitUtils.in(shot.getShotAngle(), SI.RADIAN);

        // Support for angled shots
        boolean hasShotAngle = shot.getShotAngle() != null;
        double lineOfSight = hasShotAngle ? UnitUtils.in(shot.getShotAngle(), SI.RADIAN) : 0;
        double lineOfSightTan = Math.tan(lineOfSight);
        double lineOfSightCos = Math.cos(lineOfSight);
        double lineOfSightSin = Math.sin(lineOfSight);

        double sightHeightFeet = UnitUtils.in(rifle.getZeroingInformation().getSightHeight(), CLDR.FOOT);
        double barrelElevationTan = Math.tan(barrelElevation);

        // Coriolis / Eötvös setup (per-output-point closed-form correction).
        // Latitude is north-positive; when null the correction is disabled.
        boolean coriolis = shot.getLatitude() != null;
        final double coriolisOmega = 7.2921159e-5;    // Earth rotation, rad/s
        double sinLat = coriolis ? Math.sin(UnitUtils.in(shot.getLatitude(), SI.RADIAN)) : 0;
        double cosLat = coriolis ? Math.cos(UnitUtils.in(shot.getLatitude(), SI.RADIAN)) : 0;
        double coriolisHCoef = coriolisOmega * sinLat;
        double v0mps = UnitUtils.in(ammunition.getMuzzleVelocity(), SI.METRE_PER_SECOND);
        double coriolisVRatio = coriolis
            ? 1.0 - 2.0 * coriolisOmega * cosLat * Math.sin(barrelAzimuth) * v0mps / 9.80665
            : 1.0;

        // Aerodynamic (crosswind) jump - Litz, Applied Ballistics Eq 5.4. A horizontal crosswind
        // gives a spin-stabilized bullet a vertical come-up imparted at the muzzle, then constant in
        // angle at all ranges: Y[MOA per mph] = 0.01*Sg - 0.0024*L_calibers + 0.032. Gated exactly
        // like spin drift (needs Sg + twist + bullet dims). Up for a wind from the right with a
        // right-hand twist (sign flips for left twist / wind from the left).
        double aeroJumpAngle = 0;
        if (Boolean.TRUE.equals(calculateDrift) && wind != null) {
            double lengthCalibers = UnitUtils.in(ammunition.getBulletLength(), Imperial.INCH)
                                  / UnitUtils.in(ammunition.getBulletDiameter(), Imperial.INCH);
            double aeroJumpMoaPerMph = 0.01 * stabilityCoefficient - 0.0024 * lengthCalibers + 0.032;
            // Muzzle crosswind, positive = from the right (wind direction 90 degrees).
            double crossFromRightMph = UnitUtils.in(wind.getSpeed(), CLDR.MILE_PER_HOUR)
                                     * Math.sin(UnitUtils.in(wind.getDirection(), SI.RADIAN));
            int verticalJumpSign = rifle.getRifling().getTwistDirection() == TwistDirection.RIGHT ? 1 : -1;
            double aeroJumpMoa = aeroJumpMoaPerMph * crossFromRightMph * verticalJumpSign;
            aeroJumpAngle = UnitUtils.in(Quantities.getQuantity(aeroJumpMoa, BCUnits.MOA), SI.RADIAN);
        }

        var velocity = UnitUtils.in(ammunition.getMuzzleVelocity(), BCUnits.FEET_PER_SECOND);
        double time = 0;

        Vector windVector;
        if (wind == null)
        {
            windVector = new Vector(0, 0, 0);
        }
        else
        {
            windVector = calculateWindVector(shot, wind, BCUnits.FEET_PER_SECOND);
        }

        //x - distance towards target,
        //y - drop and
        //z - windage
        var rangeVector = new Vector(0,
                                     -UnitUtils.in(rifle.getZeroingInformation().getSightHeight(), CLDR.FOOT),
                                     0);

        // The bullet is always integrated along the line of fire (vz = 0). Azimuth no longer tilts
        // the trajectory; it enters only as a scalar into the Coriolis/Eötvös terms above.
        var velocityVector = new Vector(velocity * Math.cos(barrelElevation),
                                        velocity * Math.sin(barrelElevation),
                                        0);
        int currentItem = 0;
        var maximumRange = rangeTo + calculationStep / lineOfSightCos;
        var nextRangeDistance = 0;

        var lastAtAltitude = -1000000.0;
        IDragTableNode dragTableNode = null;

        double ballisticFactor = 1.0 / ammunition.getBallisticCoefficientValue();
        var accumulatedFactor = PIR * ballisticFactor;
        var earthGravity = 32.17405;

        var rawMaximumDrop = UnitUtils.in(mMaximumDrop, CLDR.FOOT);
        var rawMinimumVelocity = UnitUtils.in(mMinimumVelocity, BCUnits.FEET_PER_SECOND);

        //run all the way down the range. The end guard is compared in the line-of-sight frame
        //(rangeVector.getX() / lineOfSightCos) so inclined shots do not stop one output step early.
        while (rangeVector.getX() / lineOfSightCos <= maximumRange &&
               velocity >= rawMinimumVelocity &&
               rangeVector.getY() >= rawMaximumDrop)
        {
            var alt = alt0 + rangeVector.getY();

            //update density and Mach velocity each 10 feet of altitude
            if (Math.abs(lastAtAltitude - alt) > altDelta)
            {
                var a = Quantities.getQuantity(alt, CLDR.FOOT);
                var t = atmosphere.getTemperatureAtAltitude(a);
                densityFactor = atmosphere.getDensityFactorForTemperature(a, t);
                mach = UnitUtils.in(atmosphere.getSpeedOfSound(t), BCUnits.FEET_PER_SECOND);
                lastAtAltitude = alt;
            }

            // Calculate actual distance along line of sight for angled shots
            double distance = rangeVector.getX() / lineOfSightCos;

            if (distance >= nextRangeDistance)
            {
                var windage = rangeVector.getZ();
                if (Boolean.TRUE.equals(calculateDrift))
                    // Spin drift is horizontal; on an inclined shot only its cos-projection lies in
                    // the reported windage plane.
                    windage += (1.25 * (stabilityCoefficient + 1.2) *
                                       Math.pow(time, 1.83) *
                                       (rifle.getRifling().getTwistDirection() == TwistDirection.RIGHT ? -1 : 1))
                               / 12.0 * lineOfSightCos;

                // Coriolis / Eötvös correction (skipped when latitude is null).
                double ryEffective = rangeVector.getY();
                if (coriolis) {
                    // Horizontal deflection: right in the N hemisphere (windage is left +, right -).
                    windage -= coriolisHCoef * distance * time;
                    // Eötvös: scale only the gravitational fall below the vacuum bore line, so the
                    // muzzle drop (where the fall is zero) stays exactly -sightHeight.
                    double boreLine = rangeVector.getX() * barrelElevationTan - sightHeightFeet;
                    ryEffective = boreLine + (rangeVector.getY() - boreLine) * coriolisVRatio;
                }

                // Calculate drop and perform coordinate rotation for angled shots
                double drop = ryEffective;
                double distanceFlat = rangeVector.getX();
                double dropFlat = ryEffective;

                if (hasShotAngle) {
                    // Rotate coordinates from muzzle frame to line-of-sight frame. sightHeight is added
                    // in the vertical frame but subtracted back in the ROTATED frame; the residual
                    // sightHeight*(cos-1) pins the muzzle drop to exactly -sightHeight.
                    double y = ryEffective + sightHeightFeet;
                    double yRotated = -rangeVector.getX() * lineOfSightSin + y * lineOfSightCos;
                    drop = yRotated - sightHeightFeet;
                }

                // Aerodynamic (crosswind) jump: a constant angle => a vertical offset linear in range
                // (positive = up => less drop). Additive to the Eötvös scaling above and independent
                // of spin drift (which is horizontal).
                if (aeroJumpAngle != 0) {
                    drop += aeroJumpAngle * distance;
                    dropFlat += aeroJumpAngle * rangeVector.getX();
                }

                trajectoryPoints[currentItem] = new TrajectoryPoint(
                    ammunition.getBulletWeight(),
                    Quantities.getQuantity(distance, CLDR.FOOT),
                    Quantities.getQuantity(distanceFlat, CLDR.FOOT),
                    Quantities.getQuantity(velocity, BCUnits.FEET_PER_SECOND),
                    velocity / mach,
                    Quantities.getQuantity(drop, CLDR.FOOT),
                    Quantities.getQuantity(dropFlat, CLDR.FOOT),
                    Quantities.getQuantity(windage, CLDR.FOOT),
                    Quantities.getQuantity(time, CLDR.SECOND));
                nextRangeDistance += step;
                currentItem++;
                if (currentItem == trajectoryPoints.length)
                    break;
            }
            var deltaTime = calculationStep / velocityVector.getX();
            var velocityAdjusted = velocityVector.subtract(windVector);
            velocity = velocityAdjusted.getMagnitude();
            double currentMach = velocity / mach;

            //find Mach node for the first time
            if (dragTableNode == null)
                dragTableNode = dragTable.find(currentMach);

            //walk towards the beginning of the table as the air-relative speed drops
            while (dragTableNode.getMach() > currentMach)
                dragTableNode = dragTableNode.getPrevious();
            //walk forward again when the air-relative speed rises (inclined/transonic shots)
            while (dragTableNode.getNext() != null && dragTableNode.getNext().getMach() <= currentMach)
                dragTableNode = dragTableNode.getNext();

            var cd = dragTableNode.calculateDrag(currentMach);
            drag = accumulatedFactor * densityFactor *
                    cd *
                    velocity;

            velocityVector = new Vector(
                velocityVector.getX() - velocityAdjusted.getX() * (deltaTime * drag),
                velocityVector.getY() - velocityAdjusted.getY() * (deltaTime * drag)
                                        - (earthGravity * deltaTime),
                velocityVector.getZ() - velocityAdjusted.getZ() * (deltaTime * drag));

            var deltaRangeVector = new Vector(calculationStep,
                velocityVector.getY() * deltaTime,
                velocityVector.getZ() * deltaTime);

            rangeVector = rangeVector.add(deltaRangeVector);
            velocity = velocityVector.getMagnitude();
            //flight time is the sum of the integration timesteps (re-deriving it from
            //displacement/speed diverges on angled shots)
            time += deltaTime;
        }

        return trajectoryPoints;
    }

    /** Gets a calculation step */
    private Quantity<Length> getCalculationStep(Quantity<Length> step) {
        while (UnitUtils.compare(step, mMaximumCalculationStepSize) > 0)
            step = step.divide(2);      //do it twice for increased accuracy of velocity calculation and 10 times per step
        return step;
    }

    /** Calculates the vector of wind velocities.
     *
     * Wind direction convention: 0° = toward the target (tailwind), 90° = from the right,
     * 270°/−90° = from the left, 180° = toward the shooter (headwind).
     */
    private static Vector calculateWindVector(ShotParameters shot, Wind wind, Unit<Speed> units) {
        // Project the wind onto the (possibly inclined) line of fire: sight angle plus shot angle.
        double lineOfFire = UnitUtils.in(shot.getSightAngle(), SI.RADIAN);
        if (shot.getShotAngle() != null)
            lineOfFire += UnitUtils.in(shot.getShotAngle(), SI.RADIAN);
        double sightCosine = Math.cos(lineOfFire);
        double sightSine = Math.sin(lineOfFire);

        Quantity<Angle> cantAngle = shot.getCantAngle();
        if (cantAngle == null)
            cantAngle = Quantities.getQuantity(0, SI.RADIAN);
        double cantCosine = Math.cos(UnitUtils.in(cantAngle, SI.RADIAN));
        double cantSine = Math.sin(UnitUtils.in(cantAngle, SI.RADIAN));

        Quantity<Speed> rangeVelocity;
        Quantity<Speed> crossComponent;

        if (wind != null)
        {
            rangeVelocity = Quantities.getQuantity(
                UnitUtils.in(wind.getSpeed(), units) *
                Math.cos(UnitUtils.in(wind.getDirection(), SI.RADIAN)), units);

            crossComponent = Quantities.getQuantity(
                UnitUtils.in(wind.getSpeed(), units) *
                Math.sin(UnitUtils.in(wind.getDirection(), SI.RADIAN)), units);
        }
        else
        {
            rangeVelocity = Quantities.getQuantity(0, units);
            crossComponent = Quantities.getQuantity(0, units);
        }

        Quantity<Speed> rangeFactor = rangeVelocity.negate().multiply(sightSine);

        return new Vector(
            rangeVelocity.multiply(sightCosine).getValue().doubleValue(),
            rangeFactor.multiply(cantCosine).add(crossComponent.multiply(cantSine)).getValue().doubleValue(),
            crossComponent.multiply(cantCosine).subtract(rangeFactor.multiply(cantSine)).getValue().doubleValue());
    }

    /** Returns a bullet stability coefficient */
    private static double calculateStabilityCoefficient(Projectile ammunitionInfo, Weapon rifleInfo, Atmosphere atmosphere) {
        double weight = UnitUtils.in(ammunitionInfo.getBulletWeight(), BCUnits.GRAIN);
        double diameter = UnitUtils.in(ammunitionInfo.getBulletDiameter(), Imperial.INCH);
        double length = UnitUtils.in(ammunitionInfo.getBulletLength(), Imperial.INCH) / diameter;
        double twist = UnitUtils.in(rifleInfo.getRifling().getRiflingStep(), Imperial.INCH) / diameter;

        double sd = 30 * weight / (Math.pow(twist, 2) * Math.pow(diameter, 3) * length * (1 + Math.pow(length, 2)));
        double fv = Math.pow(UnitUtils.in(ammunitionInfo.getMuzzleVelocity(), BCUnits.FEET_PER_SECOND) / 2800, 1.0 / 3.0);
        double ftp = 1;

        if (atmosphere != null)
        {
            double ft = UnitUtils.in(atmosphere.getTemperature(), CLDR.FAHRENHEIT);
            double pt = UnitUtils.in(atmosphere.getPressure(), CLDR.INCH_HG);
            ftp = ((ft + 460.0) / (59.0 + 460.0)) * (29.92 / pt);
        }
        return sd * fv * ftp;
    }
}