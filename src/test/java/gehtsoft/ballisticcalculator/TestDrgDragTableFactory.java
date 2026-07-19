package gehtsoft.ballisticcalculator;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import gehtsoft.ballisticcalculator.data.*;
import gehtsoft.ballisticcalculator.drag.*;
import gehtsoft.ballisticcalculator.units.BCUnits;
import gehtsoft.ballisticcalculator.units.UnitUtils;
import si.uom.SI;
import systems.uom.common.Imperial;
import systems.uom.unicode.CLDR;
import tech.units.indriya.quantity.Quantities;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class TestDrgDragTableFactory {

    // Only the weight/diameter are read by the factory; the BC is irrelevant here.
    private Projectile sampleAmmunition() {
        return new Projectile(
            Quantities.getQuantity(168, BCUnits.GRAIN),
            Quantities.getQuantity(2700, BCUnits.FEET_PER_SECOND),
            new BallisticCoefficient(1.0, DragTableId.G7),
            Quantities.getQuantity(0.308, Imperial.INCH),
            null);
    }

    private DrgFile reopen(DrgFile table) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        table.save(out);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.US_ASCII))) {
            return DrgFile.read(reader);
        }
    }

    // A flat BC(M) = k must produce Cd_custom(M) = Cd_base(M) / k at every base node.
    @Test
    void flatBcScalesBaseCurve() {
        final double k = 0.5;
        IDragTable baseCurve = StandardDragTableFactory.getInstance().getG7();
        DrgFile table = DrgDragTableFactory.build(sampleAmmunition(), DragTableId.G7,
            Arrays.asList(new BcAtMach(0.0, k), new BcAtMach(5.0, k)));

        assertThat(table.length()).isEqualTo(baseCurve.length());
        for (int i = 0; i < baseCurve.length(); i++) {
            assertThat(table.get(i).getMach()).isEqualTo(baseCurve.get(i).getMach());
            assertThat(table.get(i).getDragCoefficient())
                .isEqualTo(baseCurve.get(i).getDragCoefficient() / k, within(1e-12));
        }
    }

    // Running the synthesized (flat-BC) table with BC=1.0 must reproduce the standard table run
    // with BC=k, to well within measurement noise.
    @Test
    void flatBcReproducesStandardTrajectory() {
        final double k = 0.475;
        var cal = new TrajectoryCalculator();
        var rifle = new Weapon(new ZeroingInformation(
            Quantities.getQuantity(1.5, CLDR.INCH), Quantities.getQuantity(100, CLDR.YARD)), null);
        var atmo = new Atmosphere();

        var ammoStd = new Projectile(
            Quantities.getQuantity(168, BCUnits.GRAIN),
            Quantities.getQuantity(2700, BCUnits.FEET_PER_SECOND),
            new BallisticCoefficient(k, DragTableId.G7));

        DrgFile table = DrgDragTableFactory.build(sampleAmmunition(), DragTableId.G7,
            Arrays.asList(new BcAtMach(0.0, k), new BcAtMach(5.0, k)));

        var ammoGc = new Projectile(
            Quantities.getQuantity(168, BCUnits.GRAIN),
            Quantities.getQuantity(2700, BCUnits.FEET_PER_SECOND),
            new BallisticCoefficient(1.0, BallisticCoefficientValueType.COEFFICIENT, table));

        var step = Quantities.getQuantity(100, CLDR.YARD);
        var maxDistance = Quantities.getQuantity(1000, CLDR.YARD);

        var saStd = cal.calculateSightAngle(ammoStd, rifle, atmo);
        var trajStd = cal.calculate(ammoStd, rifle, atmo,
            new ShotParameters(maxDistance, step, saStd), null);

        var saGc = cal.calculateSightAngle(ammoGc, rifle, atmo);
        var trajGc = cal.calculate(ammoGc, rifle, atmo,
            new ShotParameters(maxDistance, step, saGc), null);

        assertThat(trajGc.length).isEqualTo(trajStd.length);
        for (int i = 0; i < trajStd.length; i++) {
            assertThat(UnitUtils.in(trajGc[i].getVelocity(), BCUnits.FEET_PER_SECOND))
                .isEqualTo(UnitUtils.in(trajStd[i].getVelocity(), BCUnits.FEET_PER_SECOND), within(0.05));
            assertThat(UnitUtils.in(trajGc[i].getDrop(), CLDR.INCH))
                .isEqualTo(UnitUtils.in(trajStd[i].getDrop(), CLDR.INCH), within(0.02));
        }
    }

    // Synthesize -> Save -> Read must round-trip the points and ammo metadata.
    @Test
    void saveRoundTrips() throws IOException {
        var ammo = sampleAmmunition();
        List<BcAtMach> knots = Arrays.asList(new BcAtMach(0.0, 0.30), new BcAtMach(1.0, 0.28), new BcAtMach(3.0, 0.33));
        DrgFile table = DrgDragTableFactory.build(ammo, DragTableId.G1, knots);

        DrgFile reopened = reopen(table);

        assertThat(reopened.length()).isEqualTo(table.length());
        for (int i = 0; i < table.length(); i++) {
            assertThat(reopened.get(i).getMach()).isEqualTo(table.get(i).getMach(), within(1e-9));
            assertThat(reopened.get(i).getDragCoefficient()).isEqualTo(table.get(i).getDragCoefficient(), within(1e-9));
        }
        assertThat(UnitUtils.in(reopened.getBulletWeight(), SI.KILOGRAM))
            .isEqualTo(UnitUtils.in(ammo.getBulletWeight(), SI.KILOGRAM), within(1e-9));
        assertThat(UnitUtils.in(reopened.getBulletDiameter(), SI.METRE))
            .isEqualTo(UnitUtils.in(ammo.getBulletDiameter(), SI.METRE), within(1e-9));
    }

    // BC is interpolated linearly between knots and held flat beyond them.
    @Test
    void bcInterpolatesBetweenKnots() {
        DrgFile table = DrgDragTableFactory.build(sampleAmmunition(), DragTableId.G1,
            Arrays.asList(new BcAtMach(1.0, 0.2), new BcAtMach(2.0, 0.4)));
        IDragTable baseCurve = StandardDragTableFactory.getInstance().getG1();

        for (int i = 0; i < baseCurve.length(); i++) {
            double m = baseCurve.get(i).getMach();
            double expectedBc = m <= 1.0 ? 0.2 : (m >= 2.0 ? 0.4 : 0.2 + (m - 1.0) * 0.2);
            assertThat(table.get(i).getDragCoefficient())
                .isEqualTo(baseCurve.get(i).getDragCoefficient() / expectedBc, within(1e-12));
        }
    }

    // A real 3rd-party .drg (BRL header, radar data) must survive Read -> Save -> Read.
    @Test
    void realDrgFileSaveRoundTrips() throws IOException {
        DrgFile original = DrgFileLoader.loadDragTable("sierra_168_brl.drg");
        DrgFile reopened = reopen(original);

        assertThat(reopened.length()).isEqualTo(original.length());
        for (int i = 0; i < original.length(); i++) {
            assertThat(reopened.get(i).getMach()).isEqualTo(original.get(i).getMach(), within(1e-9));
            assertThat(reopened.get(i).getDragCoefficient()).isEqualTo(original.get(i).getDragCoefficient(), within(1e-9));
        }
        assertThat(reopened.getName()).isEqualTo(original.getName());
        assertThat(UnitUtils.in(reopened.getBulletWeight(), SI.KILOGRAM))
            .isEqualTo(UnitUtils.in(original.getBulletWeight(), SI.KILOGRAM), within(1e-9));
        assertThat(UnitUtils.in(reopened.getBulletDiameter(), SI.METRE))
            .isEqualTo(UnitUtils.in(original.getBulletDiameter(), SI.METRE), within(1e-9));
    }

    @Test
    void buildGuardsBadInput() {
        var ammo = sampleAmmunition();
        List<BcAtMach> ok = Collections.singletonList(new BcAtMach(0.0, 0.3));

        assertThatThrownBy(() -> DrgDragTableFactory.build(null, DragTableId.G7, ok))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrgDragTableFactory.build(ammo, DragTableId.G7, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrgDragTableFactory.build(ammo, DragTableId.GC, ok))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrgDragTableFactory.build(ammo, DragTableId.G7, Collections.emptyList()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrgDragTableFactory.build(ammo, DragTableId.G7,
                Collections.singletonList(new BcAtMach(1.0, 0.0))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
