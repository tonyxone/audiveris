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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Class <code>VectorSymbols</code> gathers the music symbols that a vector PDF page draws with
 * a SMuFL music font (Bravura, Leland, MuseScore's own font...), with their precise bounds in
 * sheet image pixels.
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
    private static final VectorSymbols EMPTY = new VectorSymbols(Collections.emptyList());

    /** The symbols of each sheet, extracted once. */
    private static final Map<SheetStub, VectorSymbols> cache = Collections.synchronizedMap(
            new WeakHashMap<>());

    /** The SMuFL code points of the symbols we use. */
    private static final Map<Character, Kind> SMUFL = new HashMap<>();

    static {
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
    }

    //~ Enumerations -------------------------------------------------------------------------------

    /** The families of symbols that can be mistaken for one another. */
    public enum Category
    {
        HEAD,
        CLEF,
        ACCIDENTAL,
        DOT,
        REST;
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
        REST_64TH(Category.REST);

        public final Category category;

        Kind (Category category)
        {
            this.category = category;
        }
    }

    //~ Instance fields ----------------------------------------------------------------------------

    /** The symbols, per category. */
    private final Map<Category, List<Symbol>> symbols = new EnumMap<>(Category.class);

    //~ Constructors -------------------------------------------------------------------------------

    private VectorSymbols (List<Symbol> all)
    {
        // A head is small (cue or grace) when clearly shorter than the usual heads of the page
        final double[] heights = all.stream().filter(s -> s.kind.category == Category.HEAD)
                .mapToDouble(s -> s.bounds.getHeight()).sorted().toArray();
        final double usual = (heights.length > 0) ? heights[heights.length / 2] : 0;

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
                    : new VectorSymbols(collector.symbols);
            logger.info("{}", vectorSymbols);

            return vectorSymbols;
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not read vector symbols from {} {}", path, ex.toString());

            return EMPTY;
        }
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
            final String unicode = font.toUnicode(code);

            if ((unicode == null) || (unicode.length() != 1)
                    || !(font instanceof PDVectorFont vectorFont)) {
                return;
            }

            final Kind kind = SMUFL.get(unicode.charAt(0));

            if (kind == null) {
                return;
            }

            final GeneralPath glyph = vectorFont.getNormalizedPath(code);

            if (glyph == null) {
                return;
            }

            // Glyph space -> PDF user space (y up) -> sheet pixels (y down)
            final AffineTransform at = textRenderingMatrix.createAffineTransform();
            at.concatenate(font.getFontMatrix().createAffineTransform());

            final Rectangle2D user = at.createTransformedShape(glyph).getBounds2D();

            if (user.isEmpty()) {
                return;
            }

            symbols.add(
                    new Symbol(
                            kind,
                            new Rectangle2D.Double(
                                    (user.getMinX() - box.getLowerLeftX()) * sx,
                                    (box.getUpperRightY() - user.getMaxY()) * sy,
                                    user.getWidth() * sx,
                                    user.getHeight() * sy),
                            false));
        }
    }
}
