package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.PropertySnapshot;

import net.minecraft.client.input.MouseButtonEvent;

import java.util.Locale;

/** Text and number conversions for element properties shared by the menu and Effect editors. */
final class PropertyValues
{
    /** Values closer to zero than this are written as 0 instead of an exponent. */
    private static final double ZERO_EPSILON = 0.0000005;
    /** Values this close to a whole number are shown without decimals. */
    private static final double WHOLE_EPSILON = 0.00001;
    private static final double MAX_OPACITY = 255;
    private static final double MIN_SIZE = 0.01;

    private PropertyValues()
    {}

    static boolean isNumeric(PropertySnapshot property)
    {
        return property.type() == EditorProtocol.PROPERTY_NUMBER || property.type() == EditorProtocol.PROPERTY_INTEGER;
    }

    /** The value as shown in a single-line edit box: line breaks and backslashes of multi-line text are escaped. */
    static String display(PropertySnapshot property)
    {
        if (property.type() != EditorProtocol.PROPERTY_MULTILINE)
        {
            return property.value();
        }
        return property.value().replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    /** Reverses {@link #display}; unknown escapes such as {@code \t} are kept as typed. */
    static String submitted(PropertySnapshot property, String value)
    {
        if (property.type() != EditorProtocol.PROPERTY_MULTILINE)
        {
            return value.trim();
        }
        StringBuilder output = new StringBuilder();
        boolean escape = false;
        for (char character : value.toCharArray())
        {
            if (escape)
            {
                if (character == 'n')
                {
                    output.append('\n');
                }
                else if (character == '\\')
                {
                    output.append('\\');
                }
                else
                {
                    output.append('\\').append(character);
                }
                escape = false;
            }
            else if (character == '\\')
            {
                escape = true;
            }
            else
            {
                output.append(character);
            }
        }
        if (escape)
        {
            output.append('\\');
        }
        return output.toString();
    }

    /** How much one pixel of horizontal dragging changes the property; Alt is fine, Shift coarse, Ctrl snaps. */
    static double dragStep(PropertySnapshot property, MouseButtonEvent event)
    {
        String key = property.key();
        boolean rotation = key.startsWith("rotation.");
        boolean scale = key.startsWith("scale.");
        double normal = rotation ? 1.0 : scale ? 0.01 : property.type() == EditorProtocol.PROPERTY_INTEGER ? 1.0 : 0.25;
        if (event.hasAltDown())
        {
            return normal / 5.0;
        }
        if (event.hasShiftDown())
        {
            return normal * 5.0;
        }
        if (event.hasControlDown())
        {
            return rotation ? 15.0 : scale ? 0.1 : 1.0;
        }
        return normal;
    }

    /** Rounds integers and keeps opacity and sizes inside the range the server accepts. */
    static double normalize(PropertySnapshot property, double value)
    {
        if (!Double.isFinite(value))
        {
            return 0.0;
        }
        if (property.type() == EditorProtocol.PROPERTY_INTEGER)
        {
            value = Math.rint(value);
        }
        String key = property.key();
        if (key.equals("opacity"))
        {
            value = Math.max(0, Math.min(MAX_OPACITY, value));
        }
        if (key.equals("width") || key.equals("height") || key.equals("thickness"))
        {
            value = Math.max(MIN_SIZE, value);
        }
        return Math.abs(value) < ZERO_EPSILON ? 0.0 : value;
    }

    static String format(PropertySnapshot property, double value)
    {
        if (property.type() == EditorProtocol.PROPERTY_INTEGER)
        {
            return Long.toString(Math.round(value));
        }
        return decimal(value);
    }

    /** Up to three decimals without trailing zeros, for display. */
    static String decimal(double value)
    {
        if (Math.abs(value - Math.rint(value)) < WHOLE_EPSILON)
        {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Parses {@code value}, falling back to {@code fallback} and then to zero. */
    static double parse(String value, String fallback)
    {
        try
        {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException invalid)
        {
            try
            {
                return Double.parseDouble(fallback);
            }
            catch (NumberFormatException invalidFallback)
            {
                return 0.0;
            }
        }
    }
}
