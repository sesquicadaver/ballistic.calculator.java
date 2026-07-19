package gehtsoft.ballisticcalculator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gehtsoft.ballisticcalculator.data.*;
import gehtsoft.ballisticcalculator.drag.*;
import gehtsoft.ballisticcalculator.tools.TrajectoryLoader;
import gehtsoft.ballisticcalculator.units.*;
import si.uom.SI;
import systems.uom.unicode.CLDR;
import tech.units.indriya.quantity.Quantities;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

class TestTrajectoryCalculator {

    @ParameterizedTest
    @CsvSource(value = {
        "0.365, G1, 2600, fps, 100, yd, 5.674",
        "0.365, G1, 2600, fps, 25, yd, 12.84",
        "0.365, G1, 2600, fps, 375, yd, 12.78",
        "0.47, G7, 725, ms, 200, m, 8.171"
    })
    void zeroAngle(double bcValue, String table, double speed, String speedUnits, double zeroDistance, String distanceUnits, double moaSightAngle) {
        var id = table.trim().compareTo("G7") == 0 ? DragTableId.G7 : DragTableId.G1;
        var bc = new BallisticCoefficient(bcValue, id);
        var mvu = speedUnits.trim().compareTo("ms") == 0 ? SI.METRE_PER_SECOND : BCUnits.FEET_PER_SECOND;
        var mv = Quantities.getQuantity(speed, mvu);
        var du = distanceUnits.trim().compareTo("m") == 0 ? SI.METRE : CLDR.YARD;
        var zd = Quantities.getQuantity(zeroDistance, du);
        var sh = Quantities.getQuantity(3.2, CLDR.INCH);
        var ammo = new Projectile(Quantities.getQuantity(8, SI.GRAM), mv, bc);
        var rifle = new Weapon(new ZeroingInformation(sh, zd), null);
        var atmosphere = new Atmosphere();

        var tc = new TrajectoryCalculator();
        var sa = tc.calculateSightAngle(ammo, rifle, atmosphere);
        assertThat(UnitUtils.in(sa, BCUnits.MOA)).isEqualTo(moaSightAngle, within(0.05));
    }

    // Tolerance semantics mirror the C# TrajectoryCalculatorTest: velocity (and distance) are checked
    // as a fraction of the expected value; drop and windage use independent MOA windows scaled to range.
    private void assertThatTrajectoryPointsMatch(TrajectoryPoint value, TrajectoryPoint expected,
            double velocityAccuracyInPercent, double dropAccuracyInMOA, double windageAccuracyInMOA,
            double energyAccuracy, double timeAccuracy, Boolean ignoreMach) {
        assertThat(UnitUtils.in(value.getDistance(), expected.getDistance().getUnit()))
            .isEqualTo(expected.getDistance().getValue().doubleValue(), within(0.5));

        double velocityTolerance = Math.abs(expected.getVelocity().getValue().doubleValue()) * velocityAccuracyInPercent;
        assertThat(UnitUtils.in(value.getVelocity(), expected.getVelocity().getUnit()))
            .isEqualTo(expected.getVelocity().getValue().doubleValue(), within(velocityTolerance));

        if (!ignoreMach)
            assertThat(value.getMach()).isEqualTo(expected.getMach(), within(0.005));

        if (timeAccuracy != 0)
            assertThat(UnitUtils.in(value.getFlightTime(), SI.SECOND))
                .isEqualTo(UnitUtils.in(expected.getFlightTime(), SI.SECOND), within(timeAccuracy));

        if (energyAccuracy != 0) {
            var ev = UnitUtils.in(value.getEnergy(), expected.getEnergy().getUnit());
            assertThat(UnitUtils.in(value.getEnergy(), expected.getEnergy().getUnit()))
                .isEqualTo(expected.getEnergy().getValue().doubleValue(), within(ev * energyAccuracy / 100));
        }

        double rangeYards = UnitUtils.in(expected.getDistance(), CLDR.YARD) / 100;
        double dropTolerance = UnitUtils.in(Quantities.getQuantity(dropAccuracyInMOA, BCUnits.MOA), BCUnits.INCHES_PER_100YARDS) * rangeYards;
        double windageTolerance = UnitUtils.in(Quantities.getQuantity(windageAccuracyInMOA, BCUnits.MOA), BCUnits.INCHES_PER_100YARDS) * rangeYards;
        if (dropTolerance < 1e-3)
            dropTolerance = 1e-3;
        if (windageTolerance < 1e-3)
            windageTolerance = 1e-3;

        assertThat(UnitUtils.in(value.getDrop(), CLDR.INCH))
            .isEqualTo(UnitUtils.in(expected.getDrop(), CLDR.INCH), within(dropTolerance));

        assertThat(UnitUtils.in(value.getWindage(), CLDR.INCH))
            .isEqualTo(UnitUtils.in(expected.getWindage(), CLDR.INCH), within(windageTolerance));
    }

