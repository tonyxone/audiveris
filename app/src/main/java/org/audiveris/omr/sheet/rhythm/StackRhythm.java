//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      S t a c k R h y t h m                                     //
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
package org.audiveris.omr.sheet.rhythm;

import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.math.Rational;
import org.audiveris.omr.score.TimeRational;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractTimeInter;
import org.audiveris.omr.sig.inter.BeamGroupInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.TupletInter;
import org.audiveris.omr.sig.inter.WordInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * Class <code>StackRhythm</code> computes the rhythm content of a given MeasureStack.
 * <p>
 * This class builds the time slots and voices that result from stack content, and checks for the
 * "time correctness" of the stack.
 *
 * @author Hervé Bitteur
 */
public class StackRhythm
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(StackRhythm.class);

    /**
     * Alternative voice penalties {noLink, stemDiffTwo, staffDiff}, tried in turn on a stack
     * whose rhythm is not correct with the standard ones (60, 6, 20).
     * They favor voices following stem direction, or staying in their staff, or new voices.
     */
    private static final int[][] VOICE_TUNINGS = {
            { 60, 40, 20 },
            { 60, 6, 60 },
            { 60, 40, 60 },
            { 30, 6, 20 },
            { 100, 6, 20 } };

    //~ Instance fields ----------------------------------------------------------------------------

    /** The dedicated measure stack. */
    private final MeasureStack stack;

    /** Measures to read without implicit tuplets: they came out short with them. */
    private final Set<Measure> noImplicit = new LinkedHashSet<>();

    /** Measures to read with beam groups as implicit tuplets: they came out long without. */
    private final Map<Measure, List<List<AbstractChordInter>>> forced = new LinkedHashMap<>();

    /** The time in force, as the page reading found it, if known. */
    private TimeRational time;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>StackRhythm</code> object.
     *
     * @param stack the measure stack to process
     */
    public StackRhythm (MeasureStack stack)
    {
        this.stack = stack;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //----------------------//
    // checkStavesAreFilled //
    //----------------------//
    /**
     * Except in "merged grand staff" case, no staff should be left empty.
     *
     * @return true if OK
     */
    private boolean checkStavesAreFilled ()
    {
        // Use a temporary map: (staff -> chords)
        Map<Staff, List<AbstractChordInter>> map = new LinkedHashMap<>();

        for (Staff staff : stack.getSystem().getStaves()) {
            if (!staff.isTablature()) {
                map.put(staff, new ArrayList<>());
            }
        }

        // Populate map of staves with installed chords
        for (Measure measure : stack.getMeasures()) {
            for (AbstractChordInter chord : measure.getStandardChords()) {
                for (Staff staff : chord.getStaves()) {
                    map.get(staff).add(chord);
                }
            }
        }

        // Look for empty staves
        for (Map.Entry<Staff, List<AbstractChordInter>> entry : map.entrySet()) {
            if (entry.getValue().isEmpty()) {
                logger.info("{} staff#{} is empty", stack, entry.getKey().getId());

                return false;
            }
        }

        return true;
    }

    //-----------//
    // doProcess //
    //-----------//
    /**
     * Try to process stack rhythm.
     *
     * @return OK if successful
     */
    private boolean doProcess ()
    {
        int best = doProcess(null);

        if (best == 0) {
            return true;
        }

        // Some measure is not correct, try other voice penalties
        int[] bestTuning = null;
        int[] lastTuning = null;

        for (int[] tuning : VOICE_TUNINGS) {
            final int count = doProcess(tuning);
            lastTuning = tuning;

            if (count < best) {
                best = count;
                bestTuning = tuning;

                if (count == 0) {
                    break;
                }
            }
        }

        if (lastTuning != bestTuning) {
            doProcess(bestTuning); // Restore the best attempt
        }

        if (bestTuning != null) {
            logger.debug(
                    "{} rhythm improved by voice tuning {}",
                    stack,
                    Arrays.toString(bestTuning));
        }

        return best == 0;
    }

    /**
     * Process the stack rhythm with the provided voice penalties.
     *
     * @param tuning the specific voice penalties, or null for the standard ones
     * @return the number of abnormal measures
     */
    private int doProcess (int[] tuning)
    {
        stack.resetRhythm();

        // Process measure by measure
        for (Measure measure : stack.getMeasures()) {
            final boolean measureOk = new MeasureRhythm(
                    measure,
                    tuning,
                    !noImplicit.contains(measure),
                    forced.getOrDefault(measure, Collections.emptyList())).process();

            if (!measureOk) {
                measure.setAbnormal(true);
            }
        }

        generateStackSlots(); // Gather all chords into stack slots

        readStackActualDuration(); // Read stack actual duration

        int abnormals = 0;

        for (Measure measure : stack.getMeasures()) {
            if (measure.isAbnormal()) {
                abnormals++;
            }
        }

        return abnormals;
    }

    //--------------------//
    // generateStackSlots //
    //--------------------//
    /**
     * Using the voices detected in each measure of the stack, dispatch all chords in
     * separate stack-level slots according to their time value.
     */
    private void generateStackSlots ()
    {
        stack.getSlots().clear();

        int slotCount = 0;
        final TreeMap<Rational, List<AbstractChordInter>> times = new TreeMap<>();

        for (Measure measure : stack.getMeasures()) {
            for (Voice voice : measure.getVoices()) {
                if (!voice.isMeasureRest()) {
                    for (AbstractChordInter chord : voice.getChords()) {
                        Rational time = chord.getTimeOffset();

                        if (time != null) {
                            List<AbstractChordInter> list = times.get(time);

                            if (list == null) {
                                times.put(time, list = new ArrayList<>());
                            }

                            list.add(chord);
                        } else {
                            measure.setAbnormal(true);
                        }
                    }
                }
            }
        }

        TreeMap<Rational, Slot> timeToSlot = new TreeMap<>();

        for (Entry<Rational, List<AbstractChordInter>> entry : times.entrySet()) {
            Slot slot = new Slot(++slotCount, stack, entry.getValue());
            slot.setTimeOffset(entry.getKey());
            timeToSlot.put(entry.getKey(), slot);
            stack.getSlots().add(slot);
        }

        for (Measure measure : stack.getMeasures()) {
            for (Voice voice : measure.getVoices()) {
                if (!voice.isMeasureRest()) {
                    for (AbstractChordInter chord : voice.getChords()) {
                        Rational time = chord.getTimeOffset();

                        if (time != null) {
                            Slot slot = timeToSlot.get(time);
                            voice.putSlotInfo(
                                    slot,
                                    new SlotVoice(chord, SlotVoice.ChordStatus.BEGIN));
                        } else {
                            measure.setAbnormal(true);
                        }
                    }

                    voice.completeSlotTable();
                }
            }
        }
    }

    //---------//
    // process //
    //---------//
    /**
     * Determine stack rhythm data (voices and timing), knowing the time in force.
     * <p>
     * A page with no time signature of its own reads its stacks with the time of the pages
     * before, which the stack cannot find by itself.
     *
     * @param expectedDuration The expected duration for this stack, null if not known
     * @param time             The time in force, null if not known
     */
    public void process (Rational expectedDuration,
                         TimeRational time)
    {
        this.time = time;
        process(expectedDuration);
    }

    //---------//
    // process //
    //---------//
    /**
     * Determine stack rhythm data (voices and timing).
     *
     * @param expectedDuration The expected duration for this stack, null if not known
     */
    public void process (Rational expectedDuration)
    {
        stack.setExpectedDuration(expectedDuration);

        try {
            boolean ok = doProcess();

            if (dropBeatTuplets()) {
                ok = doProcess();
            }

            if (dropUnfitTuplets()) {
                ok = doProcess();
            }

            if (addMissedTuplets()) {
                ok = doProcess();

                if (dropUnfitForced()) {
                    ok = doProcess();
                }
            }

            if (!ok) {
                logger.info("{}{} no correct rhythm", stack.getSystem().getLogPrefix(), stack);
            }
        } catch (Exception ex) {
            logger.warn("StackRhythm error processing {} {}", stack, ex.toString(), ex);
        }
    }

    //------------------//
    // addMissedTuplets //
    //------------------//
    /**
     * Read as tuplets the beam groups of 3 or 6 plain notes of a measure that comes out long.
     * <p>
     * A triplet or sextuplet sign is often printed on the first groups only, and an implicit
     * tuplet is inferred only where it removes exactly the excess of a voice. When the
     * reading splits a line of triplets between voices, as the left hand of Debussy's first
     * Arabesque does at a clef change, no voice gets them, and the bar comes out long.
     * Every such group of the measure is read as a tuplet, unless it makes a beat of a
     * compound time such as 6/8 (three eighths, six sixteenths), and kept so only where the
     * measure then fits its time. Three sixteenths make half a beat of 6/8, so they are a
     * triplet, as in La Campanella, where the sign is printed in the first bar only.
     * <p>
     * Conversely, a measure of a compound time that comes out short may hold duplets: two
     * eighths in the time of three in 9/8, as Debussy writes in Clair de lune. Audiveris has
     * no duplet sign to recognize, but reads its "2" as a word: a pair that would make a
     * beat as a duplet and has such a "2" by it is read as one, kept only where the measure
     * then fits its time.
     *
     * @return true if some measure got tuplets, and the stack must be read again
     */
    private boolean addMissedTuplets ()
    {
        final Rational expected = stack.getExpectedDuration();

        final TimeRational current = currentTime();

        if ((expected == null) || stack.isImplicit() || (current == null)) {
            return false;
        }

        // In a compound time, a group that makes a beat is a plain beat
        final boolean compound = isCompound(current);
        final Rational beat = compound ? new Rational(3, current.den) : null;

        final List<Rectangle> twos = compound ? dupletSigns() : Collections.emptyList();
        boolean added = false;

        for (Measure measure : stack.getMeasures()) {
            if (compound && isShort(measure, expected)) {
                final List<List<AbstractChordInter>> pairs = plainPairs(measure, beat, twos);

                if (!pairs.isEmpty()) {
                    forced.put(measure, pairs);
                    added = true;
                }

                continue;
            }

            if (!isLong(measure, expected)) {
                continue;
            }

            final List<List<AbstractChordInter>> groups = new ArrayList<>();

            for (List<AbstractChordInter> group : plainGroups(measure)) {
                final Rational total = group.get(0).getDurationSansTuplet().times(group.size());

                if (!total.equals(beat)) {
                    groups.add(group);
                }
            }

            if (!groups.isEmpty()) {
                forced.put(measure, groups);
                added = true;
            }
        }

        return added;
    }

    //-----------------//
    // dropUnfitForced //
    //-----------------//
    /**
     * Give up the tuplets added to a measure that still does not fit its time with them.
     *
     * @return true if some measure lost them, and the stack must be read again
     */
    private boolean dropUnfitForced ()
    {
        final Rational expected = stack.getExpectedDuration();
        boolean dropped = false;

        for (Measure measure : new ArrayList<>(forced.keySet())) {
            if (fits(measure, expected)) {
                logger.info("{} off its time without tuplets, read with {}", measure, forced.get(measure)
                        .size());
            } else {
                forced.remove(measure);
                dropped = true;
            }
        }

        return dropped;
    }

    //------//
    // fits //
    //------//
    /**
     * Whether every chord of a measure is timed, every voice ends on or before its expected
     * duration and one on it.
     */
    private static boolean fits (Measure measure,
                                 Rational expected)
    {
        for (AbstractChordInter chord : measure.getStandardChords()) {
            if ((chord.getVoice() == null) || (chord.getTimeOffset() == null)) {
                return false;
            }
        }

        Rational longest = null;

        for (Voice voice : measure.getVoices()) {
            if (voice.isMeasureRest()) {
                continue;
            }

            final AbstractChordInter last = voice.getLastChord();
            final Rational end = (last != null) ? last.getEndTime() : null;

            if ((end == null) || (end.compareTo(expected) > 0)) {
                return false;
            }

            if ((longest == null) || (end.compareTo(longest) > 0)) {
                longest = end;
            }
        }

        return expected.equals(longest);
    }

    //-------------//
    // currentTime //
    //-------------//
    /**
     * The time of the stack, as inferred, or else as given by the page reading or by a time
     * signature earlier in the page, if any.
     */
    private TimeRational currentTime ()
    {
        if (stack.getInferredTime() != null) {
            return stack.getInferredTime();
        }

        if (time != null) {
            return time;
        }

        final AbstractTimeInter ts = stack.getCurrentTimeSignature();

        return (ts != null) ? ts.getTimeRational() : null;
    }

    //--------//
    // isLong //
    //--------//
    /**
     * Whether some voice of a measure ends after its expected duration.
     */
    private static boolean isLong (Measure measure,
                                   Rational expected)
    {
        for (Voice voice : measure.getVoices()) {
            final AbstractChordInter last = voice.getLastChord();
            final Rational end = (last != null) ? last.getEndTime() : null;

            if ((end != null) && (end.compareTo(expected) > 0)) {
                return true;
            }
        }

        return false;
    }

    //------------//
    // plainPairs //
    //------------//
    /**
     * The pairs of chords of a measure that could be unmarked duplets: 2 chords of equal
     * duration, without a tuplet sign, that make a beat as a duplet. A pair is either a beam
     * group of 2, or 2 unbeamed chords (a rest and a flagged eighth, say) that follow each
     * other in a voice from the start of a beat.
     * <p>
     * A "2" must be printed by the pair, or by a pair that starts at the same time: both
     * hands play the duplet, and the "2" of one of them may not be read.
     */
    private List<List<AbstractChordInter>> plainPairs (Measure measure,
                                                       Rational beat,
                                                       List<Rectangle> twos)
    {
        final Set<AbstractChordInter> chords = measure.getStandardChords();
        final List<List<AbstractChordInter>> candidates = new ArrayList<>();

        for (BeamGroupInter bg : measure.getBeamGroups()) {
            final List<AbstractChordInter> pair = new ArrayList<>(bg.getChords());

            if ((pair.size() == 2) && isDuplet(pair, chords, beat)) {
                Collections.sort(pair, Inters.byCenterAbscissa);
                candidates.add(pair);
            }
        }

        for (Voice voice : measure.getVoices()) {
            final List<AbstractChordInter> seq = voice.getChords();

            for (int i = 0; i < (seq.size() - 1); i++) {
                final List<AbstractChordInter> pair = List.of(seq.get(i), seq.get(i + 1));
                final Rational start = pair.get(0).getTimeOffset();

                if ((pair.get(0).getBeamGroup() == null) && (pair.get(1).getBeamGroup() == null)
                        && (start != null) && (start.divides(beat).den == 1)
                        && isDuplet(pair, chords, beat)) {
                    candidates.add(pair);
                    i++;
                }
            }
        }

        final Set<Rational> markedStarts = new LinkedHashSet<>();

        for (List<AbstractChordInter> pair : candidates) {
            if (isMarked(pair, twos)) {
                markedStarts.add(pair.get(0).getTimeOffset());
            }
        }

        final List<List<AbstractChordInter>> pairs = new ArrayList<>();

        for (List<AbstractChordInter> pair : candidates) {
            if (markedStarts.contains(pair.get(0).getTimeOffset())) {
                pairs.add(pair);
            }
        }

        return pairs;
    }

    //-------------//
    // dupletSigns //
    //-------------//
    /**
     * The bounds of the words "2" of the system, the duplet signs it may hold.
     */
    private List<Rectangle> dupletSigns ()
    {
        final List<Rectangle> boxes = new ArrayList<>();

        for (Inter inter : stack.getSystem().getSig().inters(WordInter.class)) {
            if ("2".equals(((WordInter) inter).getValue())) {
                boxes.add(inter.getBounds());
            }
        }

        return boxes;
    }

    //----------//
    // isMarked //
    //----------//
    /**
     * Whether a "2" is printed by a pair of chords: over its span, at most two interlines
     * above or below it.
     */
    private boolean isMarked (List<AbstractChordInter> pair,
                              List<Rectangle> twos)
    {
        final int interline = stack.getSystem().getSheet().getScale().getInterline();
        final Rectangle box = pair.get(0).getBounds().union(pair.get(1).getBounds());
        box.grow(interline, 2 * interline);

        return twos.stream().anyMatch(box::intersects);
    }

    //----------//
    // isDuplet //
    //----------//
    /**
     * Whether 2 chords of the measure, of equal duration and without a tuplet sign, would
     * make a beat as a duplet: two notes in the time of three.
     */
    private static boolean isDuplet (List<AbstractChordInter> pair,
                                     Set<AbstractChordInter> chords,
                                     Rational beat)
    {
        Rational dur = null;

        for (AbstractChordInter chord : pair) {
            final TupletInter tuplet = chord.getTuplet();
            final Rational d = chord.getDurationSansTuplet();

            if (!chords.contains(chord) || ((tuplet != null) && !tuplet.isImplicit())
                    || (d == null) || ((dur != null) && !dur.equals(d))) {
                return false;
            }

            dur = d;
        }

        return dur.times(3).equals(beat);
    }

    //-------------//
    // plainGroups //
    //-------------//
    /**
     * The beam groups of a measure that could be unmarked tuplets: 3 or 6 chords, all in
     * the measure, of equal duration and without a tuplet sign.
     */
    private static List<List<AbstractChordInter>> plainGroups (Measure measure)
    {
        final Set<AbstractChordInter> chords = measure.getStandardChords();
        final List<List<AbstractChordInter>> groups = new ArrayList<>();

        for (BeamGroupInter bg : measure.getBeamGroups()) {
            final List<AbstractChordInter> group = new ArrayList<>(bg.getChords());

            if ((group.size() != 3) && (group.size() != 6)) {
                continue;
            }

            Rational dur = null;
            boolean plain = true;

            for (AbstractChordInter chord : group) {
                final TupletInter tuplet = chord.getTuplet();
                final Rational d = chord.getDurationSansTuplet();

                if (!chords.contains(chord) || ((tuplet != null) && !tuplet.isImplicit())
                        || (d == null) || ((dur != null) && !dur.equals(d))) {
                    plain = false;
                    break;
                }

                dur = d;
            }

            if (plain) {
                Collections.sort(group, Inters.byCenterAbscissa);
                groups.add(group);
            }
        }

        return groups;
    }

    //-----------------//
    // dropBeatTuplets //
    //-----------------//
    /**
     * Drop the triplet signs of a measure that comes out short, where the notes they embrace
     * make a beat of a compound time.
     * <p>
     * Three eighths make a beat of 12/8 with no sign: a "3" over them is a fingering, such as
     * the one in the first bar of Chopin's Nocturne op. 9 no. 2.
     *
     * @return true if some measure lost tuplets, and the stack must be read again
     */
    private boolean dropBeatTuplets ()
    {
        final Rational expected = stack.getExpectedDuration();
        final TimeRational current = currentTime();

        if ((expected == null) || stack.isImplicit() || (current == null) || !isCompound(
                current)) {
            return false;
        }

        final Rational beat = new Rational(3, current.den);
        boolean dropped = false;

        for (Measure measure : stack.getMeasures()) {
            if (!isShort(measure, expected)) {
                continue;
            }

            for (TupletInter tuplet : new ArrayList<>(measure.getTuplets())) {
                if (tuplet.isImplicit() || tuplet.isManual()
                        || (tuplet.getShape() != Shape.TUPLET_THREE)) {
                    continue;
                }

                Rational total = Rational.ZERO;

                for (AbstractChordInter chord : tuplet.getChords()) {
                    final Rational d = chord.getDurationSansTuplet();
                    total = (d != null) ? total.plus(d) : Rational.MAX_VALUE;
                }

                if (total.equals(beat)) {
                    logger.info("{} short with {} over a beat, read without it", measure, tuplet);
                    tuplet.remove();
                    dropped = true;
                }
            }
        }

        return dropped;
    }

    //------------------//
    // dropUnfitTuplets //
    //------------------//
    /**
     * Drop the tuplets of a measure that comes out short with them and fits the bar
     * without them.
     * <p>
     * A bar shorter than its time is not flagged, so tuplets that are not there go
     * unnoticed: the implicit triplets inferred over a dotted eighth and three sixteenths of
     * Für Elise, or a fingering "3" read as a triplet, each took a third off their notes.
     * When every voice then ends on the bar once the tuplets are dropped, the implicit ones
     * are not inferred again, and if that is not enough the tuplet signs go too.
     *
     * @return true if some measure lost tuplets, and the stack must be read again
     */
    private boolean dropUnfitTuplets ()
    {
        final Rational expected = stack.getExpectedDuration();

        if ((expected == null) || stack.isImplicit()) {
            return false;
        }

        boolean dropped = false;

        for (Measure measure : stack.getMeasures()) {
            final Set<TupletInter> tuplets = measure.getTuplets();

            if (tuplets.isEmpty() || !isShort(measure, expected)) {
                continue;
            }

            final List<TupletInter> implicits = new ArrayList<>();

            for (TupletInter tuplet : tuplets) {
                if (tuplet.isImplicit()) {
                    implicits.add(tuplet);
                }
            }

            if (!implicits.isEmpty() && fitsWithout(measure, implicits, expected)) {
                logger.info("{} short with implicit tuplets, read without them", measure);
                noImplicit.add(measure);
                dropped = true;
            } else if (fitsWithout(measure, tuplets, expected)) {
                logger.info("{} short with tuplets {}, read without them", measure, tuplets);
                noImplicit.add(measure);

                for (TupletInter tuplet : new ArrayList<>(tuplets)) {
                    if (!tuplet.isImplicit()) {
                        tuplet.remove();
                    }
                }

                dropped = true;
            }
        }

        return dropped;
    }

    //-------------//
    // fitsWithout //
    //-------------//
    /**
     * Whether, with the provided tuplets dropped, every voice of a measure ends on or before
     * its expected duration and one on it, and chords of a slot still start together.
     * <p>
     * A voice that starts within the measure starts with the chords of another voice in its
     * first slot, which the dropped tuplets may move: a left hand voice starting under a
     * right hand note moves with it.
     */
    private static boolean fitsWithout (Measure measure,
                                        Collection<TupletInter> tuplets,
                                        Rational expected)
    {
        Rational longest = null;
        final Map<AbstractChordInter, Rational> starts = new LinkedHashMap<>();
        final List<Voice> voices = new ArrayList<>();

        for (Voice voice : measure.getVoices()) {
            if (voice.isMeasureRest()) {
                continue;
            }

            final List<AbstractChordInter> chords = voice.getChords();

            if (chords.isEmpty() || (chords.get(0).getTimeOffset() == null)) {
                return false;
            }

            voices.add(voice);
        }

        while (!voices.isEmpty()) {
            // Next voice: one starting the measure, or on a slot already timed
            Voice voice = voices.get(0);
            Rational start = voice.getChords().get(0).getTimeOffset();

            for (Voice v : voices) {
                final AbstractChordInter first = v.getChords().get(0);
                final Rational known = startInSlot(first, starts);

                if ((known != null) || first.getTimeOffset().equals(Rational.ZERO)) {
                    voice = v;
                    start = (known != null) ? known : Rational.ZERO;
                    break;
                }
            }

            voices.remove(voice);

            final List<AbstractChordInter> chords = voice.getChords();
            Rational end = start;

            for (AbstractChordInter chord : chords) {
                final Rational dur = tuplets.contains(chord.getTuplet()) ? chord
                        .getDurationSansTuplet() : chord.getDuration();

                if (dur == null) {
                    return false;
                }

                starts.put(chord, end);
                end = end.plus(dur);
            }

            if (end.compareTo(expected) > 0) {
                return false;
            }

            if ((longest == null) || (end.compareTo(longest) > 0)) {
                longest = end;
            }
        }

        for (Slot slot : measure.getStack().getSlots()) {
            Rational time = null;

            for (AbstractChordInter chord : slot.getChords()) {
                final Rational start = starts.get(chord);

                if (start != null) {
                    if ((time != null) && !time.equals(start)) {
                        return false;
                    }

                    time = start;
                }
            }
        }

        return expected.equals(longest);
    }

    //-------------//
    // startInSlot //
    //-------------//
    /**
     * The start already found for another chord of the slot of a chord, if any.
     */
    private static Rational startInSlot (AbstractChordInter chord,
                                         Map<AbstractChordInter, Rational> starts)
    {
        final Slot slot = chord.getSlot();

        if (slot != null) {
            for (AbstractChordInter other : slot.getChords()) {
                final Rational start = starts.get(other);

                if (start != null) {
                    return start;
                }
            }
        }

        return null;
    }

    //------------//
    // isCompound //
    //------------//
    /**
     * Whether a time is compound: its beat is three of its units, as in 6/8 or 12/8.
     */
    private static boolean isCompound (TimeRational time)
    {
        return (time.num % 3 == 0) && (time.num > 3) && (time.den >= 8);
    }

    //---------//
    // isShort //
    //---------//
    /**
     * Whether every timed voice of a measure ends before its expected duration.
     */
    private static boolean isShort (Measure measure,
                                    Rational expected)
    {
        boolean timed = false;

        for (Voice voice : measure.getVoices()) {
            if (voice.isMeasureRest()) {
                return false;
            }

            final AbstractChordInter last = voice.getLastChord();
            final Rational end = (last != null) ? last.getEndTime() : null;

            if (end == null) {
                return false;
            }

            if (end.compareTo(expected) >= 0) {
                return false;
            }

            timed = true;
        }

        return timed;
    }

    //-------------------------//
    // readStackActualDuration //
    //-------------------------//
    /**
     * Retrieve stack actual duration.
     */
    private void readStackActualDuration ()
    {
        try {
            // Determine stack actual duration
            final Rational actualDuration;

            if (stack.getVoices().isEmpty()) {
                actualDuration = Rational.ZERO; // No voice, hence no duration
            } else {
                Rational slotsDur = stack.getSlotsDuration();

                if (!slotsDur.equals(Rational.ZERO)) {
                    actualDuration = slotsDur; // Slots found, use slots-based duration
                } else {
                    actualDuration = stack.getExpectedDuration(); // No slot, just whole/multi rests
                }
            }

            stack.setActualDuration(actualDuration);
        } catch (Exception ex) {
            logger.warn("StackRhythm. Error visiting " + stack + " " + ex, ex);
        }
    }
}
