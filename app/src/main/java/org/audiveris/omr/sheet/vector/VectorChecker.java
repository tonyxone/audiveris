//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    V e c t o r C h e c k e r                                   //
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
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.Grades;
import org.audiveris.omr.glyph.Shape;
import static org.audiveris.omr.run.Orientation.VERTICAL;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.run.RunTableFactory;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.vector.VectorSymbols.Category;
import org.audiveris.omr.sheet.vector.VectorSymbols.Kind;
import org.audiveris.omr.sheet.vector.VectorSymbols.Symbol;
import org.audiveris.omr.sig.inter.AlterInter;
import org.audiveris.omr.sig.inter.ArticulationInter;
import org.audiveris.omr.sig.inter.AugmentationDotInter;
import org.audiveris.omr.sig.inter.ClefInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.KeyAlterInter;
import org.audiveris.omr.sig.inter.RestInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.process.ByteProcessor;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Class <code>VectorChecker</code> checks the symbols recognized in a system against the
 * symbols a vector PDF page draws (see {@link VectorSymbols}).
 * <p>
 * A recognized symbol is confirmed when its center lies near the center of a drawn symbol of the
 * same kind (and, for a note head, of the same size), and its grade is raised accordingly.
 * Otherwise it is removed: the drawn symbol there is of another kind of the same category (a
 * hollow head on a black one, an augmentation dot on a staccato dot), or nothing of its category
 * is drawn there at all.
 * <p>
 * A category is checked in a system only where the PDF draws most of what was recognized: a page
 * may draw some symbols with a SMuFL font and others, such as its note heads, with an older one.
 * <p>
 * Note heads are checked as soon as they are built, before stems are looked for. The other
 * symbols are checked before they get linked to their notes.
 */