    // Standard ≤1000 yd trajectories. Tolerances match the C# TrajectoryCalculatorTest
    // (velocityAccuracyInPercent, dropAccuracyInMOA, windageAccuracyInMOA); energy/time/mach are
    // Java-side extras. ra4_nowind is Java-only.
    @ParameterizedTest
    @CsvSource(value = {
        "g1_nowind.txt,             0.0015, 0.10, 0.05,  1, 0.005, false,  ",
        // g1_nowind_up goes transonic/subsonic by 1000 yd; the residual there is G1-drag-shape vs
        // Hornady radar data (drag-data, not algorithm), so drop uses 0.20 MOA — same as C#.
        "g1_nowind_up.txt,          0.0015, 0.20, 0.05,  1, 0.01,  false,  ",
        "g1_nowind_up_supersonic.txt, 0.0015, 0.10, 0.05, 1, 0.01, false,  ",
        "g1_wind.txt,               0.0015, 0.10, 0.10,  1, 0.005, false,  ",
        "g1_wind_cold.txt,          0.0015, 0.10, 0.05,  1, 0.005, false,  ",
        "g1_wind_hot.txt,           0.0015, 0.10, 0.10,  1, 0.005, false,  ",
        "g1_twist.txt,              0.0015, 0.10, 0.015, 1, 0.005, false,  ",
        "g7_nowind.txt,             0.0015, 0.10, 0.05,  1, 0.005, false,  ",
        "ra4_nowind.txt,            0.0015, 0.10, 0.10,  1, 0.005, false,  ",
        // custom.txt / custom2.txt exercise the DRG custom-table path; drag is approximated, so their
        // windows are wider than the field-reference cases (Java-specific smoke tests, no C# counterpart).
        "custom.txt,                0.02,   0.75, 0.75,  0, 0.05,  true, drg.txt",
        "custom2.txt,               0.02,   0.75, 0.75,  0, 0.05,  true, drg2.txt",
    })
    void trajectory(String testFile, double velocityAccuracyInPercent, double dropAccuracyInMOA, double windageAccuracyInMOA, double energyAccuracy, double timeAccuracy, Boolean ignoreMach, String customDragTableName)
        throws IOException {
        runTrajectory(testFile, velocityAccuracyInPercent, dropAccuracyInMOA, windageAccuracyInMOA, energyAccuracy, timeAccuracy, ignoreMach, customDragTableName);
    }

    // Coriolis / Eötvös absolute acceptance against Ballistic Explorer, to 2000 yd. Same tolerances
    // as the C# CoriolisTrajectoryTest. Latitude/azimuth come from the shot row of each template.
    @ParameterizedTest
    @CsvSource(value = {
        "be_coriolis_off.txt,   0.005, 0.4, 0.05",
        "be_coriolis_north.txt, 0.005, 0.4, 0.05",
        "be_coriolis_east.txt,  0.005, 0.4, 0.05",
        "be_coriolis_west.txt,  0.005, 0.4, 0.05",
        "be_coriolis_pole.txt,  0.005, 0.4, 0.05",
        "be_coriolis_south.txt, 0.005, 0.4, 0.05",
    })
    void coriolisTrajectory(String testFile, double velocityAccuracyInPercent, double dropAccuracyInMOA, double windageAccuracyInMOA)
        throws IOException {
        // energy skipped, time loosened: at 2000 yd the transonic G1-drag baseline (not Coriolis)
        // dominates energy/time residuals, exactly as the C# test only checks distance/velocity/drop/windage.
        runTrajectory(testFile, velocityAccuracyInPercent, dropAccuracyInMOA, windageAccuracyInMOA, 0, 0, false, null);
    }

    private void runTrajectory(String testFile, double velocityAccuracyInPercent, double dropAccuracyInMOA, double windageAccuracyInMOA, double energyAccuracy, double timeAccuracy, Boolean ignoreMach, String customDragTableName)
        throws IOException {
        DrgFile customDrg = null;
        if (customDragTableName != null) {
            customDrg = DrgFileLoader.loadDragTable(customDragTableName);
        }
        TrajectoryLoader loader = new TrajectoryLoader();
        loader.load(testFile, customDrg);
        var expectedTrajectory = loader.getTrajectory();
        var maxDistance = expectedTrajectory.get(expectedTrajectory.size() - 1).getDistance();
        var pt0 = expectedTrajectory.get(0);
        var pt1 = expectedTrajectory.get(1);
        var step = Quantities.getQuantity(pt1.getDistance().getValue().doubleValue() - pt0.getDistance().getValue().doubleValue(), pt0.getDistance().getUnit());

        var tc = new TrajectoryCalculator();
        var sa = tc.calculateSightAngle(loader.getProjectile(), loader.getWeapon(), loader.getAtmosphere());

        var shot = new ShotParameters(maxDistance, step, sa, loader.getShotAngle(), loader.getCantAngle(), loader.getBarrelAzimuth(), loader.getLatitude());
        var trajectory = tc.calculate(loader.getProjectile(), loader.getWeapon(), loader.getAtmosphere(), shot, loader.getWind());

        var expectedLength = expectedTrajectory.size();
        assertThat(trajectory).hasSize(expectedLength);

        for (int i = 0; i < trajectory.length; i++) {
            var point = trajectory[i];
            var expectedPoint = expectedTrajectory.get(i);
            assertThat(point).isNotNull();
            assertThat(expectedPoint).isNotNull();
            assertThatTrajectoryPointsMatch(point, expectedPoint, velocityAccuracyInPercent, dropAccuracyInMOA, windageAccuracyInMOA, energyAccuracy, timeAccuracy, ignoreMach);
        }
    }
}
