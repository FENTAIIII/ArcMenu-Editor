package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementSelectionCalibrationTest {
    @Test
    void persistsFourAxesPerKindAndKeepsKindsIndependent(@TempDir Path directory) {
        Path file = directory.resolve("selection-calibration.properties");
        ElementSelectionCalibration saved = new ElementSelectionCalibration();
        saved.set(EditorProtocol.KIND_TEXT, 2.5, -1.25, 0.8, 1.15);
        saved.set(EditorProtocol.KIND_REGION, -4.0, 3.0, 1.2, 0.9);
        saved.save(file);

        ElementSelectionCalibration loaded = new ElementSelectionCalibration();
        loaded.load(file);
        assertEquals(new ElementSelectionCalibration.Calibration(2.5, -1.25, 0.8, 1.15),
                loaded.get(EditorProtocol.KIND_TEXT));
        assertEquals(new ElementSelectionCalibration.Calibration(-4.0, 3.0, 1.2, 0.9),
                loaded.get(EditorProtocol.KIND_REGION));
        assertEquals(ElementSelectionCalibration.defaultFor(EditorProtocol.KIND_IMAGE),
                loaded.get(EditorProtocol.KIND_IMAGE));
    }

    @Test
    void writesACompleteDeveloperEditableSettingsFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("selection-calibration.properties");
        new ElementSelectionCalibration().save(file);
        String content = Files.readString(file);
        for (byte kind : ElementSelectionCalibration.KINDS) {
            String prefix = ElementSelectionCalibration.key(kind);
            assertTrue(content.contains(prefix + ".offset-ratio-x"));
            assertTrue(content.contains(prefix + ".offset-ratio-y"));
            assertTrue(content.contains(prefix + ".scale-x"));
            assertTrue(content.contains(prefix + ".scale-y"));
        }
    }

    @Test
    void exactMenusDrawRectanglesWithoutTheLegacyCorrection() {
        ElementSelectionCalibration values = new ElementSelectionCalibration();
        assertEquals(ElementSelectionCalibration.IDENTITY, values.forNode(EditorProtocol.KIND_RECTANGLE, true));
        assertEquals(values.get(EditorProtocol.KIND_RECTANGLE), values.forNode(EditorProtocol.KIND_RECTANGLE, false));
        assertEquals(values.get(EditorProtocol.KIND_LINE), values.forNode(EditorProtocol.KIND_LINE, true));
        assertEquals(values.get(EditorProtocol.KIND_REGION), values.forNode(EditorProtocol.KIND_REGION, true));
    }

    @Test
    void releaseDefaultsMatchTheValidatedClientCalibration() {
        ElementSelectionCalibration values = new ElementSelectionCalibration();
        assertEquals(new ElementSelectionCalibration.Calibration(0.0, -0.03, 1.0, 0.9),
                values.get(EditorProtocol.KIND_RECTANGLE));
        assertEquals(new ElementSelectionCalibration.Calibration(0.0, 0.0, 1.02, 0.1),
                values.get(EditorProtocol.KIND_LINE));
        assertEquals(new ElementSelectionCalibration.Calibration(0.0, 0.5, 1.3, 1.3),
                values.get(EditorProtocol.KIND_TEXT));
        assertEquals(new ElementSelectionCalibration.Calibration(0.12, 0.25, 1.0, 1.0),
                values.get(EditorProtocol.KIND_IMAGE));
        assertEquals(new ElementSelectionCalibration.Calibration(0.0, -0.04, 1.01, 0.92),
                values.get(EditorProtocol.KIND_REGION));
    }

    @Test
    void clampsInvalidScaleWithoutDamagingOtherKindSettings() {
        ElementSelectionCalibration values = new ElementSelectionCalibration();
        values.set(EditorProtocol.KIND_TEXT, Double.NaN, 3.0, 0.0, Double.POSITIVE_INFINITY);
        assertEquals(new ElementSelectionCalibration.Calibration(0.0, 3.0, 0.01, 1.0),
                values.get(EditorProtocol.KIND_TEXT));
        assertEquals(ElementSelectionCalibration.IDENTITY, values.get(EditorProtocol.KIND_BLOCK));
    }

    @Test
    void proportionalOffsetTracksEachElementSize() {
        ElementSelectionCalibration.Calibration value =
                new ElementSelectionCalibration.Calibration(0.1, -0.25, 1.0, 1.0);

        assertEquals(30.0, value.proxyX(10.0, 200.0), 1.0e-12);
        assertEquals(12.0, value.proxyX(10.0, 20.0), 1.0e-12);
        assertEquals(-40.0, value.proxyY(10.0, 200.0), 1.0e-12);
        assertEquals(5.0, value.proxyY(10.0, 20.0), 1.0e-12);
    }

    @Test
    void resizeInverseRemovesBothOffsetRatioAndAxisScale() {
        ElementSelectionCalibration.Calibration value =
                new ElementSelectionCalibration.Calibration(0.2, -0.15, 1.25, 0.75);
        double nodeX = 20.0;
        double nodeY = -10.0;
        double width = 40.0;
        double height = 24.0;
        double proxyRight = value.proxyX(nodeX, width) + value.proxyWidth(width) / 2.0;
        double proxyBottom = value.proxyY(nodeY, height) - value.proxyHeight(height) / 2.0;

        assertEquals(nodeX + width / 2.0, value.serverRightX(nodeX, proxyRight), 1.0e-12);
        assertEquals(nodeY - height / 2.0, value.serverBottomY(nodeY, proxyBottom), 1.0e-12);
    }
}
