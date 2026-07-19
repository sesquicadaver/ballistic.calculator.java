package gehtsoft.ballisticcalculator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gehtsoft.ballisticcalculator.data.*;
import gehtsoft.ballisticcalculator.drag.*;
import gehtsoft.ballisticcalculator.tools.TrajectoryLoader;
import gehtsoft.ballisticcalculator.units.BCUnits;
import gehtsoft.ballisticcalculator.units.UnitUtils;
import systems.uom.unicode.CLDR;
import tech.units.indriya.quantity.Quantities;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A per-bullet effective ballistic-coefficient-vs-Mach curve (bc_*.txt), synthesized into a custom
 * drag table via {@link DrgDragTableFactory}, reproduces the Hornady 4DOF reference (b1_*.txt) far
 * better than the published box BC.
 *
 * (The aerodynamic-jump tests from the C# suite are not ported: the crosswind-jump term is absent
 * by design in this engine, so the b1_eldx_wind case asserts velocity + windage only.)
 */
class TestB1Drag {
    // trajectory resource, bc-curve resource, velocity tol (relative), drop tol (MOA),
    // windage tol (MOA), assert drop?, minimum published-BC drop error (MOA) the control run must exceed.
    @ParameterizedTest
    @CsvSource(value = {
        "b1_eldx,       bc_eldx, 0.003, 0.10, 0.05, true,  0.5",
        "b1_eldm,       bc_eldm, 0.003, 0.10, 0.05, true,  1.0",
        "b1_eldm_10kft, bc_eldm, 0.010, 0.10, 0.05, true,  0.5",
        "b1_bthp,       bc_bthp, 0.030, 1.50, 0.05, true,  3.0",
        "b1_bthp_hot,   bc_bthp, 0.030, 1.20, 0.05, true,  3.0",
        "b1_bthp_3200,  bc_bthp, 0.035, 1.20, 0.05, true,  3.0",
        "b1_bthp_7kft,  bc_bthp, 0.030, 1.50, 0.05, true,  3.0",
        "b1_eldx_wind,  bc_eldx, 0.003, 0.0,  0.10, false, 0.0",
    })
    void synthesizedCurveMatches4Dof(String trajRes, String bcRes, double velTol, double dropTolMOA,
            double windTolMOA, boolean assertDrop, double minPublishedDropMOA) throws IOException {
        BcCurve curve = loadBcCurve(bcRes);

        TrajectoryLoader template = new TrajectoryLoader();
        template.load(trajRes + ".txt", null);
        var expected = template.getTrajectory();
        var maxDistance = expected.get(expected.size() - 1).getDistance();
        var step = Quantities.getQuantity(50, CLDR.YARD);
        var cal = new TrajectoryCalculator();

        Projectile published = template.getProjectile();
        DrgFile table = DrgDragTableFactory.build(published, curve.baseTable, curve.knots);

        // synthesized-drg run: swap the published BC for the custom (GC) curve
        Projectile synth = new Projectile(published.getBulletWeight(), published.getMuzzleVelocity(),
            new BallisticCoefficient(1.0, BallisticCoefficientValueType.COEFFICIENT, table),
            published.getBulletDiameter(), published.getBulletLength());

        var sa = cal.calculateSightAngle(synth, template.getWeapon(), template.getAtmosphere());
        var shot = new ShotParameters(maxDistance, step, sa, template.getShotAngle(), null, null);
        var traj = cal.calculate(synth, template.getWeapon(), template.getAtmosphere(), shot, template.getWind());

        assertThat(traj).hasSize(expected.size());
        for (int i = 0; i < traj.length; i++) {
            var p = traj[i];
            var r = expected.get(i);
            double yd = UnitUtils.in(r.getDistance(), CLDR.YARD);
            double refV = r.getVelocity().getValue().doubleValue();
            assertThat(UnitUtils.in(p.getVelocity(), r.getVelocity().getUnit()))
                .as("vel@%.0f", yd).isEqualTo(refV, within(Math.abs(refV) * velTol));
            if (yd <= 0)
                continue;
            if (assertDrop)
                assertThat(UnitUtils.in(p.getDrop(), CLDR.INCH))
                    .as("drop@%.0f", yd).isEqualTo(UnitUtils.in(r.getDrop(), CLDR.INCH), within(inchTol(dropTolMOA, yd)));
            assertThat(UnitUtils.in(p.getWindage(), CLDR.INCH))
                .as("wind@%.0f", yd).isEqualTo(UnitUtils.in(r.getWindage(), CLDR.INCH), within(inchTol(windTolMOA, yd)));
        }

        // control: the published-BC (plain 3DOF) run must be materially worse than the synthesized
        // curve — proves b1 closes a real drag-data gap, not a trivial pass.
        if (minPublishedDropMOA > 0) {
            var csa = cal.calculateSightAngle(published, template.getWeapon(), template.getAtmosphere());
            var cshot = new ShotParameters(maxDistance, step, csa, template.getShotAngle(), null, null);
            var ctrl = cal.calculate(published, template.getWeapon(), template.getAtmosphere(), cshot, template.getWind());
            double maxCtrlDropMOA = 0;
            for (int i = 0; i < ctrl.length; i++) {
                double yd = UnitUtils.in(expected.get(i).getDistance(), CLDR.YARD);
                if (yd <= 0)
                    continue;
                double err = Math.abs(UnitUtils.in(ctrl[i].getDrop(), CLDR.INCH) - UnitUtils.in(expected.get(i).getDrop(), CLDR.INCH));
                maxCtrlDropMOA = Math.max(maxCtrlDropMOA, moaOf(err, yd));
            }
            assertThat(maxCtrlDropMOA)
                .as("the published-BC 3DOF run should be materially worse than the synthesized curve")
                .isGreaterThan(minPublishedDropMOA);
        }
    }

    private static double inchTol(double moa, double yards) {
        double t = UnitUtils.in(Quantities.getQuantity(moa, BCUnits.MOA), BCUnits.INCHES_PER_100YARDS) * yards / 100.0;
        return t < 0.001 ? 0.001 : t;
    }

    private static double moaOf(double inches, double yards) {
        return UnitUtils.in(Quantities.getQuantity(inches / (yards / 100.0), BCUnits.INCHES_PER_100YARDS), BCUnits.MOA);
    }

    private static class BcCurve {
        DragTableId baseTable;
        List<BcAtMach> knots;
    }

    // bc_*.txt: comment lines (#) ignored, first real line = base DragTableId, remaining lines "mach;bc".
    private static BcCurve loadBcCurve(String name) throws IOException {
        ClassLoader classLoader = TestB1Drag.class.getClassLoader();
        File file = new File(classLoader.getResource(name + ".txt").getFile());
        BcCurve result = new BcCurve();
        result.knots = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#')
                    continue;
                if (result.baseTable == null) {
                    result.baseTable = DragTableId.valueOf(line);
                    continue;
                }
                String[] parts = line.split(";");
                result.knots.add(new BcAtMach(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
            }
        }
        return result;
    }
}
