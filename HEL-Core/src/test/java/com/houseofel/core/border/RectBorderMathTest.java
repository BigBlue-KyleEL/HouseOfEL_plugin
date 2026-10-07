package com.houseofel.core.border;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RectBorderMathTest {

    // Live values: X -15000..15000 (short, 30000), Z -20000..30000 (long, 50000).
    private final RectBorderMath live = new RectBorderMath(-15000, 15000, -20000, 30000, 16);

    private static void assertCenter(double x, double z, RectBorderMath.Center c) {
        assertEquals(x, c.x(), 1e-9, "center x");
        assertEquals(z, c.z(), 1e-9, "center z");
    }

    @Test void sideIsShortDimension() {
        assertEquals(30000, live.side());
    }

    @Test void middleOfBoxFollowsPlayerOnLongAxisSnappedToStep() {
        assertCenter(0, 5000, live.centerFor(1234, 4999));  // grid anchored on -5000, step 16
        assertCenter(0, 8, live.centerFor(-7000, 3));
        assertCenter(0, 4008, live.centerFor(14999, 4007));
    }

    @Test void shortAxisAlwaysAtMidpointSoXEdgesStayOnBox() {
        for (double x : new double[]{-20000, -14999, 0, 14999, 20000}) {
            var c = live.centerFor(x, 0);
            assertEquals(0, c.x());
            assertEquals(-15000, c.x() - live.side() / 2);
            assertEquals(15000, c.x() + live.side() / 2);
        }
    }

    @Test void nearEachLongAxisLimit() {
        assertCenter(0, -4984, live.centerFor(0, -4990));
        assertCenter(0, -5000, live.centerFor(0, -5007));
        assertCenter(0, 14984, live.centerFor(0, 14990));
        assertCenter(0, 15000, live.centerFor(0, 15006));
    }

    @Test void exactlyAtLimitsNearEdgeLandsOnBox() {
        var low = live.centerFor(0, -5000);
        assertCenter(0, -5000, low);
        assertEquals(-20000, low.z() - live.side() / 2);
        var high = live.centerFor(0, 15000);
        assertCenter(0, 15000, high);
        assertEquals(30000, high.z() + live.side() / 2);
        // Player standing on the box edge itself.
        assertCenter(0, -5000, live.centerFor(0, -20000));
        assertCenter(0, 15000, live.centerFor(0, 30000));
    }

    @Test void outsideBoxClampsToLimits() {
        assertCenter(0, -5000, live.centerFor(99999, -1_000_000));
        assertCenter(0, 15000, live.centerFor(-99999, 1_000_000));
    }

    @Test void squareBoxIsFixedOnBothAxes() {
        var sq = new RectBorderMath(-1000, 3000, 500, 4500, 16);
        assertEquals(4000, sq.side());
        assertCenter(1000, 2500, sq.centerFor(-50000, 90000));
        assertCenter(1000, 2500, sq.centerFor(2999, 501));
    }

    @Test void xLongBoxFollowsXAndFixesZ() {
        var wide = new RectBorderMath(-20000, 30000, -15000, 15000, 16);
        assertEquals(30000, wide.side());
        assertCenter(5000, 0, wide.centerFor(4999, 12345));
        assertCenter(-5000, 0, wide.centerFor(-19000, -14000));
        assertCenter(15000, 0, wide.centerFor(40000, 99999));
    }

    @Test void stepGridNeverPullsCenterInsideTheLimit() {
        // A limit that is off the step grid from both sides: center must still hit it exactly.
        var odd = new RectBorderMath(0, 100, 0, 1007, 16);
        assertCenter(50, 50, odd.centerFor(0, 50));
        assertCenter(50, 957, odd.centerFor(0, 957));
        assertCenter(50, 946, odd.centerFor(0, 950));
    }

    @Test void updateStepBelowOneIsTreatedAsOne() {
        var fine = new RectBorderMath(-15000, 15000, -20000, 30000, 0);
        assertCenter(0, 1235, fine.centerFor(0, 1234.6));
    }

    @Test void validation() {
        assertNull(RectBorderMath.validate(-15000, 15000, -20000, 30000));
        assertNotNull(RectBorderMath.validate(10, 10, 0, 5));
        assertNotNull(RectBorderMath.validate(20, 10, 0, 5));
        assertNotNull(RectBorderMath.validate(0, 5, 7, -7));
        assertNotNull(RectBorderMath.validate(-4e7, 4e7, -4e7, 4e7));
        assertNull(RectBorderMath.validate(-4e7, 4e7, 0, 100)); // only the short side matters
    }
}
