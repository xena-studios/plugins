package co.xenastudios.xplots.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class LayoutTest {

    static final SignPlacement MIDDLE = new SignPlacement(SignPlacement.Position.MIDDLE, SignPlacement.Rotation.STRAIGHT);

    @Test
    void splitsAndCentresTheGrid() {
        // 27 wide: 4 plots of 5 with 2-block paths take 26, leaving 1, so the grid starts 0 in.
        Layout layout = Layout.of(Box.of(0, 64, 0, 26, 74, 12), 5, 5, 2);
        assertEquals(4, layout.cols());
        assertEquals(2, layout.rows());
        assertEquals(0, layout.originX());
        // 13 deep: 2 rows take 12, so the 1 left over is split: 0 at the start.
        assertEquals(0, layout.originZ());
        assertEquals(8, layout.count());

        Layout centred = Layout.of(Box.of(0, 64, 0, 29, 74, 4), 5, 5, 2);
        // 30 wide: 4 plots take 26, so 2 blocks of margin each side.
        assertEquals(2, centred.originX());
    }

    @Test
    void numberAtMatchesEverySpotAndNothingElse() {
        Layout layout = Layout.of(Box.of(-20, 60, 100, 40, 70, 150), 5, 7, 3);
        Set<String> inPlots = new HashSet<>();
        for (Layout.Spot spot : layout.spots(MIDDLE)) {
            Box box = spot.box();
            assertEquals(5, box.width());
            assertEquals(7, box.depth());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    assertEquals(spot.number(), layout.numberAt(x, z));
                    inPlots.add(x + "," + z);
                }
            }
        }
        Box area = layout.area();
        for (int x = area.minX() - 2; x <= area.maxX() + 2; x++) {
            for (int z = area.minZ() - 2; z <= area.maxZ() + 2; z++) {
                if (!inPlots.contains(x + "," + z)) {
                    assertEquals(0, layout.numberAt(x, z), "path at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void signsFaceTheirPathAndNeverShareABlock() {
        for (int path : List.of(0, 1, 2, 3)) {
            Layout layout = Layout.of(Box.of(0, 64, 0, 40, 70, 40), 5, 5, path);
            Set<String> signs = new HashSet<>();
            for (Layout.Spot spot : layout.spots(MIDDLE)) {
                assertTrue(signs.add(spot.signX() + "," + spot.signY() + "," + spot.signZ()), "two signs on one block, path " + path);
                assertEquals(65, spot.signY());
                boolean inside = spot.box().containsColumn(spot.signX(), spot.signZ());
                assertEquals(path < 2, inside, "sign inside the plot only when the path is narrow");
                if (!inside) {
                    // On the path just outside the plot's edge.
                    int edge = spot.facing() == BlockFace.SOUTH ? spot.box().maxZ() + 1 : spot.box().minZ() - 1;
                    assertEquals(edge, spot.signZ());
                    assertEquals(0, layout.numberAt(spot.signX(), spot.signZ()));
                }
            }
        }
    }

    @Test
    void rowsFaceEachOtherInPairs() {
        Layout layout = Layout.of(Box.of(0, 64, 0, 4, 70, 4 * 5 + 3 * 2 - 1), 5, 5, 2);
        assertEquals(4, layout.rows());
        List<Layout.Spot> spots = layout.spots(MIDDLE);
        assertEquals(BlockFace.SOUTH, spots.get(0).facing());
        assertEquals(BlockFace.NORTH, spots.get(1).facing());
        assertEquals(BlockFace.SOUTH, spots.get(2).facing());
        assertEquals(BlockFace.NORTH, spots.get(3).facing());

        // An odd row out at the end faces back toward the others.
        Layout three = Layout.of(Box.of(0, 64, 0, 4, 70, 3 * 5 + 2 * 2 - 1), 5, 5, 2);
        assertEquals(BlockFace.NORTH, three.spots(MIDDLE).get(2).facing());
        Layout one = Layout.of(Box.of(0, 64, 0, 4, 70, 4), 5, 5, 2);
        assertEquals(BlockFace.SOUTH, one.spots(MIDDLE).get(0).facing());
    }

    @Test
    void nothingFits() {
        assertNull(Layout.of(Box.of(0, 64, 0, 3, 70, 10), 5, 5, 1));
    }

    @Test
    void fieldLocatesPlotsPathsAndHeights() {
        Layout layout = Layout.of(Box.of(0, 64, 0, 11, 70, 4), 5, 5, 2);
        Field field = new Field(1, "box", "world", layout);
        for (Layout.Spot spot : layout.spots(MIDDLE)) {
            field.add(new Plot(field, spot.number(), spot.box(), spot.signX(), spot.signY(), spot.signZ(), spot.facing()));
        }
        assertEquals("box-1", field.locate(0, 64, 0).id());
        assertEquals("box-2", field.locate(11, 70, 4).id());
        assertEquals(Field.PATH, field.locate(5, 65, 2), "the path between them");
        assertEquals(Field.PATH, field.locate(0, 71, 0), "above the plot is protected, not buildable");
        assertEquals(Field.PATH, field.locate(0, 63, 0), "and below");
        assertNull(field.locate(12, 65, 0), "outside the field");

        Plot custom = new Plot(field, field.nextNumber(), Box.of(20, 64, 0, 22, 66, 2), 21, 65, 3, BlockFace.SOUTH);
        field.add(custom);
        assertEquals("box-3", custom.id());
        assertEquals(custom, field.locate(21, 65, 1));
        assertNull(field.locate(16, 65, 1), "between the grid and a plot by itself is nobody's");
    }

    @Test
    void cornerSignsStandAtTheLeftOrRightCornerAsSeenFromTheWalkway() {
        Layout layout = Layout.of(Box.of(0, 64, 0, 18, 70, 18), 7, 7, 5);
        SignPlacement left = new SignPlacement(SignPlacement.Position.LEFT_CORNER, SignPlacement.Rotation.STRAIGHT);
        SignPlacement right = new SignPlacement(SignPlacement.Position.RIGHT_CORNER, SignPlacement.Rotation.DIAGONAL);
        // Row 0 faces south: looking north at it from the walkway, its left is west (min x).
        Layout.Spot south = layout.spots(left).get(0);
        assertEquals(south.box().minX(), south.signX());
        assertEquals(south.box().maxZ() + 1, south.signZ());
        assertEquals(BlockFace.SOUTH, south.facing());
        Layout.Spot southRight = layout.spots(right).get(0);
        assertEquals(southRight.box().maxX(), southRight.signX());
        assertEquals(BlockFace.SOUTH_EAST, southRight.facing(), "turned out from its corner");
        // Row 1 faces north: looking south at it, its left is east (max x).
        Layout.Spot north = layout.spots(left).get(2);
        assertEquals(north.box().maxX(), north.signX());
        assertEquals(north.box().minZ() - 1, north.signZ());
        Layout.Spot northRight = layout.spots(right).get(2);
        assertEquals(northRight.box().minX(), northRight.signX());
        assertEquals(BlockFace.NORTH_WEST, northRight.facing());
    }

    @Test
    void everyPlacementKeepsSignsOnTheirOwnBlocks() {
        for (SignPlacement.Position position : SignPlacement.Position.values()) {
            for (SignPlacement.Rotation rotation : SignPlacement.Rotation.values()) {
                for (int path : List.of(0, 1, 2, 3, 5)) {
                    Layout layout = Layout.of(Box.of(0, 64, 0, 60, 70, 60), 7, 7, path);
                    Set<String> signs = new HashSet<>();
                    for (Layout.Spot spot : layout.spots(new SignPlacement(position, rotation))) {
                        assertTrue(signs.add(spot.signX() + "," + spot.signZ()), position + " " + rotation + " path " + path);
                        int number = layout.numberAt(spot.signX(), spot.signZ());
                        assertTrue(number == 0 || number == spot.number(), "a sign never stands in another plot");
                    }
                }
            }
        }
    }

    @Test
    void signsTurnAndFaceThePlayer() {
        assertEquals(BlockFace.SOUTH_WEST, SignPlacement.turn(BlockFace.SOUTH));
        assertEquals(BlockFace.NORTH_EAST, SignPlacement.turn(BlockFace.NORTH));
        assertEquals(BlockFace.NORTH_NORTH_EAST, SignPlacement.turn(BlockFace.NORTH_NORTH_WEST));
        // Looking south (yaw 0), the sign faces back north at the player; looking west, it faces east.
        assertEquals(BlockFace.NORTH, SignPlacement.toward(0));
        assertEquals(BlockFace.EAST, SignPlacement.toward(90));
        assertEquals(BlockFace.SOUTH, SignPlacement.toward(-180));
        assertEquals(BlockFace.NORTH_NORTH_WEST, SignPlacement.toward(-22));
    }
}
