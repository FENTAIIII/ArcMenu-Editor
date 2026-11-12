package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.PropertySnapshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyValuesTest
{
    private static PropertySnapshot property(String key, byte type, String value)
    {
        return new PropertySnapshot(key, type, value, List.of());
    }

    @Test
    void multilineTextRoundTripsThroughTheSingleLineEditor()
    {
        var text = property("content", EditorProtocol.PROPERTY_MULTILINE, "first\r\nsecond \\ path");

        String shown = PropertyValues.display(text);

        assertEquals("first\\nsecond \\\\ path", shown);
        assertEquals("first\nsecond \\ path", PropertyValues.submitted(text, shown));
    }

    @Test
    void unknownEscapesAndTrailingBackslashAreKept()
    {
        var text = property("content", EditorProtocol.PROPERTY_MULTILINE, "");

        assertEquals("tab\\there\\", PropertyValues.submitted(text, "tab\\there\\"));
    }

    @Test
    void singleLineValuesAreTrimmedOnly()
    {
        var name = property("id", EditorProtocol.PROPERTY_TEXT, "");

        assertEquals("a\\nb", PropertyValues.submitted(name, "  a\\nb "));
    }

    @Test
    void normalizeRoundsIntegersAndClampsOpacityAndSize()
    {
        assertEquals(3.0, PropertyValues.normalize(property("count", EditorProtocol.PROPERTY_INTEGER, "0"), 2.6));
        assertEquals(255.0, PropertyValues.normalize(property("opacity", EditorProtocol.PROPERTY_INTEGER, "0"), 300));
        assertEquals(0.01, PropertyValues.normalize(property("width", EditorProtocol.PROPERTY_NUMBER, "0"), -4));
        assertEquals(0.0, PropertyValues.normalize(property("offset.x", EditorProtocol.PROPERTY_NUMBER, "0"), 1e-9));
        assertEquals(0.0,
                PropertyValues.normalize(property("offset.x", EditorProtocol.PROPERTY_NUMBER, "0"), Double.NaN));
    }

    @Test
    void decimalShowsAtMostThreePlacesWithoutTrailingZeros()
    {
        assertEquals("2", PropertyValues.decimal(2.000001));
        assertEquals("2.5", PropertyValues.decimal(2.5));
        assertEquals("0.333", PropertyValues.decimal(1.0 / 3));
    }

    @Test
    void parseFallsBackToTheOldValueThenZero()
    {
        assertEquals(1.5, PropertyValues.parse("1.5", "9"));
        assertEquals(9.0, PropertyValues.parse("abc", "9"));
        assertEquals(0.0, PropertyValues.parse("abc", "def"));
    }

    @Test
    void onlyNumberAndIntegerAreNumeric()
    {
        assertTrue(PropertyValues.isNumeric(property("x", EditorProtocol.PROPERTY_NUMBER, "0")));
        assertTrue(PropertyValues.isNumeric(property("x", EditorProtocol.PROPERTY_INTEGER, "0")));
        assertFalse(PropertyValues.isNumeric(property("x", EditorProtocol.PROPERTY_TEXT, "0")));
    }
}