public abstract class VectorChecker
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(VectorChecker.class);

    //~ Constructors -------------------------------------------------------------------------------

    private VectorChecker ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //-------//
    // check //
    //-------//
    private static void check (SystemInfo system,
                               List<Inter> inters)
    {
        final VectorSymbols vectorSymbols = VectorSymbols.of(system.getSheet());

        if (vectorSymbols.isEmpty()) {
            return;
        }

        final Scale scale = system.getSheet().getScale();
        final double margin = scale.toPixelsDouble(constants.centerMargin);

        // Verdict on each checked inter, per category
        final Map<Category, List<Inter>> confirmed = new EnumMap<>(Category.class);
        final Map<Category, List<Inter>> rejected = new EnumMap<>(Category.class);

        for (Inter inter : inters) {
            if (inter.isRemoved()) {
                continue;
            }

            final Kind kind = kindOf(inter);

            if (kind == null) {
                continue;
            }

            final boolean same = isDrawn(vectorSymbols, inter, kind, margin);

            (same ? confirmed : rejected).computeIfAbsent(kind.category, c -> new ArrayList<>())
                    .add(inter);
        }

        // A category is trusted only where the PDF draws most of what was recognized: a page may
        // draw some symbols with a SMuFL font and others, such as its note heads, with another
        int nConfirmed = 0;
        int nRemoved = 0;

        for (Category category : Category.values()) {
            final List<Inter> good = confirmed.getOrDefault(category, Collections.emptyList());
            final List<Inter> bad = rejected.getOrDefault(category, Collections.emptyList());

            if (good.size() < constants.minAgreement.getValue() * (good.size() + bad.size())) {
                continue;
            }

            for (Inter inter : good) {
                inter.setGrade(Math.max(inter.getGrade(), Grades.intrinsicRatio));
            }

            for (Inter inter : bad) {
                if (inter.isVip()) {
                    logger.info("VIP {} not drawn by the PDF", inter);
                }

                inter.remove();
            }

            nConfirmed += good.size();
            nRemoved += bad.size();
        }

        logger.debug("S#{} vector check: {} confirmed, {} removed", system.getId(), nConfirmed,
                nRemoved);
    }

    //---------//
    // isDrawn //
    //---------//
    /**
     * Tell whether the PDF draws, at the center of a recognized symbol, a symbol of the
     * same kind.
     *
     * @param inter the recognized symbol
     * @return false if the page is not read from a vector PDF or draws no such symbol there
     */
    public static boolean isDrawn (Inter inter)
    {
        final Sheet sheet = inter.getSig().getSystem().getSheet();
        final VectorSymbols vectorSymbols = VectorSymbols.of(sheet);
        final Kind kind = kindOf(inter);

        if (vectorSymbols.isEmpty() || (kind == null)) {
            return false;
        }

        final double margin = sheet.getScale().toPixelsDouble(constants.centerMargin);

        return isDrawn(vectorSymbols, inter, kind, margin);
    }

    private static boolean isDrawn (VectorSymbols vectorSymbols,
                                    Inter inter,
                                    Kind kind,
                                    double margin)
    {
        final Point center = inter.getCenter();
        final Rectangle2D spot = new Rectangle2D.Double(
                center.x - margin,
                center.y - margin,
                2 * margin,
                2 * margin);

        for (Symbol symbol : vectorSymbols.intersected(kind.category, spot)) {
            if ((symbol.kind() == kind) && isCentered(center, symbol, margin)
                    && sizeFits(inter, symbol)) {
                return true;
            }
        }

        return false;
    }

    //--------------//
    // missedGlyphs //
    //--------------//
    /**
     * Report a glyph for each symbol of a shape the PDF draws in a system where the image
     * analysis found no candidate: its black pixels within the drawn symbol bounds.
     * <p>
     * A dot drawn close to a note head, for instance, gets erased with the head before symbols
     * are looked for.
     *
     * @param system the system to process
     * @param shape  the shape of symbol
     * @param known  the bounds of candidates already found
     * @return the glyphs of the missed symbols
     */
    public static List<Glyph> missedGlyphs (SystemInfo system,
                                            Shape shape,
                                            Collection<Rectangle> known)
    {
        final Sheet sheet = system.getSheet();
        final VectorSymbols vectorSymbols = VectorSymbols.of(sheet);
        final Kind kind = kindOf(shape);
        final List<Glyph> glyphs = new ArrayList<>();

        if (vectorSymbols.isEmpty() || (kind == null)) {
            return glyphs;
        }

        final ByteProcessor binary = sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        final Rectangle image = new Rectangle(0, 0, binary.getWidth(), binary.getHeight());

        for (Symbol symbol : vectorSymbols.intersected(kind.category, system.getBounds())) {
            if ((symbol.kind() != kind) || !system.getArea().contains(
                    symbol.bounds().getCenterX(),
                    symbol.bounds().getCenterY())) {
                continue;
            }

            final Rectangle roi = symbol.bounds().getBounds().intersection(image);

            if (roi.isEmpty() || known.stream().anyMatch(box -> box.intersects(roi))) {
                continue;
            }

            final Point offset = new Point();
            final RunTable table = new RunTableFactory(VERTICAL).createTable(binary, roi).trim(
                    offset);

            if (table.getWeight() == 0) {
                continue;
            }

            glyphs.add(sheet.getGlyphIndex().registerOriginal(
                    new Glyph(roi.x + offset.x, roi.y + offset.y, table)));
        }

        logger.debug("S#{} {} missed {}", system.getId(), glyphs.size(), shape);

        return glyphs;
    }

    //------------//
    // isCentered //
    //------------//
    /**
     * Whether a recognized symbol center lies near the center of the drawn symbol: closer
     * than a quarter of its size, or than the margin for a small symbol. A note head one step
     * off the drawn one overlaps it, but is half its height away.
     */
    private static boolean isCentered (Point center,
                                       Symbol symbol,
                                       double margin)
    {
        final Rectangle2D b = symbol.bounds();

        return (Math.abs(center.x - b.getCenterX()) <= Math.max(margin, b.getWidth() / 4))
                && (Math.abs(center.y - b.getCenterY()) <= Math.max(margin, b.getHeight() / 4));
    }

    //------------//
    // checkHeads //
    //------------//
    /**
     * Check the note heads of a system against the PDF.
     *
     * @param system the system to process
     */
    public static void checkHeads (SystemInfo system)
    {
        check(system, system.getSig().inters(HeadInter.class));
    }

    //--------------//
    // checkSymbols //
    //--------------//
    /**
     * Check the clefs, accidentals, dots and rests of a system against the PDF.
     *
     * @param system the system to process
     */
    public static void checkSymbols (SystemInfo system)
    {
        final List<Inter> inters = new ArrayList<>();
        inters.addAll(system.getSig().inters(ClefInter.class));
        inters.addAll(system.getSig().inters(AlterInter.class));
        inters.addAll(system.getSig().inters(AugmentationDotInter.class));
        inters.addAll(system.getSig().inters(ArticulationInter.class));
        inters.addAll(system.getSig().inters(RestInter.class));
        check(system, inters);
    }

    //--------//
    // kindOf //
    //--------//
    /**
     * Report the kind of drawn symbol a recognized symbol stands for.
     *
     * @param inter the recognized symbol
     * @return its kind, or null if not checked
     */
    private static Kind kindOf (Inter inter)
    {
        if (inter instanceof KeyAlterInter) {
            return null; // Key signatures are read on their own
        }

        return (inter.getShape() != null) ? kindOf(inter.getShape()) : null;
    }

    /**
     * Report the kind of drawn symbol a shape stands for.
     *
     * @param shape the recognized shape
     * @return its kind, or null if not checked
     */
    private static Kind kindOf (Shape shape)
    {
        return switch (shape) {
            case NOTEHEAD_BLACK, NOTEHEAD_BLACK_SMALL -> Kind.HEAD_BLACK;
            case NOTEHEAD_VOID, NOTEHEAD_VOID_SMALL -> Kind.HEAD_VOID;
            case WHOLE_NOTE, WHOLE_NOTE_SMALL -> Kind.HEAD_WHOLE;
            case BREVE, BREVE_SMALL -> Kind.HEAD_BREVE;
            case G_CLEF, G_CLEF_SMALL, G_CLEF_8VA, G_CLEF_8VB -> Kind.CLEF_G;
            case F_CLEF, F_CLEF_SMALL, F_CLEF_8VA, F_CLEF_8VB -> Kind.CLEF_F;
            case C_CLEF -> Kind.CLEF_C;
            case FLAT -> Kind.FLAT;
            case NATURAL -> Kind.NATURAL;
            case SHARP -> Kind.SHARP;
            case DOUBLE_SHARP -> Kind.DOUBLE_SHARP;
            case DOUBLE_FLAT -> Kind.DOUBLE_FLAT;
            case AUGMENTATION_DOT -> Kind.AUGMENTATION_DOT;
            case STACCATO -> Kind.STACCATO;
            case WHOLE_REST -> Kind.REST_WHOLE;
            case HALF_REST -> Kind.REST_HALF;
            case QUARTER_REST -> Kind.REST_QUARTER;
            case EIGHTH_REST -> Kind.REST_8TH;
            case ONE_16TH_REST -> Kind.REST_16TH;
            case ONE_32ND_REST -> Kind.REST_32ND;
            case ONE_64TH_REST -> Kind.REST_64TH;
            default -> null;
        };
    }

    //----------//
    // sizeFits //
    //----------//
    /**
     * Whether a note head and the drawn head are both of standard size, or both small (cue
     * or grace).
     */
    private static boolean sizeFits (Inter inter,
                                     Symbol symbol)
    {
        return !(inter instanceof HeadInter) || (symbol.small() == inter.getShape().isSmallHead());
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Scale.Fraction centerMargin = new Scale.Fraction(
                0.2,
                "Margin around a symbol center to find the drawn symbol");

        private final Constant.Ratio minAgreement = new Constant.Ratio(
                0.7,
                "Minimum ratio of recognized symbols drawn by the PDF to trust a category");
    }
}
