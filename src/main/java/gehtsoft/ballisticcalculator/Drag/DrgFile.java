package gehtsoft.ballisticcalculator.drag;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.measure.*;
import javax.measure.quantity.*;

import gehtsoft.ballisticcalculator.units.UnitUtils;
import si.uom.SI;

@java.lang.SuppressWarnings("java:S3252") //false positive for java's own SI.*
public class DrgFile extends AdvancedDragTable {
    private String mName;
    private Quantity<Length> mBulletDiameter;
    private Quantity<Mass> mBulletWeight;

    /** Returns the name */
    public String getName() {
        return mName;
    }

    /** Returns the bullet diameter */
    public Quantity<Length> getBulletDiameter() {
        return mBulletDiameter;
    }

    /** Returns the bullet weight */
    public Quantity<Mass> getBulletWeight() {
        return mBulletWeight;
    }

    DrgFile(String name, Quantity<Length> bulletDiameter, 
                    Quantity<Mass> bulletWeight, 
                    DragTableDataPoint[] points)
    {
        super(DragTableId.GC, points);
        mName = name;
        mBulletDiameter = bulletDiameter;
        mBulletWeight = bulletWeight;
    }

    /**
     * Reads a drag table from a file
     * @param source file reader
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public static DrgFile read(BufferedReader source)
        throws IOException, IllegalArgumentException
    {
        DrgFileReader reader = new DrgFileReader(source);
        return reader.read();
    }

    /**
     * Saves the drag table to a file (ASCII encoding). Symmetric with {@link #read(BufferedReader)}.
     * @param fileName the target file name
     * @throws IOException
     */
    public void save(String fileName) throws IOException {
        try (OutputStream stream = new FileOutputStream(fileName)) {
            save(stream);
        }
    }

    /**
     * Saves the drag table to a stream (ASCII encoding). The stream is left open.
     *
     * Format: a header {@code CFM,<name>,<weightKg>,<diameterM>,0,0} followed by one
     * {@code <Cd> <Mach>} line per point, all round-trip formatted in invariant culture.
     * @param stream the target stream
     * @throws IOException
     */
    public void save(OutputStream stream) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.US_ASCII));
        String name = (mName == null || mName.isEmpty() ? "custom" : mName).replace(',', ' ');
        double weightKg = mBulletWeight == null ? 0 : UnitUtils.in(mBulletWeight, SI.KILOGRAM);
        double diameterM = mBulletDiameter == null ? 0 : UnitUtils.in(mBulletDiameter, SI.METRE);

        writer.write(String.format(Locale.ROOT, "CFM,%s,%s,%s,0,0%n",
            name, Double.toString(weightKg), Double.toString(diameterM)));

        for (int i = 0; i < length(); i++) {
            IDragTableNode node = get(i);
            writer.write(String.format(Locale.ROOT, "%s %s%n",
                Double.toString(node.getDragCoefficient()), Double.toString(node.getMach())));
        }
        writer.flush();
    }
}
