//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   S y m b o l s B u i l d e r                                  //
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
package org.audiveris.omr.sheet.symbol;

import org.audiveris.omr.classifier.Classifier;
import org.audiveris.omr.classifier.Evaluation;
import org.audiveris.omr.classifier.ShapeClassifier;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.GlyphCluster;
import org.audiveris.omr.glyph.GlyphGroup;
import org.audiveris.omr.glyph.GlyphLink;
import org.audiveris.omr.glyph.Glyphs;
import org.audiveris.omr.glyph.Grades;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.math.GeoUtil;
import org.audiveris.omr.score.TimeRational;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.vector.VectorChecker;
import org.audiveris.omr.sheet.vector.VectorSymbols;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.ClefInter;
import org.audiveris.omr.sig.inter.DynamicsInter;
import org.audiveris.omr.sig.inter.FermataInter;
import org.audiveris.omr.sig.inter.FlagInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.OrnamentInter;
import org.audiveris.omr.sig.inter.PedalInter;
import org.audiveris.omr.sig.inter.RestInter;
import org.audiveris.omr.sig.inter.SmallChordInter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.inter.TimeCustomInter;
import org.audiveris.omr.sig.inter.TupletInter;
import org.audiveris.omr.sig.relation.Exclusion;
import org.audiveris.omr.sig.relation.FlagStemRelation;
import org.audiveris.omr.sig.relation.NoExclusion;
import org.audiveris.omr.sig.relation.Relation;
import org.audiveris.omr.util.Dumping;
import org.audiveris.omr.util.Navigable;
import org.audiveris.omr.util.StopWatch;

import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.SimpleGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Class <code>SymbolsBuilder</code> is in charge, at system level, of retrieving all
 * possible symbols interpretations.
 *
 * @author Hervé Bitteur
 */
