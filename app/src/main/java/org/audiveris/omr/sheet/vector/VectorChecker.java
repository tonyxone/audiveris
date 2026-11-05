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
import org.audiveris.omr.sig.relation.Exclusion;
import org.audiveris.omr.sig.relation.NoExclusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.process.ByteProcessor;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
        final Map<Category, List<Inter>> confused = new EnumMap<>(Category.class);

        for (Inter inter : inters) {
            if (inter.isRemoved()) {
                continue;
            }

            final Kind kind = kindOf(inter);

            if (kind == null) {
                continue;
            }

            final boolean same = isDrawn(vectorSymbols, inter, kind, margin);

            // A dot on a digit the PDF draws is a piece of a fingering: whatever the trust in
            // dots, it goes
            if (!same && (kind.category == Category.DOT) && isOnDigit(vectorSymbols, inter)) {
                if (inter.isVip()) {
                    logger.info("VIP {} is a piece of a digit", inter);
                }

                inter.remove();
                continue;
            }
            final Map<Category, List<Inter>> verdict = same ? confirmed
                    : ((kind.category == Category.DOT)
                            && isConfused(vectorSymbols, inter, kind, margin)) ? confused
                                    : rejected;
            verdict.computeIfAbsent(kind.category, c -> new ArrayList<>()).add(inter);
        }

        // A category is trusted only where the PDF draws most of what was recognized: a page may
        // draw some symbols with a SMuFL font and others, such as its note heads, with another.
        // A dot read as a staccato, or the reverse, shows the PDF draws dots: it does not count
        // against trust, but is removed.
        int nConfirmed = 0;
        int nRemoved = 0;
        final List<Inter> drawn = new ArrayList<>();

        for (Category category : Category.values()) {
            final List<Inter> good = confirmed.getOrDefault(category, Collections.emptyList());
            final List<Inter> bad = new ArrayList<>(
                    rejected.getOrDefault(category, Collections.emptyList()));

            if (good.size() < constants.minAgreement.getValue() * (good.size() + bad.size())) {
                continue;
            }

            bad.addAll(confused.getOrDefault(category, Collections.emptyList()));

            for (Inter inter : good) {
                inter.setGrade(Math.max(inter.getGrade(), Grades.intrinsicRatio));
            }

            drawn.addAll(good);

            for (Inter inter : bad) {
                if (inter.isVip()) {
                    logger.info("VIP {} not drawn by the PDF", inter);
                }

                inter.remove();
            }

            nConfirmed += good.size();
            nRemoved += bad.size();
        }

        keepDrawnOverlaps(drawn);

        logger.debug("S#{} vector check: {} confirmed, {} removed", system.getId(), nConfirmed,
                nRemoved);
    }

    //-------------------//
    // keepDrawnOverlaps //
    //-------------------//
    /**
     * Two symbols of different kinds that the PDF both draws may overlap, such as the dot of a
     * rest under the next natural: neither excludes the other.
     *
     * @param drawn the recognized symbols the PDF draws
     */
    private static void keepDrawnOverlaps (List<Inter> drawn)
    {
        for (int i = 0; i < drawn.size(); i++) {
            final Inter inter = drawn.get(i);
            final var sig = inter.getSig();

            for (Inter other : drawn.subList(i + 1, drawn.size())) {
                if ((kindOf(other).category == kindOf(inter).category)
                        || !other.getBounds().intersects(inter.getBounds())) {
                    continue;
                }

                Exclusion exclusion;

                while ((exclusion = sig.getExclusion(inter, other)) != null) {
                    sig.removeEdge(exclusion);
                }

                if (!sig.containsEdge(inter, other) && !sig.containsEdge(other, inter)) {
                    sig.addEdge(inter, other, new NoExclusion());
                }
            }
        }
    }

    //--------------//
    // isOnFingering //
    //--------------//
    /**
     * Tell whether the PDF draws a fingering digit at the center of a recognized symbol, and
     * no tuplet number: LilyPond writes its tuplet numbers in italics, and its fingerings
     * with digits of its own.
     *
     * @param inter the recognized symbol
     * @return false if the page is not read from a vector PDF or draws no fingering there
     */
    public static boolean isOnFingering (Inter inter)
    {
        final VectorSymbols vectorSymbols = VectorSymbols.of(inter.getSig().getSystem()
                .getSheet());

        if (vectorSymbols.isEmpty()) {
            return false;
        }

        final Point center = inter.getCenter();
        final Rectangle2D spot = new Rectangle2D.Double(center.x - 1, center.y - 1, 3, 3);

        if (!vectorSymbols.intersected(Category.TUPLET, inter.getBounds()).isEmpty()) {
            return false;
        }

        return isOnDigit(vectorSymbols, inter) && !vectorSymbols.intersected(Category.TIME, spot)
                .isEmpty();
    }

    //----------------//
    // isOnAccidental //
    //----------------//
    /**
     * Tell whether the center of a recognized symbol lies within an accidental the PDF
     * draws, and on no note head.
     */
    private static boolean isOnAccidental (VectorSymbols vectorSymbols,
                                           Inter inter)
    {
        final Point center = inter.getCenter();
        final Rectangle2D spot = new Rectangle2D.Double(center.x, center.y, 1, 1);

        if (!vectorSymbols.intersected(Category.HEAD, spot).isEmpty()) {
            return false;
        }

        for (Symbol symbol : vectorSymbols.intersected(Category.ACCIDENTAL, spot)) {
            if (symbol.bounds().contains(center.x, center.y)) {
                return true;
            }
        }

        return false;
    }

    //-----------//
    // isOnDigit //
    //-----------//
    /**
     * Tell whether the center of a recognized symbol lies within a digit the PDF draws.
     */
    private static boolean isOnDigit (VectorSymbols vectorSymbols,
                                      Inter inter)
    {
        final Point center = inter.getCenter();
        final Rectangle2D spot = new Rectangle2D.Double(center.x, center.y, 1, 1);

        for (Symbol symbol : vectorSymbols.intersected(Category.TIME, spot)) {
            if (symbol.bounds().contains(center.x, center.y)) {
                return true;
            }
        }

        return false;
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

    //------------//
    // isConfused //
    //------------//
    /**
     * Tell whether the PDF draws, at the center of a recognized symbol, a symbol of the same
     * category but of another kind, such as a dot where a staccato was read.
     */
    private static boolean isConfused (VectorSymbols vectorSymbols,
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
            if ((symbol.kind() != kind) && isCentered(center, symbol, margin)) {
                return true;
            }
        }

        return false;
    }

    //-----------------//
    // drawnClefChange //
    //-----------------//
    /**
     * Report the clef change the PDF draws over a glyph: a G or F clef whose bounds mostly
     * match those of the glyph.
     * <p>
     * The image of a small clef within a staff is hard to recognize: its tail may get lost with
     * the staff line, and the classifier take what remains for a time signature or a note.
     *
     * @param glyph  the glyph
     * @param system the containing system
     * @return G_CLEF_SMALL or F_CLEF_SMALL, or null if the PDF draws no such clef there
     */
    public static Shape drawnClefChange (Glyph glyph,
                                         SystemInfo system)
    {
        final VectorSymbols vectorSymbols = VectorSymbols.of(system.getSheet());

        if (vectorSymbols.isEmpty()) {
            return null;
        }

        final Rectangle box = glyph.getBounds();

        for (Symbol symbol : vectorSymbols.intersected(Category.CLEF, box)) {
            if (!overlapsMost(symbol.bounds(), box)) {
                continue;
            }

            switch (symbol.kind()) {
                case CLEF_G -> {
                    return Shape.G_CLEF_SMALL;
                }
                case CLEF_F -> {
                    return Shape.F_CLEF_SMALL;
                }
                default -> {}
            }
        }

        return null;
    }

    //-----------//
    // drawnFlag //
    //-----------//
    /**
     * Report the flag the PDF draws over a glyph: a flag of a regular note whose bounds mostly
     * match those of the glyph, with a glyph of the black pixels within the drawn flag.
     * <p>
     * The classifier may score a plain flag too low to keep it, as for the dotted eighths of
     * Bach's first prelude of the Well-Tempered Clavier, whose glyph also takes the dot in: the
     * flag is then made of its own pixels, so as not to overlap the dot.
     *
     * @param glyph  the glyph
     * @param system the containing system
     * @return the flag shape with its glyph, or null if the PDF draws no such flag there
     */
    public static Map.Entry<Shape, Glyph> drawnFlag (Glyph glyph,
                                                     SystemInfo system)
    {
        final Sheet sheet = system.getSheet();
        final VectorSymbols vectorSymbols = VectorSymbols.of(sheet);

        if (vectorSymbols.isEmpty()) {
            return null;
        }

        final Rectangle box = glyph.getBounds();
        final double minHeight = system.getSheet().getScale().toPixelsDouble(
                constants.minFlagHeight);

        // Flags of small notes are drawn smaller: they are left alone
        for (Symbol symbol : vectorSymbols.intersected(Category.FLAG, box)) {
            if ((symbol.bounds().getHeight() < minHeight) || !overlapsMost(symbol.bounds(), box)) {
                continue;
            }

            final Shape shape = switch (symbol.kind()) {
                case FLAG_1_UP -> Shape.FLAG_1;
                case FLAG_1_DOWN -> Shape.FLAG_1_DOWN;
                case FLAG_2_UP -> Shape.FLAG_2;
                case FLAG_2_DOWN -> Shape.FLAG_2_DOWN;
                case FLAG_3_UP -> Shape.FLAG_3;
                case FLAG_3_DOWN -> Shape.FLAG_3_DOWN;
                case FLAG_4_UP -> Shape.FLAG_4;
                case FLAG_4_DOWN -> Shape.FLAG_4_DOWN;
                default -> null;
            };

            final ByteProcessor binary = sheet.getPicture().getSource(Picture.SourceKey.BINARY);
            final Rectangle roi = symbol.bounds().getBounds().intersection(box);

            if ((shape == null) || roi.isEmpty()) {
                continue;
            }

            final Point offset = new Point();
            final RunTable table = new RunTableFactory(VERTICAL).createTable(binary, roi).trim(
                    offset);

            if (table.getWeight() == 0) {
                continue;
            }

            final Glyph flag = sheet.getGlyphIndex().registerOriginal(
                    new Glyph(roi.x + offset.x, roi.y + offset.y, table));

            return new AbstractMap.SimpleEntry<>(shape, flag);
        }

        return null;
    }

    //-----------//
    // drawnTime //
    //-----------//
    /**
     * Report the time signature the PDF draws over a glyph: digits of a time signature size,
     * a numerator above a denominator, whose bounds together mostly match those of the glyph.
     * <p>
     * The classifier may take one time signature for another of the same look, such as 6/4 for
     * 3/4 in the Emmentaler font of LilyPond.
     *
     * @param glyph  the glyph
     * @param system the containing system
     * @return the shape of a predefined time signature, or null
     */
    public static Shape drawnTime (Glyph glyph,
                                   SystemInfo system)
    {
        final VectorSymbols vectorSymbols = VectorSymbols.of(system.getSheet());

        if (vectorSymbols.isEmpty()) {
            return null;
        }

        final Rectangle box = glyph.getBounds();
        final double minHeight = system.getSheet().getScale().toPixelsDouble(
                constants.minTimeDigitHeight);
        final List<Symbol> digits = new ArrayList<>();
        Rectangle2D drawn = null;

        // Fingerings use the same digits, smaller
        for (Symbol symbol : vectorSymbols.intersected(Category.TIME, box)) {
            if (symbol.bounds().getHeight() >= minHeight) {
                digits.add(symbol);
                drawn = (drawn == null) ? (Rectangle2D) symbol.bounds().clone()
                        : drawn.createUnion(symbol.bounds());
            }
        }

        if ((drawn == null) || !overlapsMost(drawn, box)) {
            return null;
        }

        // Numerator digits above the middle, denominator digits below, each read left to right
        final double midY = drawn.getCenterY();
        digits.sort(Comparator.comparingDouble(d -> d.bounds().getX()));

        int num = 0;
        int den = 0;

        for (Symbol digit : digits) {
            if (digit.bounds().getCenterY() < midY) {
                num = (10 * num) + digit.kind().digit();
            } else {
                den = (10 * den) + digit.kind().digit();
            }
        }

        return switch ((num * 100) + den) {
            case 404 -> Shape.TIME_FOUR_FOUR;
            case 202 -> Shape.TIME_TWO_TWO;
            case 204 -> Shape.TIME_TWO_FOUR;
            case 304 -> Shape.TIME_THREE_FOUR;
            case 504 -> Shape.TIME_FIVE_FOUR;
            case 604 -> Shape.TIME_SIX_FOUR;
            case 308 -> Shape.TIME_THREE_EIGHT;
            case 608 -> Shape.TIME_SIX_EIGHT;
            case 1208 -> Shape.TIME_TWELVE_EIGHT;
            default -> null;
        };
    }

    //--------------//
    // overlapsMost //
    //--------------//
    /**
     * Whether two boxes share most of their union.
     */
    private static boolean overlapsMost (Rectangle2D drawn,
                                         Rectangle2D box)
    {
        final Rectangle2D common = drawn.createIntersection(box);

        if (common.isEmpty()) {
            return false;
        }

        final double inter = common.getWidth() * common.getHeight();
        final double union = (drawn.getWidth() * drawn.getHeight())
                + (box.getWidth() * box.getHeight()) - inter;

        return inter >= (constants.minClefOverlap.getValue() * union);
    }

    //--------------//
    // drawnOctaves //
    //--------------//
    /**
     * Report the octave signs the PDF draws in a system: for each, a glyph of the black pixels
     * within the bounds of its value, with the kind of its value.
     *
     * @param system the system to process
     * @return the glyphs of the drawn octave values, with their kind
     */
    public static Map<Glyph, Kind> drawnOctaves (SystemInfo system)
    {
        return drawnGlyphs(system, Category.OCTAVE);
    }

    //-------------//
    // drawnGlyphs //
    //-------------//
    /**
     * Report the symbols of a category the PDF draws in a system: for each, a glyph of the
     * black pixels within its bounds, with its kind.
     *
     * @param system   the system to process
     * @param category the category of symbols
     * @return the glyphs of the drawn symbols, with their kind
     */
    private static Map<Glyph, Kind> drawnGlyphs (SystemInfo system,
                                                 Category category)
    {
        final Sheet sheet = system.getSheet();
        final VectorSymbols vectorSymbols = VectorSymbols.of(sheet);
        final Map<Glyph, Kind> glyphs = new LinkedHashMap<>();

        if (vectorSymbols.isEmpty()) {
            return glyphs;
        }

        final ByteProcessor binary = sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        final Rectangle image = new Rectangle(0, 0, binary.getWidth(), binary.getHeight());

        for (Symbol symbol : vectorSymbols.intersected(category, system.getBounds())) {
            final Rectangle roi = symbol.bounds().getBounds().intersection(image);

            if (roi.isEmpty() || !system.getArea().contains(
                    symbol.bounds().getCenterX(),
                    symbol.bounds().getCenterY())) {
                continue;
            }

            final Point offset = new Point();
            final RunTable table = new RunTableFactory(VERTICAL).createTable(binary, roi).trim(
                    offset);

            if (table.getWeight() == 0) {
                continue;
            }

            glyphs.put(
                    sheet.getGlyphIndex().registerOriginal(
                            new Glyph(roi.x + offset.x, roi.y + offset.y, table)),
                    symbol.kind());
        }

        return glyphs;
    }

    //--------------//
    // drawnTuplets //
    //--------------//
    /**
     * Report the tuplet numbers the PDF draws in a system, as LilyPond does with lone italic
     * digits: for each, a glyph of the black pixels within the digit, with its kind.
     *
     * @param system the system to process
     * @return the glyphs of the drawn tuplet numbers, with their kind
     */
    public static Map<Glyph, Kind> drawnTuplets (SystemInfo system)
    {
        return drawnGlyphs(system, Category.TUPLET);
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
     * Small heads read on an accidental the PDF draws, such as the halves of a sharp, go first:
     * they came after the heads were checked.
     *
     * @param system the system to process
     */
    public static void checkSymbols (SystemInfo system)
    {
        final VectorSymbols vectorSymbols = VectorSymbols.of(system.getSheet());

        if (!vectorSymbols.isEmpty()) {
            for (Inter inter : system.getSig().inters(HeadInter.class)) {
                if ((inter.getShape() != null) && inter.getShape().isSmallHead()
                        && isOnAccidental(vectorSymbols, inter)) {
                    if (inter.isVip()) {
                        logger.info("VIP {} is a piece of an accidental", inter);
                    }

                    inter.remove();
                }
            }
        }

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

        private final Constant.Ratio minClefOverlap = new Constant.Ratio(
                0.5,
                "Minimum ratio of common area to union area between a glyph and a drawn symbol");

        private final Scale.Fraction minFlagHeight = new Scale.Fraction(
                2.0,
                "Minimum height of a flag drawn by the PDF, below which it is a small note's");

        private final Scale.Fraction minTimeDigitHeight = new Scale.Fraction(
                1.5,
                "Minimum height of a time signature digit drawn by the PDF");

        private final Constant.Ratio minAgreement = new Constant.Ratio(
                0.7,
                "Minimum ratio of recognized symbols drawn by the PDF to trust a category");
    }
}
