//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    V e c t o r S y m b o l s                                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.sheet.vector;

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Class <code>VectorSymbols</code> gathers the music symbols that a vector PDF page draws with
 * a music font, with their precise bounds in sheet image pixels.
 * <p>
 * Four font layouts are read: SMuFL (Bravura, Leland, MuseScore's own font...), the font of
 * MuseScore 2 (also named MScore, but with code points of its own), the Sonata layout
 * (Petrucci, Opus, Maestro...), which places symbols on the characters of the Mac Roman
 * encoding, and the Emmentaler font of LilyPond, whose glyphs are known by their names.
 * <p>
 * A PDF exported from a notation program draws note heads, clefs, accidentals, dots and rests as
 * characters of a music font, and the code point of each character tells what the symbol is,
 * with no recognition involved. A scanned page, or a page drawn with another font, has none.
 */
public class VectorSymbols
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(VectorSymbols.class);

    /** No symbols, for a page that is not a vector PDF page. */
    private static final VectorSymbols EMPTY = new VectorSymbols(Collections.emptyList(), false);

    /** The symbols of each sheet, extracted once. */
    private static final Map<SheetStub, VectorSymbols> cache = Collections.synchronizedMap(
            new WeakHashMap<>());

    /** The SMuFL code points of the symbols we use. */
    private static final Map<Character, Kind> SMUFL = new HashMap<>();

    /** The code points of the symbols we use, in the font of MuseScore 2. */
    private static final Map<Character, Kind> OLD_MSCORE = new HashMap<>();

    /** The Mac Roman bytes of the symbols we use, in the Sonata layout. */
    private static final Map<Integer, Kind> SONATA = new HashMap<>();

    /** The glyph names of the symbols we use, in the Emmentaler font of LilyPond. */
    private static final Map<String, Kind> EMMENTALER = new HashMap<>();

    /** The music fonts with the Sonata layout. */
    private static final Set<String> SONATA_FONTS = Set.of(
            "Engraver",
            "Maestro",
            "Opus",
            "OpusStd",
            "Petrucci",
            "Sonata");

    /** Encoder to the Mac Roman bytes of a Sonata layout font. */
    private static final Charset MAC_ROMAN = Charset.forName("x-MacRoman");

    static {
        emmentaler(Kind.HEAD_BREVE, "noteheads.sM1", "noteheads.sM1double");
        emmentaler(Kind.HEAD_WHOLE, "noteheads.s0");
        emmentaler(Kind.HEAD_VOID, "noteheads.s1");
        emmentaler(Kind.HEAD_BLACK, "noteheads.s2");
        emmentaler(Kind.CLEF_G, "clefs.G", "clefs.G_change");
        emmentaler(Kind.CLEF_C, "clefs.C", "clefs.C_change");
        emmentaler(Kind.CLEF_F, "clefs.F", "clefs.F_change");
        // Older versions of LilyPond name accidentals by their alteration in quarter tones
        emmentaler(Kind.FLAT, "accidentals.flat", "accidentals.M2");
        emmentaler(Kind.NATURAL, "accidentals.natural", "accidentals.0");
        emmentaler(Kind.SHARP, "accidentals.sharp", "accidentals.2");
        emmentaler(Kind.DOUBLE_SHARP, "accidentals.doublesharp", "accidentals.4");
        emmentaler(Kind.DOUBLE_FLAT, "accidentals.flatflat", "accidentals.M4");
        emmentaler(Kind.AUGMENTATION_DOT, "dots.dot");
        emmentaler(Kind.STACCATO, "scripts.staccato");
        emmentaler(Kind.REST_WHOLE, "rests.0", "rests.0o");
        emmentaler(Kind.REST_HALF, "rests.1", "rests.1o");
        emmentaler(Kind.REST_QUARTER, "rests.2");
        emmentaler(Kind.REST_8TH, "rests.3");
        emmentaler(Kind.REST_16TH, "rests.4");
        emmentaler(Kind.REST_32ND, "rests.5");
        emmentaler(Kind.REST_64TH, "rests.6");
        emmentaler(
                Kind.FERMATA,
                "scripts.ufermata",
                "scripts.dfermata",
                "scripts.ushortfermata",
                "scripts.dshortfermata",
                "scripts.ulongfermata",
                "scripts.dlongfermata",
                "scripts.uverylongfermata",
                "scripts.dverylongfermata",
                "scripts.uveryshortfermata",
                "scripts.dveryshortfermata",
                "scripts.uhenzeshortfermata",
                "scripts.dhenzeshortfermata",
                "scripts.uhenzelongfermata",
                "scripts.dhenzelongfermata");
        emmentaler(Kind.TRILL, "scripts.trill");
        emmentaler(Kind.PRALL, "scripts.prall");
        emmentaler(Kind.MORDENT, "scripts.mordent");
        emmentaler(Kind.TURN, "scripts.turn");
        emmentaler(Kind.TURN_INVERTED, "scripts.reverseturn");

        final String[] digits = {
                "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};

        for (int d = 0; d <= 9; d++) {
            final Kind kind = Kind.values()[Kind.TIME_0.ordinal() + d];
            emmentaler(kind, digits[d]);
            smufl(kind, 0xE080 + d);
        }

        for (int f = 0; f < 4; f++) {
            final Kind up = Kind.values()[Kind.FLAG_1_UP.ordinal() + (2 * f)];
            final Kind down = Kind.values()[Kind.FLAG_1_DOWN.ordinal() + (2 * f)];
            emmentaler(up, "flags.u" + (f + 3));
            emmentaler(down, "flags.d" + (f + 3));
            smufl(up, 0xE240 + (2 * f));
            smufl(down, 0xE241 + (2 * f));
        }

        smufl(Kind.OCTAVE_8, 0xE510, 0xE511, 0xE512, 0xE513, 0xE51C);
        smufl(Kind.OCTAVE_15, 0xE514, 0xE515, 0xE516, 0xE51D);
        smufl(Kind.OCTAVE_22, 0xE517, 0xE518, 0xE519, 0xE51E);

        smufl(Kind.HEAD_BREVE, 0xE0A0, 0xE0A1);
        smufl(Kind.HEAD_WHOLE, 0xE0A2);
        smufl(Kind.HEAD_VOID, 0xE0A3);
        smufl(Kind.HEAD_BLACK, 0xE0A4);
        smufl(Kind.CLEF_G, 0xE050, 0xE052, 0xE053, 0xE07A);
        smufl(Kind.CLEF_C, 0xE05C, 0xE07B);
        smufl(Kind.CLEF_F, 0xE062, 0xE064, 0xE065, 0xE07C);
        smufl(Kind.FLAT, 0xE260);
        smufl(Kind.NATURAL, 0xE261);
        smufl(Kind.SHARP, 0xE262);
        smufl(Kind.DOUBLE_SHARP, 0xE263);
        smufl(Kind.DOUBLE_FLAT, 0xE264);
        smufl(Kind.AUGMENTATION_DOT, 0xE1E7);
        smufl(Kind.STACCATO, 0xE4A2, 0xE4A3);
        smufl(Kind.REST_WHOLE, 0xE4E3);
        smufl(Kind.REST_HALF, 0xE4E4);
        smufl(Kind.REST_QUARTER, 0xE4E5);
        smufl(Kind.REST_8TH, 0xE4E6);
        smufl(Kind.REST_16TH, 0xE4E7);
        smufl(Kind.REST_32ND, 0xE4E8);
        smufl(Kind.REST_64TH, 0xE4E9);
        smufl(Kind.FERMATA, 0xE4C0, 0xE4C1, 0xE4C2, 0xE4C3, 0xE4C4, 0xE4C5, 0xE4C6, 0xE4C7,
              0xE4C8, 0xE4C9, 0xE4CA, 0xE4CB, 0xE4CC, 0xE4CD, 0xE4CE, 0xE4CF);
        smufl(Kind.TRILL, 0xE566);
        smufl(Kind.TURN, 0xE567);
        smufl(Kind.TURN_INVERTED, 0xE568);
        smufl(Kind.PRALL, 0xE56C);
        smufl(Kind.MORDENT, 0xE56D);

        // The augmentation dot of MuseScore 2 is a glyph of its own, unlike its staccato
        oldMScore(Kind.HEAD_WHOLE, 0xE12B);
        oldMScore(Kind.HEAD_VOID, 0xE12C);
        oldMScore(Kind.HEAD_BLACK, 0xE12D);
        oldMScore(Kind.CLEF_G, 0xE19E);
        oldMScore(Kind.CLEF_F, 0xE19C);
        oldMScore(Kind.SHARP, 0xE10E);
        oldMScore(Kind.NATURAL, 0xE113);
        oldMScore(Kind.FLAT, 0xE114);
        oldMScore(Kind.DOUBLE_FLAT, 0xE11A);
        oldMScore(Kind.AUGMENTATION_DOT, 0xE127);
        oldMScore(Kind.REST_WHOLE, 0xE100);
        oldMScore(Kind.REST_HALF, 0xE101);
        oldMScore(Kind.REST_QUARTER, 0xE107);
        oldMScore(Kind.REST_8TH, 0xE109);
        oldMScore(Kind.REST_16TH, 0xE10A);
        oldMScore(Kind.REST_32ND, 0xE10B);

        // The Sonata layout draws the augmentation dot and the staccato with one glyph: '.'
        SONATA.put(0xCF, Kind.HEAD_BLACK);
        SONATA.put(0xFA, Kind.HEAD_VOID);
        SONATA.put((int) 'w', Kind.HEAD_WHOLE);
        SONATA.put((int) '&', Kind.CLEF_G);
        SONATA.put((int) '?', Kind.CLEF_F);
        SONATA.put((int) 'B', Kind.CLEF_C);
        SONATA.put((int) 'b', Kind.FLAT);
        SONATA.put((int) 'n', Kind.NATURAL);
        SONATA.put((int) '#', Kind.SHARP);
        SONATA.put(0xB7, Kind.REST_WHOLE);
        SONATA.put(0xEE, Kind.REST_HALF);
        SONATA.put(0xCE, Kind.REST_QUARTER);
        SONATA.put(0xE4, Kind.REST_8TH);
        SONATA.put(0xC5, Kind.REST_16TH);
    }

    //~ Enumerations -------------------------------------------------------------------------------

    /** The families of symbols that can be mistaken for one another. */
    public enum Category
    {
        HEAD,
        CLEF,
        ACCIDENTAL,
        DOT,
        REST,
        TIME,
        OCTAVE,
        FLAG,
        TUPLET,
        ORNAMENT,
        FERMATA;
    }

    /** The symbols we read. */
    public enum Kind
    {
        HEAD_BLACK(Category.HEAD),
        HEAD_VOID(Category.HEAD),
        HEAD_WHOLE(Category.HEAD),
        HEAD_BREVE(Category.HEAD),
        CLEF_G(Category.CLEF),
        CLEF_F(Category.CLEF),
        CLEF_C(Category.CLEF),
        FLAT(Category.ACCIDENTAL),
        NATURAL(Category.ACCIDENTAL),
        SHARP(Category.ACCIDENTAL),
        DOUBLE_SHARP(Category.ACCIDENTAL),
        DOUBLE_FLAT(Category.ACCIDENTAL),
        AUGMENTATION_DOT(Category.DOT),
        STACCATO(Category.DOT),
        REST_WHOLE(Category.REST),
        REST_HALF(Category.REST),
        REST_QUARTER(Category.REST),
        REST_8TH(Category.REST),
        REST_16TH(Category.REST),
        REST_32ND(Category.REST),
        REST_64TH(Category.REST),
        TIME_0(Category.TIME),
        TIME_1(Category.TIME),
        TIME_2(Category.TIME),
        TIME_3(Category.TIME),
        TIME_4(Category.TIME),
        TIME_5(Category.TIME),
        TIME_6(Category.TIME),
        TIME_7(Category.TIME),
        TIME_8(Category.TIME),
        TIME_9(Category.TIME),
        OCTAVE_8(Category.OCTAVE),
        OCTAVE_15(Category.OCTAVE),
        OCTAVE_22(Category.OCTAVE),
        /** A lone italic value, which is an octave sign only if a line follows it. */
        OCTAVE_8_BARE(Category.OCTAVE),
        OCTAVE_15_BARE(Category.OCTAVE),
        /** Flags of a stem up (hanging down from its top) or down, by count of beams. */
        FLAG_1_UP(Category.FLAG),
        FLAG_1_DOWN(Category.FLAG),
        FLAG_2_UP(Category.FLAG),
        FLAG_2_DOWN(Category.FLAG),
        FLAG_3_UP(Category.FLAG),
        FLAG_3_DOWN(Category.FLAG),
        FLAG_4_UP(Category.FLAG),
        FLAG_4_DOWN(Category.FLAG),
        /** A lone italic number, as LilyPond writes the number of a tuplet. */
        TUPLET_3(Category.TUPLET),
        TUPLET_6(Category.TUPLET),
        /** Ornaments: a trill, a short trill (upper mordent), a mordent, turns. */
        TRILL(Category.ORNAMENT),
        PRALL(Category.ORNAMENT),
        MORDENT(Category.ORNAMENT),
        TURN(Category.ORNAMENT),
        TURN_INVERTED(Category.ORNAMENT),
        /** A fermata, above or below. */
        FERMATA(Category.FERMATA);

        public final Category category;

        Kind (Category category)
        {
            this.category = category;
        }

        /** The value of a time digit. */
        public int digit ()
        {
            return ordinal() - TIME_0.ordinal();
        }
    }

    //~ Instance fields ----------------------------------------------------------------------------

    /** The symbols, per category. */
    private final Map<Category, List<Symbol>> symbols = new EnumMap<>(Category.class);

    //~ Constructors -------------------------------------------------------------------------------

    /** Whether the heads are drawn with a font of a known layout, Emmentaler or SMuFL. */
    private final boolean knownFont;

    private VectorSymbols (List<Symbol> all,
                           boolean knownFont)
    {
        this.knownFont = knownFont;

        // A head is small (cue or grace) when clearly shorter than the usual heads of the page,
        // measured high in the range since small heads can make most of a page (a cadenza)
        final double[] heights = all.stream().filter(s -> s.kind.category == Category.HEAD)
                .mapToDouble(s -> s.bounds.getHeight()).sorted().toArray();
        final double usual = (heights.length > 0) ? heights[(heights.length * 9) / 10] : 0;

        for (Symbol symbol : all) {
            final boolean small = (symbol.kind.category == Category.HEAD)
                    && (symbol.bounds.getHeight() < (constants.maxSmallHeadRatio.getValue() * usual));
            symbols.computeIfAbsent(symbol.kind.category, c -> new ArrayList<>()).add(
                    new Symbol(symbol.kind, symbol.bounds, small));
        }
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-------------//
    // intersected //
    //-------------//
    /**
     * Report the symbols of a category whose bounds intersect the provided box.
     *
     * @param category the category of symbols
     * @param box      the box, in sheet pixels
     * @return the intersected symbols, perhaps empty
     */
    public List<Symbol> intersected (Category category,
                                     Rectangle2D box)
    {
        final List<Symbol> found = new ArrayList<>();

        for (Symbol symbol : symbols.getOrDefault(category, Collections.emptyList())) {
            if (symbol.bounds.intersects(box)) {
                found.add(symbol);
            }
        }

        return found;
    }

    //-------------//
    // isKnownFont //
    //-------------//
    /**
     * Tell whether the page draws its heads with a font of a known layout, Emmentaler or
     * SMuFL, so that a symbol of that font that the page does not draw is not there.
     *
     * @return true if so
     */
    public boolean isKnownFont ()
    {
        return knownFont;
    }

    //---------//
    // isEmpty //
    //---------//
    /**
     * Tell whether the page has no vector symbols at all, as a scanned page.
     *
     * @return true if so
     */
    public boolean isEmpty ()
    {
        return symbols.isEmpty();
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        final StringBuilder sb = new StringBuilder("VectorSymbols{");

        for (Map.Entry<Category, List<Symbol>> entry : symbols.entrySet()) {
            sb.append(' ').append(entry.getKey()).append(':').append(entry.getValue().size());
        }

        return sb.append('}').toString();
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //---------//
    // extract //
    //---------//
    private static VectorSymbols extract (Sheet sheet)
    {
        final SheetStub.SheetInput input = sheet.getStub().getSheetInput();
        final Path path = input.path;

        if ((path == null) || !path.toString().toLowerCase().endsWith(".pdf")) {
            return EMPTY;
        }

        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(path.toString()))) {
            final PDPage page = doc.getPage(input.number - 1);

            if ((page.getRotation() % 360) != 0) {
                return EMPTY; // Not handled
            }

            final PDRectangle box = page.getCropBox();
            final GlyphCollector collector = new GlyphCollector(
                    box,
                    sheet.getWidth() / box.getWidth(),
                    sheet.getHeight() / box.getHeight());
            collector.setStartPage(input.number);
            collector.setEndPage(input.number);
            collector.getText(doc);

            final VectorSymbols vectorSymbols = collector.symbols.isEmpty() ? EMPTY
                    : new VectorSymbols(collector.symbols, collector.knownFont);
            logger.info("{}", vectorSymbols);

            return vectorSymbols;
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not read vector symbols from {} {}", path, ex.toString());

            return EMPTY;
        }
    }

    //------------//
    // emmentaler //
    //------------//
    private static void emmentaler (Kind kind,
                                    String... names)
    {
        for (String name : names) {
            EMMENTALER.put(name, kind);
        }
    }

    //----------------//
    // emmentalerKind //
    //----------------//
    /**
     * Report the kind of symbol a code of the Emmentaler font draws, by the name of its glyph.
     *
     * @param font the font
     * @param code the code in the font
     * @return the kind of symbol, or null if not Emmentaler or not one we use
     */
    private static Kind emmentalerKind (PDFont font,
                                        int code)
    {
        if (!(font instanceof PDSimpleFont simple) || (simple.getEncoding() == null)) {
            return null;
        }

        if (isEmmentaler(font)) {
            return EMMENTALER.get(simple.getEncoding().getName(code));
        }

        // LilyPond writes its fingerings with the digits of its feta-alphabet font
        if ((font.getName() != null) && font.getName().contains("feta-alphabet")) {
            final Kind kind = EMMENTALER.get(simple.getEncoding().getName(code));

            return ((kind != null) && (kind.category == Category.TIME)) ? kind : null;
        }

        return null;
    }

    //--------------//
    // isEmmentaler //
    //--------------//
    private static boolean isEmmentaler (PDFont font)
    {
        return (font.getName() != null) && font.getName().contains("Emmentaler");
    }

    //--------//
    // kindOf //
    //--------//
    /**
     * Report the kind of symbol a character of a font draws.
     *
     * @param font the font
     * @param c    the character
     * @return the kind of symbol, or null if not one we use
     */
    private static Kind kindOf (PDFont font,
                                char c)
    {
        final Kind kind = SMUFL.get(c);

        if (kind != null) {
            return kind;
        }

        // Font name, without the prefix of a subset ("ABCDEF+", "CIDFont+")
        final String fontName = (font.getName() == null) ? "" : font.getName().replaceFirst(
                "^[^+]*\\+",
                "");

        if (fontName.equals("MScore")) {
            return OLD_MSCORE.get(c);
        }

        if ((c >= 0xF000) && (c <= 0xF0FF) && (SONATA_FONTS.contains(fontName) || fontName
                .matches("F\\d+"))) {
            // A symbol font, which maps its bytes to private code points, as Finale does
            return SONATA.get(c - 0xF000);
        }

        if (SONATA_FONTS.contains(fontName)) {
            final CharsetEncoder encoder = MAC_ROMAN.newEncoder();

            if (encoder.canEncode(c)) {
                return SONATA.get(String.valueOf(c).getBytes(MAC_ROMAN)[0] & 0xFF);
            }
        }

        return null;
    }

    //----//
    // of //
    //----//
    /**
     * Report the vector symbols of a sheet, read from its PDF page the first time.
     *
     * @param sheet the sheet
     * @return its vector symbols, empty for a scanned page or when the use is disabled
     */
    public static VectorSymbols of (Sheet sheet)
    {
        if (!constants.useVectorSymbols.isSet()) {
            return EMPTY;
        }

        return cache.computeIfAbsent(sheet.getStub(), stub -> extract(sheet));
    }

    //-----------//
    // oldMScore //
    //-----------//
    private static void oldMScore (Kind kind,
                                   int... codes)
    {
        for (int code : codes) {
            OLD_MSCORE.put((char) code, kind);
        }
    }

    //-------//
    // smufl //
    //-------//
    private static void smufl (Kind kind,
                               int... codes)
    {
        for (int code : codes) {
            SMUFL.put((char) code, kind);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //--------//
    // Symbol //
    //--------//
    /**
     * A symbol drawn by the PDF.
     *
     * @param kind   what it is
     * @param bounds the bounds of its outline, in sheet pixels
     * @param small  true for a small (cue or grace) note head
     */
    public static record Symbol(Kind kind,
                                Rectangle2D bounds,
                                boolean small)
    {
    }

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Boolean useVectorSymbols = new Constant.Boolean(
                true,
                "Should the music symbols drawn by a vector PDF check the recognized ones?");

        private final Constant.Ratio maxSmallHeadRatio = new Constant.Ratio(
                0.85,
                "Maximum height of a small note head, relative to the usual heads of the page");
    }

    //----------------//
    // GlyphCollector //
    //----------------//
    /**
     * Collects the music font glyphs a page draws, with the bounds of their outlines.
     */
    private static class GlyphCollector
            extends PDFTextStripper
    {
        private final PDRectangle box;

        private final double sx;

        private final double sy;

        private final List<Symbol> symbols = new ArrayList<>();

        /** Whether some heads are drawn with Emmentaler or a SMuFL font. */
        private boolean knownFont;

        /** The characters drawn in a row so far, that may make an octave sign. */
        private final StringBuilder octave = new StringBuilder();

        /** The bounds of the octave value drawn so far. */
        private Rectangle2D octaveValue;

        /** The right end of the last character of the row. */
        private double octaveRight;

        /** Whether the value of the row is drawn in italics. */
        private boolean octaveItalic;

        GlyphCollector (PDRectangle box,
                        double sx,
                        double sy)
            throws IOException
        {
            this.box = box;
            this.sx = sx;
            this.sy = sy;
        }

        @Override
        protected void showGlyph (Matrix textRenderingMatrix,
                                  PDFont font,
                                  int code,
                                  Vector displacement)
            throws IOException
        {
            if (!(font instanceof PDVectorFont vectorFont)) {
                octave.setLength(0);
                return;
            }

            final Rectangle2D bounds = boundsOf(textRenderingMatrix, font, vectorFont, code);

            // Emmentaler glyphs are known by their names, and have no unicode
            Kind kind = emmentalerKind(font, code);
            Character c = null;

            if ((kind != null) && (kind.category == Category.TIME)) {
                c = (char) ('0' + kind.digit());
            } else if (!isEmmentaler(font)) {
                final String unicode = font.toUnicode(code);

                if ((unicode != null) && (unicode.length() == 1)) {
                    c = unicode.charAt(0);
                    kind = kindOf(font, c);
                }
            }

            if (bounds == null) {
                return;
            }

            trackOctave(c, bounds, font);

            if (kind != null) {
                symbols.add(new Symbol(kind, bounds, false));

                if ((kind.category == Category.HEAD) && (isEmmentaler(font) || ((c != null)
                        && SMUFL.containsKey(c)))) {
                    knownFont = true;
                }
            }
        }

        //----------//
        // boundsOf //
        //----------//
        /**
         * The bounds of a glyph, in sheet pixels, or null if it draws nothing.
         */
        private Rectangle2D boundsOf (Matrix textRenderingMatrix,
                                      PDFont font,
                                      PDVectorFont vectorFont,
                                      int code)
            throws IOException
        {
            final GeneralPath glyph = vectorFont.getNormalizedPath(code);

            if (glyph == null) {
                return null;
            }

            // Glyph space -> PDF user space (y up) -> sheet pixels (y down)
            final AffineTransform at = textRenderingMatrix.createAffineTransform();
            at.concatenate(font.getFontMatrix().createAffineTransform());

            final Rectangle2D user = at.createTransformedShape(glyph).getBounds2D();

            if (user.isEmpty()) {
                return null;
            }

            return new Rectangle2D.Double(
                    (user.getMinX() - box.getLowerLeftX()) * sx,
                    (box.getUpperRightY() - user.getMaxY()) * sy,
                    user.getWidth() * sx,
                    user.getHeight() * sy);
        }

        //-------------//
        // trackOctave //
        //-------------//
        /**
         * Follow the characters drawn in a row, to find the octave signs written as text: a
         * value (8, 15, 22) followed by its letters (va, vb, ma, mb), as LilyPond writes them
         * with its own digits and the letters of a text font.
         * A symbol is then recorded with the bounds of the value.
         */
        private void trackOctave (Character c,
                                  Rectangle2D bounds,
                                  PDFont font)
        {
            final boolean near = (octave.length() > 0) && (bounds.getMinX() - octaveRight
                    < octaveValue.getHeight()) && (Math.abs(bounds.getMaxY() - octaveValue
                            .getMaxY()) < (0.5 * octaveValue.getHeight()));

            if ((c != null) && Character.isDigit(c)) {
                if (near && octave.chars().allMatch(Character::isDigit)) {
                    octave.append(c);
                    octaveValue = octaveValue.createUnion(bounds);
                } else {
                    endOctave();
                    octave.append(c);
                    octaveValue = (Rectangle2D) bounds.clone();
                    octaveItalic = (font.getName() != null) && font.getName().matches(
                            "(?i).*(ital|oblique).*");
                }
            } else if ((c != null) && near && ("vmab".indexOf(c) >= 0)) {
                octave.append(c);
            } else {
                endOctave();
                return;
            }

            octaveRight = bounds.getMaxX();

            final Kind kind = switch (octave.toString()) {
                case "8va", "8vb" -> Kind.OCTAVE_8;
                case "15ma", "15mb" -> Kind.OCTAVE_15;
                case "22ma", "22mb" -> Kind.OCTAVE_22;
                default -> null;
            };

            if (kind != null) {
                symbols.add(new Symbol(kind, octaveValue, false));
                octave.setLength(0);
            }
        }

        //-----------//
        // endOctave //
        //-----------//
        /**
         * End the row of characters: a value left alone, in italics, may be a bare octave sign,
         * or the number of a tuplet.
         */
        private void endOctave ()
        {
            if (octaveItalic) {
                final Kind kind = switch (octave.toString()) {
                    case "8" -> Kind.OCTAVE_8_BARE;
                    case "15" -> Kind.OCTAVE_15_BARE;
                    case "3" -> Kind.TUPLET_3;
                    case "6" -> Kind.TUPLET_6;
                    default -> null;
                };

                if (kind != null) {
                    symbols.add(new Symbol(kind, octaveValue, false));
                }
            }

            octave.setLength(0);
            octaveItalic = false;
        }
    }
}