public class SymbolsBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(SymbolsBuilder.class);

    //~ Instance fields ----------------------------------------------------------------------------

    /** The dedicated system. */
    @Navigable(false)
    private final SystemInfo system;

    /** The related sheet. */
    @Navigable(false)
    private final Sheet sheet;

    /** Shape classifier to use. */
    private final Classifier classifier = ShapeClassifier.getInstance();

    /** Companion factory for symbols inters. */
    private final InterFactory factory;

    /** Areas where fine glyphs may be needed. */
    private final List<Rectangle> fineBoxes = new ArrayList<>();

    /** Scale-dependent global constants. */
    private final Parameters params;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new SymbolsBuilder object.
     *
     * @param system  the dedicated system
     * @param factory the dedicated symbol factory
     */
    public SymbolsBuilder (SystemInfo system,
                           InterFactory factory)
    {
        this.system = system;
        this.factory = factory;

        sheet = system.getSheet();

        params = new Parameters(sheet.getScale());
    }

    //~ Methods ------------------------------------------------------------------------------------

    //--------------//
    // buildSymbols //
    //--------------//
    /**
     * Find all possible interpretations of symbols composed from available system glyphs.
     * <p>
     * <b>Synopsis:</b>
     * <ol>
     * <li>retrieveFineBoxes() // Retrieve areas around small chords
     * <li>getSymbolsGlyphs() // Retrieve all glyphs usable for symbols
     * <li>buildLinks() // Build graph with distances
     * <li>processClusters(): // Group connected glyphs into clusters
     * <ol>
     * <li>FOREACH cluster of connected glyphs:
     * <ol>
     * <li>cluster.decompose() // Decompose cluster into all subsets
     * <li>FOREACH subset process(subset):
     * <ol>
     * <li>build compound glyph // Build one compound glyph per subset
     * <li>evaluateGlyph(compound) // Run shape classifier on compound
     * <li>FOREACH acceptable evaluation:
     * <ol>
     * <li>interFactory.create(eval, glyph) // Create inter(s) related to evaluation
     * </ol>
     * </ol>
     * </ol>
     * </ol>
     * </ol>
     *
     * @param optionalsMap the optional (weak) glyphs per system
     */
    public void buildSymbols (Map<SystemInfo, List<Glyph>> optionalsMap)
    {
        final StopWatch watch = new StopWatch("buildSymbols system #" + system.getId());
        logger.debug("System#{} buildSymbols", system.getId());

        // Identify areas for fine glyphs
        watch.start("retrieveFineBoxes");
        retrieveFineBoxes();

        // Retrieve all candidate glyphs
        watch.start("getSymbolsGlyphs");

        final List<Glyph> glyphs = getSymbolsGlyphs(optionalsMap);

        // Formalize glyphs relationships in a system-level graph
        watch.start("buildLinks");

        final SimpleGraph<Glyph, GlyphLink> systemGraph = Glyphs.buildLinks(glyphs, params.maxGap);

        // Process all sets of connected glyphs
        watch.start("processClusters");
        processClusters(systemGraph);

        // Tuplet numbers the PDF draws where none was read
        watch.start("addDrawnTuplets");
        addDrawnTuplets();

        watch.start("addDrawnOrnaments");
        addDrawnOrnaments();

        watch.start("checkDrawnDynamics");
        checkDrawnDynamics();

        watch.start("addDrawnFermatas");
        addDrawnFermatas();

        watch.start("addDrawnPedals");
        addDrawnPedals();

        watch.start("addDrawnFlags");
        addDrawnFlags();

        watch.start("addDrawnClefChanges");
        addDrawnClefChanges();

        watch.start("addDrawnRests");
        addDrawnRests();

        if (constants.printWatch.isSet()) {
            watch.print();
        }
    }

    //---------------//
    // evaluateGlyph //
    //---------------//
    /**
     * Evaluate a provided glyph and create all acceptable inter instances.
     *
     * @param glyph the glyph to evaluate
     */
    private void evaluateGlyph (Glyph glyph)
    {
        if (glyph.getId() == 0) {
            glyph = sheet.getGlyphIndex().registerOriginal(glyph);
        }

        logger.debug("evaluateGlyph on {}", glyph);

        if (glyph.isVip()) {
            logger.info("VIP evaluateGlyph on {}", glyph);
        }

        final Point2D center = glyph.getCenter2D();
        final Staff closestStaff = system.getClosestStaff(center); // Just an indication!

        if (closestStaff == null) {
            return;
        }

        Evaluation[] evals = classifier.evaluate(
                glyph,
                system,
                constants.maxEvaluationCount.getValue(),
                Grades.symbolMinGrade,
                EnumSet.of(Classifier.Condition.CHECKED));

        // A flag the PDF draws there is proposed, from its own pixels, when the classifier proposes
        // no flag for the glyph and none is there yet: it may score a plain flag too low to keep it
        final Map.Entry<Shape, Glyph> drawnFlag = isFlagged(glyph, evals) ? null
                : VectorChecker.drawnFlag(glyph, system);

        if (drawnFlag != null) {
            final Evaluation flagEval = new Evaluation(
                    drawnFlag.getKey(),
                    constants.drawnFlagGrade.getValue());
            final Inter flag = factory.create(flagEval, drawnFlag.getValue(), closestStaff);

            if (flag != null) {
                logger.debug("{} read from the PDF", flag);
            }
        }

        // A time signature the PDF draws there replaces any the classifier proposes. One with no
        // predefined shape, as the 3/2 of Brahms' Sapphic Ode, is read as a custom one: the
        // classifier knows no 3/2, and takes it for 3/4
        final TimeRational drawnRational = VectorChecker.drawnTimeRational(glyph, system);
        final Shape drawnTime = (drawnRational != null) ? VectorChecker.timeShape(drawnRational)
                : null;

        if (drawnRational != null) {
            final List<Evaluation> kept = new ArrayList<>();

            for (Evaluation eval : evals) {
                if (!ShapeSet.WholeTimes.contains(eval.shape)) {
                    kept.add(eval);
                }
            }

            if (drawnTime != null) {
                kept.add(0, new Evaluation(drawnTime, constants.drawnTimeGrade.getValue()));
            }

            evals = kept.toArray(Evaluation[]::new);
        }

        // Create one interpretation for each acceptable evaluation
        final SIGraph sig = system.getSig();
        final List<Inter> createdInters = new ArrayList<>();

        if ((drawnRational != null) && (drawnTime == null)) {
            final TimeCustomInter time = new TimeCustomInter(
                    drawnRational.num,
                    drawnRational.den,
                    constants.drawnTimeGrade.getValue());
            time.setGlyph(glyph);
            time.setBounds(glyph.getBounds());
            time.setStaff(closestStaff);
            sig.addVertex(time);
            createdInters.add(time);
        }

        final Shape clefChange = isTrebleClefChange(glyph, closestStaff) ? Shape.G_CLEF_SMALL
                : drawnClefChange(glyph, closestStaff, evals);

        if (clefChange != null) {
            final Inter clef = ClefInter.createValid(
                    glyph,
                    clefChange,
                    constants.clefChangeGrade.getValue(),
                    closestStaff);

            if (clef != null) {
                sig.addVertex(clef);
                createdInters.add(clef);
            }
        }

        for (Evaluation eval : evals) {
            try {
                final Inter created = factory.create(eval, glyph, closestStaff);

                if (created != null) {
                    // Set exclusion with all competitors already created for the same glyph
                    for (Inter other : createdInters) {
                        sig.insertExclusion(other, created, Exclusion.ExclusionCause.OVERLAP);
                    }

                    createdInters.add(created);
                }
            } catch (Exception ex) {
                logger.warn("Error in glyph evaluation " + ex, ex);
            }
        }
    }

    //-----------------//
    // addDrawnTuplets //
    //-----------------//
    /**
     * Propose the tuplet numbers a PDF draws where no tuplet sign was read, each made of the
     * pixels within its digit: the digit often touches the bracket of the tuplet, and the
     * glyph of both is no tuplet sign to the classifier. A sign already read there is
     * confirmed, and one read on a fingering digit is removed.
     */
    private void addDrawnTuplets ()
    {
        final int margin = sheet.getInterline() / 2;

        // A tuplet sign read on a fingering digit the PDF draws is that digit, and one read
        // where LilyPond draws no tuplet number is a piece of something else
        for (Inter tuplet : system.getSig().inters(TupletInter.class)) {
            if (VectorChecker.isOnFingering(tuplet)) {
                logger.debug("{} is a fingering", tuplet);
                tuplet.remove();
            } else if (VectorChecker.isUndrawnTuplet(tuplet)) {
                logger.debug("{} not drawn by the PDF", tuplet);
                tuplet.remove();
            }
        }

        for (Map.Entry<Glyph, VectorSymbols.Kind> entry : VectorChecker.drawnTuplets(system)
                .entrySet()) {
            final Glyph glyph = entry.getKey();
            final Rectangle around = glyph.getBounds();
            around.grow(margin, margin);

            // A sign already there is confirmed: a weak one is dropped when tuplets get linked
            final List<Inter> there = system.getSig().inters(TupletInter.class).stream().filter(
                    t -> t.getBounds().intersects(around)).toList();

            if (!there.isEmpty()) {
                for (Inter t : there) {
                    t.setGrade(Math.max(t.getGrade(), constants.drawnTupletGrade.getValue()));
                }

                continue;
            }

            final Staff staff = system.getClosestStaff(glyph.getCenter2D());

            if (staff == null) {
                continue;
            }

            final Shape shape = (entry.getValue() == VectorSymbols.Kind.TUPLET_6)
                    ? Shape.TUPLET_SIX
                    : Shape.TUPLET_THREE;
            final Inter tuplet = factory.create(
                    new Evaluation(shape, constants.drawnTupletGrade.getValue()),
                    glyph,
                    staff);

            if (tuplet != null) {
                logger.debug("{} read from the PDF", tuplet);
            }
        }
    }

    //----------------//
    // addDrawnPedals //
    //----------------//
    /**
     * Propose the pedal marks a PDF draws where none was read, and give a mark read as the
     * other one the drawn shape: a "Ped." missed, or taken for the asterisk that releases the
     * pedal, leaves a whole passage played without it.
     */
    private void addDrawnPedals ()
    {
        for (Map.Entry<Glyph, VectorSymbols.Kind> entry : VectorChecker.drawnPedals(system)
                .entrySet()) {
            final Glyph glyph = entry.getKey();
            final Rectangle box = glyph.getBounds();
            final Shape shape = (entry.getValue() == VectorSymbols.Kind.PEDAL_DOWN)
                    ? Shape.PEDAL_MARK
                    : Shape.PEDAL_UP_MARK;
            final List<Inter> there = system.getSig().inters(PedalInter.class).stream().filter(
                    p -> p.getBounds().intersects(box)).toList();

            // One read there is confirmed: a weak one is purged otherwise
            final List<Inter> right = there.stream().filter(p -> p.getShape() == shape)
                    .toList();

            if (!right.isEmpty()) {
                for (Inter p : right) {
                    p.setGrade(Math.max(p.getGrade(), constants.drawnPedalGrade.getValue()));
                }

                continue;
            }

            for (Inter wrong : there) {
                logger.debug("{} drawn as {}", wrong, shape);
                wrong.remove();
            }

            final Staff staff = system.getClosestStaff(glyph.getCenter2D());

            if (staff == null) {
                continue;
            }

            final Inter pedal = factory.create(
                    new Evaluation(shape, constants.drawnPedalGrade.getValue()),
                    glyph,
                    staff);

            if (pedal != null) {
                logger.debug("{} read from the PDF", pedal);
            }
        }
    }

    //---------------//
    // addDrawnRests //
    //---------------//
    /**
     * Propose the rests a PDF draws where none was read. A rest close to a note of the other
     * voice, as the quarter rests under the half notes of Liszt's first Consolation, may be
     * taken in a glyph of its own with it: the notes that follow then start a beat early, and
     * the rhythm comes out wrong.
     */
    private void addDrawnRests ()
    {
        final List<Rectangle> known = new ArrayList<>();

        for (Inter rest : system.getSig().inters(RestInter.class)) {
            known.add(rest.getBounds());
        }

        for (Shape shape : new Shape[] {
                Shape.WHOLE_REST,
                Shape.HALF_REST,
                Shape.QUARTER_REST,
                Shape.EIGHTH_REST,
                Shape.ONE_16TH_REST,
                Shape.ONE_32ND_REST,
                Shape.ONE_64TH_REST }) {
            for (Glyph glyph : VectorChecker.missedGlyphs(system, shape, known)) {
                final Staff staff = system.getClosestStaff(glyph.getCenter2D());

                if (staff == null) {
                    continue;
                }

                final Inter rest = factory.create(
                        new Evaluation(shape, constants.drawnRestGrade.getValue()),
                        glyph,
                        staff);

                if (rest != null) {
                    logger.debug("{} read from the PDF", rest);
                }
            }
        }
    }

    //---------------------//
    // addDrawnClefChanges //
    //---------------------//
    /**
     * Propose the clef changes a PDF draws where no clef was read. The curl of a small clef
     * may be taken for hollow heads and its line for their stem, and its pixels never make a
     * glyph of their own, as at the clef changes of the left hand of Liszt's third
     * Consolation: the clef is read from its own pixels, and the pieces give way to it.
     */
    private void addDrawnClefChanges ()
    {
        for (Map.Entry<Glyph, Shape> entry : VectorChecker.drawnClefs(system).entrySet()) {
            final Glyph glyph = entry.getKey();
            final Staff staff = system.getClosestStaff(glyph.getCenter2D());

            if ((staff == null) || (glyph.getLeft() <= staff.getHeaderStop())) {
                continue;
            }

            final Rectangle box = glyph.getBounds();

            if (system.getSig().inters(ClefInter.class).stream().anyMatch(c -> c.getBounds()
                    .intersects(box))) {
                continue;
            }

            final Inter clef = ClefInter.createValid(
                    glyph,
                    entry.getValue(),
                    constants.clefChangeGrade.getValue(),
                    staff);

            if (clef != null) {
                system.getSig().addVertex(clef);
                logger.debug("{} read from the PDF", clef);
            }
        }
    }

    //---------------//
    // addDrawnFlags //
    //---------------//
    /**
     * Propose the flags a PDF draws where none was read. Two voices of one staff may put their
     * stems side by side, as Bach's Chaconne does, and the flags of both make one glyph, read as
     * one flag: the other note was played twice as long.
     */
    private void addDrawnFlags ()
    {
        for (Map.Entry<Glyph, Shape> entry : VectorChecker.drawnFlags(system).entrySet()) {
            final Glyph glyph = entry.getKey();
            final Rectangle box = glyph.getBounds();
            final double area = box.getWidth() * box.getHeight();

            if (system.getSig().inters(FlagInter.class).stream().anyMatch(f -> {
                final Rectangle common = f.getBounds().intersection(box);

                return !common.isEmpty() && ((common.getWidth() * common.getHeight()) >= (area
                        / 2));
            })) {
                continue;
            }

            final Staff staff = system.getClosestStaff(glyph.getCenter2D());

            if (staff == null) {
                continue;
            }

            final Inter flag = factory.create(
                    new Evaluation(entry.getValue(), constants.drawnFlagGrade.getValue()),
                    glyph,
                    staff);

            if (flag == null) {
                continue;
            }

            logger.debug("{} read from the PDF", flag);

            // The drawn flag may cross the stem of the other voice, or the flag read on it, and
            // the pixels of its box take in the end of a short stem's head: they are drawn too,
            // and none must exclude it
            final SIGraph sig = system.getSig();
            final Set<Inter> stems = stemsOf(flag);
            final List<Inter> neighbors = new ArrayList<>(sig.inters(StemInter.class));
            neighbors.addAll(sig.inters(FlagInter.class));
            neighbors.addAll(sig.inters(HeadInter.class));

            for (Inter other : neighbors) {
                if ((other == flag) || stems.contains(other) || !other.getBounds().intersects(
                        flag.getBounds()) || ((other instanceof FlagInter) && !Collections
                                .disjoint(stems, stemsOf(other)))) {
                    continue;
                }

                Relation exclusion;

                while ((exclusion = sig.getExclusion(flag, other)) != null) {
                    sig.removeEdge(exclusion);
                }

                if (!sig.containsEdge(flag, other) && !sig.containsEdge(other, flag)) {
                    sig.addEdge(flag, other, new NoExclusion());
                }
            }
        }
    }

    private Set<Inter> stemsOf (Inter flag)
    {
        final SIGraph sig = system.getSig();
        final Set<Inter> stems = new LinkedHashSet<>();

        for (Relation rel : sig.getRelations(flag, FlagStemRelation.class)) {
            stems.add(sig.getOppositeInter(flag, rel));
        }

        return stems;
    }

    //------------------//
    // addDrawnFermatas //
    //------------------//
    /**
     * Propose the fermatas a PDF draws where none was read: the arc and its dot, close to a
     * note or a barline, are often taken for something else.
     */
    private void addDrawnFermatas ()
    {
        final int margin = sheet.getInterline() / 2;

        for (Glyph glyph : VectorChecker.drawnFermatas(system)) {
            final Rectangle around = glyph.getBounds();
            around.grow(margin, margin);

            if (system.getSig().inters(FermataInter.class).stream().anyMatch(f -> f.getBounds()
                    .intersects(around))) {
                continue;
            }

            final Point2D center = glyph.getCenter2D();
            final Staff staff = system.getClosestStaff(center);

            if (staff == null) {
                continue;
            }

            // Above its staff, the arc opens down; below, it opens up
            final Shape shape = (center.getY() < staff.getMidLine().yAt(center.getX()))
                    ? Shape.FERMATA
                    : Shape.FERMATA_BELOW;
            final Inter fermata = factory.create(
                    new Evaluation(shape, constants.drawnFermataGrade.getValue()),
                    glyph,
                    staff);

            if (fermata != null) {
                logger.debug("{} read from the PDF", fermata);
            }
        }
    }

    //--------------------//
    // checkDrawnDynamics //
    //--------------------//
    /**
     * Check the dynamics against those a PDF draws letter by letter: a dynamic read where none
     * is drawn goes, one read as another, a "pp" for a "p", say, is read as the drawn one, and
     * one drawn but not read is proposed. Nothing is done where the PDF draws no dynamic this
     * way, since it may draw them with a text font.
     */
    private void checkDrawnDynamics ()
    {
        final Map<Glyph, String> words = VectorChecker.drawnDynamics(system);

        if (words.isEmpty()) {
            return;
        }

        final int margin = sheet.getInterline() / 4;
        final Set<Glyph> read = new HashSet<>();

        for (Inter inter : system.getSig().inters(DynamicsInter.class)) {
            final Rectangle box = inter.getBounds();
            box.grow(margin, margin);

            final Glyph word = words.keySet().stream().filter(g -> g.getBounds().intersects(box))
                    .findFirst().orElse(null);

            if (word == null) {
                logger.debug("{} not drawn by the PDF", inter);
                inter.remove();
                continue;
            }

            read.add(word);

            final Shape drawn = dynamicsShape(words.get(word));

            if ((drawn != null) && (drawn != inter.getShape()) && (inter.getGlyph() != null)) {
                logger.debug("{} drawn as {}", inter, drawn);
                inter.remove();
                createDynamics(drawn, inter.getGlyph());
            }
        }

        // A drawn one not read is proposed, unless its pixels touch a note: they would take
        // part of the head or stem, and the dynamic could exclude it
        final List<Inter> notes = system.getSig().inters(HeadInter.class);
        notes.addAll(system.getSig().inters(StemInter.class));

        for (Map.Entry<Glyph, String> entry : words.entrySet()) {
            final Shape drawn = dynamicsShape(entry.getValue());
            final Rectangle box = entry.getKey().getBounds();

            if (!read.contains(entry.getKey()) && (drawn != null) && notes.stream().noneMatch(
                    n -> n.getBounds().intersects(box))) {
                createDynamics(drawn, entry.getKey());
            }
        }
    }

    private void createDynamics (Shape shape,
                                 Glyph glyph)
    {
        final Staff staff = system.getClosestStaff(glyph.getCenter2D());

        if (staff != null) {
            final Inter dynamics = factory.create(
                    new Evaluation(shape, constants.drawnDynamicsGrade.getValue()),
                    glyph,
                    staff);
            logger.debug("{} read from the PDF", dynamics);
        }
    }

    /** The shape of a drawn dynamic, from its letters, or null if not one we handle. */
    private static Shape dynamicsShape (String letters)
    {
        try {
            return Shape.valueOf("DYNAMICS_" + letters.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    //-------------------//
    // addDrawnOrnaments //
    //-------------------//
    /**
     * Propose the ornaments a PDF draws where none was read: the small sign of a trill or a
     * mordent above a note is often missed, and the note is played plain. A sign already read
     * there is confirmed.
     */
    private void addDrawnOrnaments ()
    {
        final int margin = sheet.getInterline() / 2;

        for (Map.Entry<Glyph, VectorSymbols.Kind> entry : VectorChecker.drawnOrnaments(system)
                .entrySet()) {
            final Glyph glyph = entry.getKey();
            final Rectangle around = glyph.getBounds();
            around.grow(margin, margin);

            final List<Inter> there = system.getSig().inters(OrnamentInter.class).stream()
                    .filter(o -> o.getBounds().intersects(around)).toList();

            if (!there.isEmpty()) {
                for (Inter o : there) {
                    o.setGrade(Math.max(o.getGrade(), constants.drawnOrnamentGrade.getValue()));
                }

                continue;
            }

            final Staff staff = system.getClosestStaff(glyph.getCenter2D());

            if (staff == null) {
                continue;
            }

            final Shape shape = VectorChecker.ornamentShape(entry.getValue());
            final Inter ornament = factory.create(
                    new Evaluation(shape, constants.drawnOrnamentGrade.getValue()),
                    glyph,
                    staff);

            if (ornament != null) {
                logger.debug("{} read from the PDF", ornament);
            }
        }
    }

    //-----------//
    // isFlagged //
    //-----------//
    /**
     * Whether the classifier proposes a flag for a glyph with some confidence, or a flag is
     * already there.
     *
     * @param glyph the glyph to check
     * @param evals the classifier evaluations of the glyph
     * @return true if so
     */
    private boolean isFlagged (Glyph glyph,
                               Evaluation[] evals)
    {
        for (Evaluation eval : evals) {
            if ((ShapeSet.FlagsUp.contains(eval.shape) || ShapeSet.FlagsDown.contains(eval.shape))
                    && (eval.grade >= constants.minFlagGrade.getValue())) {
                return true;
            }
        }

        final Rectangle box = glyph.getBounds();

        for (Inter flag : system.getSig().inters(FlagInter.class)) {
            if (flag.getBounds().intersects(box)) {
                return true;
            }
        }

        return false;
    }

    //-----------------//
    // drawnClefChange //
    //-----------------//
    /**
     * Report the clef change that the PDF draws over a glyph within a staff, past its header,
     * if the classifier proposes no clef of this shape.
     *
     * @param glyph the glyph to check
     * @param staff the closest staff
     * @param evals the classifier evaluations of the glyph
     * @return the clef shape, or null
     */
    private Shape drawnClefChange (Glyph glyph,
                                   Staff staff,
                                   Evaluation[] evals)
    {
        if (glyph.getLeft() <= staff.getHeaderStop()) {
            return null;
        }

        final Shape shape = VectorChecker.drawnClefChange(glyph, system);

        if (shape == null) {
            return null;
        }

        for (Evaluation eval : evals) {
            if ((eval.shape == shape) || (eval.shape == Shape.G_CLEF && shape == Shape.G_CLEF_SMALL)
                    || (eval.shape == Shape.F_CLEF && shape == Shape.F_CLEF_SMALL)) {
                return null;
            }
        }

        return shape;
    }

    //--------------------//
    // isTrebleClefChange //
    //--------------------//
    /**
     * Tell whether a glyph is the small treble clef of a clef change within a staff.
     * <p>
     * The classifier hardly knows this shape: it scores it too low to be kept, often below 0.1,
     * though G_CLEF is its first choice. Besides that choice, the glyph must have the dimensions
     * of such a clef and stand where it does, from a bit above the staff to a bit below it.
     *
     * @param glyph the glyph to check
     * @param staff the closest staff
     * @return true if so
     */
    private boolean isTrebleClefChange (Glyph glyph,
                                        Staff staff)
    {
        final Rectangle box = glyph.getBounds();
        final double interline = sheet.getInterline();
        final double width = box.width / interline;
        final double height = box.height / interline;

        if ((width < constants.minClefChangeWidth.getValue())
                || (width > constants.maxClefChangeWidth.getValue())
                || (height < constants.minClefChangeHeight.getValue())
                || (height > constants.maxClefChangeHeight.getValue())) {
            return false;
        }

        final double above = (staff.getFirstLine().yAt(box.x) - box.y) / interline;
        final double below = ((box.y + box.height) - staff.getLastLine().yAt(box.x)) / interline;

        if ((above < 0) || (above > constants.maxClefChangeMargin.getValue()) || (below < 0)
                || (below > constants.maxClefChangeMargin.getValue())) {
            return false;
        }

        final Evaluation[] top = classifier.evaluate(
                glyph,
                system,
                1,
                0.0,
                EnumSet.noneOf(Classifier.Condition.class));

        return (top.length > 0) && (top[0].shape == Shape.G_CLEF);
    }

    //------------------//
    // getSymbolsGlyphs //
    //------------------//
    /**
     * Report the collection of glyphs as symbols candidates.
     * <p>
     * Using a too low weight threshold would result in explosion of symbols (and inter-symbol
     * relations), which would be a disaster in terms of resources. However, we need really fine
     * glyphs to detect small flags (slashed or not).
     * <p>
     * So, we define a list of "fine boxes" at system level, which represents the boxes of system
     * small head-chords. And we use a different threshold depending on whether a glyph candidate
     * intersects or not a fine box.
     *
     * @return the candidates (ordered by abscissa)
     */
    private List<Glyph> getSymbolsGlyphs (Map<SystemInfo, List<Glyph>> optionalsMap)
    {
        // Sorted by geometry (see below)
        List<Glyph> glyphs = new ArrayList<>();

        for (Glyph glyph : system.getGroupedGlyphs(GlyphGroup.SYMBOL)) {
            final int weight = glyph.getWeight();

            if (weight >= params.minWeight) {
                glyphs.add(glyph);
            } else if ((weight >= params.minFineWeight) && hitFineBox(glyph)) {
                glyphs.add(glyph);
            }
        }

        // Include optional glyphs as well
        List<Glyph> optionals = optionalsMap.get(system);

        if ((optionals != null) && !optionals.isEmpty()) {
            for (Glyph glyph : optionals) {
                final int weight = glyph.getWeight();

                if (weight >= params.minWeight) {
                    glyphs.add(glyph);
                } else if ((weight >= params.minFineWeight) && hitFineBox(glyph)) {
                    glyphs.add(glyph);
                }
            }
        }

        // Free glyphs come in registration order, which may vary from run to run
        Collections.sort(glyphs, Glyphs.byGeometry);

        return glyphs;
    }

    //------------//
    // hitFineBox //
    //------------//
    /**
     * Check whether the provided glyph intersects a fine box.
     *
     * @param glyph the glyph to check
     * @return true if in fine area
     */
    private boolean hitFineBox (Glyph glyph)
    {
        final Rectangle glyphBounds = glyph.getBounds();

        for (Rectangle box : fineBoxes) {
            if (box.intersects(glyphBounds)) {
                return true;
            }
        }

        return false;
    }

    //-----------------//
    // processClusters //
    //-----------------//
    /**
     * Process all clusters of connected glyphs, based on the glyphs graph.
     *
     * @param systemGraph the graph of candidate glyphs, with their mutual distances
     */
    private void processClusters (SimpleGraph<Glyph, GlyphLink> systemGraph)
    {
        // Retrieve all the clusters of glyphs (sets of connected glyphs)
        final ConnectivityInspector<Glyph, GlyphLink> inspector = new ConnectivityInspector<>(
                systemGraph);
        final List<Set<Glyph>> sets = inspector.connectedSets();
        logger.debug("symbols sets: {}", sets.size());

        final int interline = sheet.getInterline();
        final int maxPartCount = constants.maxPartCount.getValue();

        for (Set<Glyph> set : sets) {
            final int setSize = set.size();
            logger.debug("set size: {}", setSize);

            if (setSize > 1) {
                final Set<Glyph> subSet; // Use an upper limit for set size

                if (setSize <= maxPartCount) {
                    subSet = set;
                } else {
                    List<Glyph> list = new ArrayList<>(set);
                    Collections.sort(list, Glyphs.byReverseWeight);
                    list = list.subList(0, Math.min(list.size(), maxPartCount));
                    subSet = new LinkedHashSet<>(list);
                    logger.debug("Symbol parts shrunk from {} to {}", setSize, maxPartCount);
                }

                // Use just the subgraph for this (sub)set
                final SimpleGraph<Glyph, GlyphLink> subGraph;
                subGraph = GlyphCluster.getSubGraph(subSet, systemGraph, true);
                new GlyphCluster(new SymbolAdapter(subGraph), GlyphGroup.SYMBOL).decompose();
            } else {
                // The set is just an isolated glyph, to be evaluated directly
                final Glyph glyph = set.iterator().next();

                if (classifier.isBigEnough(glyph, interline)) {
                    evaluateGlyph(glyph);
                }
            }
        }
    }

    //-------------------//
    // retrieveFineBoxes //
    //-------------------//
    /**
     * Define a fine box on the right side of every small chord, specifically meant for
     * detecting a potential small flag there.
     */
    private void retrieveFineBoxes ()
    {
        final List<Inter> smallChords = system.getSig().inters(SmallChordInter.class);

        for (Inter inter : smallChords) {
            final Rectangle box = inter.getBounds();
            final Rectangle fineBox = new Rectangle(
                    box.x + box.width,
                    box.y,
                    params.smallChordMargin,
                    box.height);
            fineBoxes.add(fineBox);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print out the stop watch?");

        private final Constant.Integer maxPartCount = new Constant.Integer(
                "Glyphs",
                7,
                "Maximum number of parts considered for a symbol");

        private final Scale.Fraction minClefChangeWidth = new Scale.Fraction(
                1.5,
                "Minimum width of a small treble clef");

        private final Scale.Fraction maxClefChangeWidth = new Scale.Fraction(
                2.4,
                "Maximum width of a small treble clef");

        private final Scale.Fraction minClefChangeHeight = new Scale.Fraction(
                4.8,
                "Minimum height of a small treble clef");

        private final Scale.Fraction maxClefChangeHeight = new Scale.Fraction(
                6.2,
                "Maximum height of a small treble clef");

        private final Scale.Fraction maxClefChangeMargin = new Scale.Fraction(
                1.5,
                "Maximum extent of a small treble clef above or below its staff");

        private final Constant.Ratio drawnTupletGrade = new Constant.Ratio(
                0.8,
                "Grade given to a tuplet number that the PDF draws");

        private final Constant.Ratio drawnOrnamentGrade = new Constant.Ratio(
                0.8,
                "Grade given to an ornament that the PDF draws");

        private final Constant.Ratio drawnPedalGrade = new Constant.Ratio(
                0.8,
                "Grade given to a pedal mark that the PDF draws");

        private final Constant.Ratio drawnRestGrade = new Constant.Ratio(
                0.8,
                "Grade given to a rest that the PDF draws");

        private final Constant.Ratio drawnFermataGrade = new Constant.Ratio(
                0.8,
                "Grade given to a fermata that the PDF draws");

        private final Constant.Ratio drawnDynamicsGrade = new Constant.Ratio(
                0.8,
                "Grade given to a dynamic that the PDF draws");

        private final Constant.Ratio minFlagGrade = new Constant.Ratio(
                0.5,
                "Classifier grade from which a flag proposed for a glyph needs no help from the PDF");

        private final Constant.Ratio drawnFlagGrade = new Constant.Ratio(
                0.8,
                "Grade given to a flag that the PDF draws");

        private final Constant.Ratio drawnTimeGrade = new Constant.Ratio(
                0.9,
                "Grade given to a time signature that the PDF draws");

        private final Constant.Ratio clefChangeGrade = new Constant.Ratio(
                0.8,
                "Grade given to a small treble clef recognized from its shape");

        private final Constant.Integer maxEvaluationCount = new Constant.Integer(
                "Evaluations",
                2,
                "Maximum number of evaluations kept for a symbol");

        private final Scale.Fraction maxGap = new Scale.Fraction(
                0.8,
                "Maximum distance between two compound parts");

        private final Scale.AreaFraction minWeight = new Scale.AreaFraction(
                0.03,
                "Minimum weight for glyph consideration");

        private final Scale.AreaFraction minFineWeight = new Scale.AreaFraction(
                0.006,
                "Minimum weight for glyph consideration in a fine area");

        private final Scale.Fraction smallChordMargin = new Scale.Fraction(
                1,
                "Margin on right side of small chords to extend fine boxes");

        private final Scale.Fraction maxSymbolWidth = new Scale.Fraction(
                4.0,
                "Maximum width for a symbol");

        private final Scale.Fraction maxSymbolHeight = new Scale.Fraction(
                10.0,
                "Maximum height for a symbol (when found within staff abscissa range)");
    }

    //------------//
    // Parameters //
    //------------//
    /**
     * Class <code>Parameters</code> gathers all pre-scaled constants.
     */
    private static class Parameters
    {
        final double maxGap;

        final int maxSymbolWidth;

        final int maxSymbolHeight;

        final int smallChordMargin;

        final int minWeight;

        final int minFineWeight;

        Parameters (Scale scale)
        {
            maxGap = scale.toPixelsDouble(constants.maxGap);
            maxSymbolWidth = scale.toPixels(constants.maxSymbolWidth);
            maxSymbolHeight = scale.toPixels(constants.maxSymbolHeight);
            smallChordMargin = scale.toPixels(constants.smallChordMargin);
            minWeight = scale.toPixels(constants.minWeight);
            minFineWeight = scale.toPixels(constants.minFineWeight);

            if (logger.isDebugEnabled()) {
                new Dumping().dump(this);
            }
        }
    }

    //---------------//
    // SymbolAdapter //
    //---------------//
    private class SymbolAdapter
            extends GlyphCluster.AbstractAdapter
    {
        private final Scale scale = sheet.getScale();

        SymbolAdapter (SimpleGraph<Glyph, GlyphLink> graph)
        {
            super(graph);
        }

        @Override
        public void evaluateGlyph (Glyph glyph,
                                   Set<Glyph> parts)
        {
            SymbolsBuilder.this.evaluateGlyph(glyph);
        }

        @Override
        public boolean isTooLarge (Rectangle symBox)
        {
            // Check width
            if (symBox.width > params.maxSymbolWidth) {
                return true;
            }

            // Check height (not limited if on left of system: braces / brackets)
            if (GeoUtil.center2D(symBox).getX() < system.getLeft()) {
                return false;
            } else {
                return symBox.height > params.maxSymbolHeight;
            }
        }

        @Override
        public boolean isTooLight (int weight)
        {
            double normed = scale.pixelsToAreaFrac(weight);

            return !classifier.isBigEnough(normed);
        }
    }
}
