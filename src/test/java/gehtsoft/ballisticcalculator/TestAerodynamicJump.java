package gehtsoft.ballisticcalculator;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import gehtsoft.ballisticcalculator.data.*;
import gehtsoft.ballisticcalculator.drag.*;
import gehtsoft.ballisticcalculator.tools.TrajectoryLoader;
import gehtsoft.ballisticcalculator.units.BCUnits;
import gehtsoft.ballisticcalculator.units.UnitUtils;
import systems.uom.unicode.CLDR;
import tech.units.indriya.quantity.Quantities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Aerodynamic (crosswind) jump — Litz Applied Ballistics Eq 5.4. A horizontal crosswind gives a
 * spin-stabilized bullet a vertical come-up imparted at the muzzle, then constant in angle at all
 * ranges. Runs on the synthesized (b1) drag curve so the drag baseline already matches Hornady 4DOF;
 * the only remaining drop gap is the jump.
 *
 * (The C# AerodynamicJump_UsesMuzzleWindZone test is not ported: it needs multi-zone wind arrays,
 * which this engine's single-Wind API does not support.)
 */
class TestAerodynamicJump {

    // Our original hypothesis for the b1_eldx_wind drop gap was that 4DOF's "drop" carries a vertical
    // crosswind jump we didn't model. This proves modeling it closes the gap.
    @Test
    void closesHornadyWindDropGap() throws IOException {
        var reference = loadTrajectory("b1_eldx_wind_twist");          // Hornady 4DOF drop (with jump)
        var withJump = runSynth("b1_eldx_wind_twist", null, null);     // twist+dims => jump modelled
        var noJump = runSynth("b1_eldx_wind", null, null);             // no dims => jump absent

        double maxErrWith = 0, maxErrNo = 0, jumpMin = Double.MAX_VALUE, jumpMax = 0;
        for (int i = 0; i < withJump.length; i++) {
            double yd = UnitUtils.in(reference.get(i).getDistance(), CLDR.YARD);
            if (yd <= 0)
                continue;
            double refDrop = UnitUtils.in(reference.get(i).getDrop(), CLDR.INCH);
            maxErrWith = Math.max(maxErrWith, moaOf(Math.abs(UnitUtils.in(withJump[i].getDrop(), CLDR.INCH) - refDrop), yd));
            maxErrNo = Math.max(maxErrNo, moaOf(Math.abs(UnitUtils.in(noJump[i].getDrop(), CLDR.INCH) - refDrop), yd));
            double jumpMoa = moaOf(UnitUtils.in(withJump[i].getDrop(), CLDR.INCH) - UnitUtils.in(noJump[i].getDrop(), CLDR.INCH), yd);
            jumpMin = Math.min(jumpMin, jumpMoa);
            jumpMax = Math.max(jumpMax, jumpMoa);
        }

        // The unmodeled jump is a real, large drop gap vs 4DOF...
        assertThat(maxErrNo).as("unmodeled crosswind jump is a real ~0.8 MOA drop gap").isGreaterThan(0.6);
        // ...that aerodynamic jump largely closes.
        assertThat(maxErrWith).as("aerodynamic jump closes most of the drop gap").isLessThan(0.25);
        assertThat(maxErrWith).as("with-jump is materially better than without").isLessThan(maxErrNo * 0.4);

        // The jump is a constant angle at all ranges and matches Litz Eq 5.4 for this bullet/crosswind.
        double sg = millerSg(220, 0.308, 7, 1.630, 2600, 29.92, 59);
        double expectJumpMoa = (0.01 * sg - 0.0024 * (1.630 / 0.308) + 0.032) * 20.0;   // Eq 5.4 x 20 mph
        assertThat(jumpMin).as("jump is range-independent (constant angle)").isEqualTo(jumpMax, within(0.005));
        assertThat(jumpMax).as("jump magnitude matches Litz Eq 5.4").isEqualTo(expectJumpMoa, within(0.02));
    }

    // Linearity in crosswind, sign by wind side and twist direction, zero for a pure head/tailwind.
    @Test
    void structuralProperties() throws IOException {
        double right20 = jumpMoaAt1000(20, 90, null);                          // wind from right, template's right twist
        double right10 = jumpMoaAt1000(10, 90, null);
        double left20 = jumpMoaAt1000(20, 270, null);                          // wind from left
        double leftTwist20 = jumpMoaAt1000(20, 90, TwistDirection.LEFT);       // flip twist under test
        double head20 = jumpMoaAt1000(20, 0, null);                            // pure tail/headwind axis

        assertThat(right20).as("right twist + wind from right => jump up (Drop less negative)").isGreaterThan(0);
        assertThat(right10).as("jump is linear in crosswind speed").isEqualTo(right20 / 2, within(0.01));
        assertThat(left20).as("wind from the left flips the vertical sign").isEqualTo(-right20, within(0.01));
        assertThat(leftTwist20).as("left-hand twist flips the vertical sign").isEqualTo(-right20, within(0.01));
        assertThat(head20).as("a pure head/tailwind has no crosswind component => no jump").isEqualTo(0, within(1e-6));
    }

    private double jumpMoaAt1000(double windMph, double dirDeg, TwistDirection twist) throws IOException {
        Wind w = new Wind(Quantities.getQuantity(windMph, CLDR.MILE_PER_HOUR), Quantities.getQuantity(dirDeg, CLDR.DEGREE));
        var on = runSynth("b1_eldx_wind_twist", w, twist);   // jump on (has dims/twist)
        var off = runSynth("b1_eldx_wind", w, null);         // jump off (no dims)
        int i = indexAt(on, 1000);
        return moaOf(UnitUtils.in(on[i].getDrop(), CLDR.INCH) - UnitUtils.in(off[i].getDrop(), CLDR.INCH), 1000);
    }

    // Run a template on the synthesized (GC) b1 drag curve. `wind` overrides the scenario wind
    // (null => the template's own); `twistOverride` flips only the twist direction.
    private TrajectoryPoint[] runSynth(String templateName, Wind wind, TwistDirection twistOverride) throws IOException {
        TrajectoryLoader loader = new TrajectoryLoader();
        loader.load(templateName + ".txt", null);
        var expected = loader.getTrajectory();
        var maxDistance = expected.get(expected.size() - 1).getDistance();
        var step = Quantities.getQuantity(50, CLDR.YARD);
        BcCurve curve = loadBcCurve("bc_eldx");
        var cal = new TrajectoryCalculator();

        Projectile pub = loader.getProjectile();
        DrgFile table = DrgDragTableFactory.build(pub, curve.baseTable, curve.knots);
        Projectile synth = new Projectile(pub.getBulletWeight(), pub.getMuzzleVelocity(),
            new BallisticCoefficient(1.0, BallisticCoefficientValueType.COEFFICIENT, table),
            pub.getBulletDiameter(), pub.getBulletLength());

        Weapon rifle = loader.getWeapon();
        if (twistOverride != null)
            rifle = new Weapon(rifle.getZeroingInformation(),
                new Rifling(rifle.getRifling().getRiflingStep(), twistOverride));

        Wind useWind = wind != null ? wind : loader.getWind();
        var sa = cal.calculateSightAngle(synth, rifle, loader.getAtmosphere());
        var shot = new ShotParameters(maxDistance, step, sa, loader.getShotAngle(), null, null);
        return cal.calculate(synth, rifle, loader.getAtmosphere(), shot, useWind);
    }

    private static int indexAt(TrajectoryPoint[] traj, double yd) {
        for (int i = 0; i < traj.length; i++)
            if (Math.abs(UnitUtils.in(traj[i].getDistance(), CLDR.YARD) - yd) < 1)
                return i;
        throw new IllegalStateException("no trajectory point at " + yd + " yd");
    }

    private static double moaOf(double inches, double yards) {
        return UnitUtils.in(Quantities.getQuantity(inches / (yards / 100.0), BCUnits.INCHES_PER_100YARDS), BCUnits.MOA);
    }

    // Miller stability with Litz's velocity + temp/pressure corrections (mirrors the engine's
    // calculateStabilityCoefficient) — used to predict Eq 5.4.
    private static double millerSg(double grains, double diaIn, double twistIn, double lenIn, double mvFps, double presInHg, double tempF) {
        double t = twistIn / diaIn, l = lenIn / diaIn;
        double sd = 30 * grains / (t * t * diaIn * diaIn * diaIn * l * (1 + l * l));
        double fv = Math.pow(mvFps / 2800.0, 1.0 / 3.0);
        double ftp = ((tempF + 460) / (59 + 460)) * (29.92 / presInHg);
        return sd * fv * ftp;
    }

    private static List<TrajectoryPoint> loadTrajectory(String name) throws IOException {
        TrajectoryLoader loader = new TrajectoryLoader();
        loader.load(name + ".txt", null);
        return loader.getTrajectory();
    }

    private static class BcCurve {
        DragTableId baseTable;
        List<BcAtMach> knots;
    }

    // bc_*.txt: comment lines (#) ignored, first real line = base DragTableId, remaining "mach;bc".
    private static BcCurve loadBcCurve(String name) throws IOException {
        ClassLoader classLoader = TestAerodynamicJump.class.getClassLoader();
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
