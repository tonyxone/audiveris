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
import org.audiveris.omr.sig.inter.RestChordInter;
import org.audiveris.omr.sig.inter.TupletInter;
import org.audiveris.omr.sig.inter.WordInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
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

    /** Chords closer than this, in interlines, are at the same abscissa. */
    private static final double SAME_ABSCISSA = 0.25;

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

    /** Measures to read mapping each chord to the voice ending first: off their time otherwise. */
    private final Set<Measure> strict = new LinkedHashSet<>();

    /** The strict measures whose forced tuplets go with them. */
    private final Set<Measure> strictForced = new LinkedHashSet<>();

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
                    forced.getOrDefault(measure, Collections.emptyList()),
                    strict.contains(measure)).process();

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

        for (Measure measure : stack.getMeasures()) {
            TwoNoteTremolos.detect(measure, false, new ArrayList<>());
        }

        try {
            boolean ok = doProcess();

            if (dropBeatTuplets()) {
                ok = doProcess();
            }

            if (dropUnfitTuplets()) {
                ok = doProcess();
            }

            final Map<Measure, Rational> endBefore = new LinkedHashMap<>();
            final Map<Measure, Integer> untimedBefore = new LinkedHashMap<>();

            for (Measure measure : stack.getMeasures()) {
                endBefore.put(measure, longestEnd(measure));
                untimedBefore.put(measure, untimedHeadChords(measure));
            }

            if (addMissedTuplets()) {
                ok = doProcess();

                if (dropUnfitForced(endBefore, untimedBefore)) {
                    ok = doProcess();
                }
            }

            ok = retryOffTime(ok);
            ok = retryOutOfOrder(ok);

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
                // Voices may share out the notes of a beat triplet and the measure still fit
                if (!compound) {
                    final List<List<AbstractChordInter>> triplets = beatTripletsOfFour(
                            measure,
                            new Rational(1, current.den));

                    if (!triplets.isEmpty()) {
                        forced.put(measure, triplets);
                        added = true;
                    }
                }

                continue;
            }

            final List<List<AbstractChordInter>> groups = new ArrayList<>();

            for (List<AbstractChordInter> group : plainGroups(measure)) {
                final Rational total = group.get(0).getDurationSansTuplet().times(group.size());

                if (!total.equals(beat) && !completesBeat(group)) {
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

    //--------------//
    // retryOffTime //
    //--------------//
    /**
     * Read again the measures still off their time: with strict voices, then with two-note
     * tremolos, then with both. Each measure is kept that way only if it then fits.
     *
     * @param ok the current status
     * @return the new status
     */
    private boolean retryOffTime (boolean ok)
    {
        final Rational expected = stack.getExpectedDuration();

        if ((expected == null) || stack.isImplicit()) {
            return ok;
        }

        boolean result = ok;

        if (retryStrict(stack.getMeasures())) {
            result = doProcess();

            if (dropUnfitStrict()) {
                result = doProcess();
            }
        }

        // The rests read on the strokes are out of the rhythm during the trial, for good if it works
        final Map<Measure, List<RestChordInter>> tremolos = new LinkedHashMap<>();

        for (Measure measure : stack.getMeasures()) {
            final List<RestChordInter> strokes = new ArrayList<>();

            if (!fits(measure, expected) && TwoNoteTremolos.detect(measure, true, strokes)) {
                measure.hideRestChords(strokes);
                tremolos.put(measure, strokes);
            }
        }

        if (tremolos.isEmpty()) {
            return result;
        }

        result = doProcess();

        if (retryStrict(tremolos.keySet())) {
            result = doProcess();

            if (dropUnfitStrict()) {
                result = doProcess();
            }
        }

        boolean dropped = false;

        for (Map.Entry<Measure, List<RestChordInter>> entry : tremolos.entrySet()) {
            final Measure measure = entry.getKey();
            final List<RestChordInter> strokes = entry.getValue();
            final boolean good = fits(measure, expected) && isSequential(measure);
            measure.showRestChords(strokes);

            if (good) {
                logger.info("{} off its time, read with two-note tremolos", measure);

                for (RestChordInter rest : strokes) {
                    rest.remove();
                }
            } else {
                TwoNoteTremolos.detect(measure, false, new ArrayList<>());
                dropped = true;
            }
        }

        return dropped ? doProcess() : result;
    }

    //-----------------//
    // retryOutOfOrder //
    //-----------------//
    /**
     * Read again the measures whose chords do not go on in time as they go on across the page.
     * <p>
     * Such a measure may well add up: a chord continuing the wrong voice, or implicit tuplets
     * where none is printed, leaves its total right but plays notes at the wrong time.
     * It is tried with strict voices, without implicit tuplets, then with both, and kept that
     * way only if it then fits its time, in order. Chords at one abscissa with different times
     * are not enough to doubt the voices, a missed dot does that too: strict voices are not
     * tried, and in a measure that fits already most of these chords must keep their time.
     *
     * @param ok the current status
     * @return the new status
     */
    private boolean retryOutOfOrder (boolean ok)
    {
        final Rational expected = stack.getExpectedDuration();

        if ((expected == null) || stack.isImplicit()) {
            return ok;
        }

        final List<Measure> remaining = new ArrayList<>();
        final Map<Measure, Map<AbstractChordInter, Rational>> slight = new LinkedHashMap<>();

        for (Measure measure : stack.getMeasures()) {
            final int disorder = disorder(measure);

            if ((disorder > 0) && !strict.contains(measure) && !noImplicit.contains(measure)) {
                remaining.add(measure);

                if (disorder == 1) {
                    slight.put(
                            measure,
                            fits(measure, expected) ? startTimes(measure) : Collections.emptyMap());
                }
            }
        }

        if (remaining.isEmpty()) {
            return ok;
        }

        boolean result = ok;
        final boolean[][] trials = {{true, false}, {false, true}, {true, true}};

        for (boolean[] trial : trials) {
            for (Measure measure : remaining) {
                if (!trial[0] || !slight.containsKey(measure)) {
                    setRetry(strict, measure, trial[0]);
                    setRetry(noImplicit, measure, trial[1]);
                }
            }

            result = doProcess();

            for (Iterator<Measure> it = remaining.iterator(); it.hasNext();) {
                final Measure measure = it.next();

                if ((!trial[0] || !slight.containsKey(measure)) && fits(measure, expected)
                        && isSequential(measure) && (disorder(measure) == 0)
                        && (!slight.containsKey(measure) || keepsMost(measure, slight.get(measure)))) {
                    logger.info("{} out of order, read again", measure);
                    it.remove();
                }
            }

            if (remaining.isEmpty()) {
                return result;
            }
        }

        for (Measure measure : remaining) {
            strict.remove(measure);
            noImplicit.remove(measure);
        }

        return doProcess();
    }

    private static Map<AbstractChordInter, Rational> startTimes (Measure measure)
    {
        final Map<AbstractChordInter, Rational> times = new LinkedHashMap<>();

        for (AbstractChordInter chord : voicedChords(measure)) {
            times.put(chord, chord.getTimeOffset());
        }

        return times;
    }

    //-----------//
    // keepsMost //
    //-----------//
    /**
     * Tell whether, wherever chords at one abscissa had different times, most of them have kept
     * theirs.
     *
     * @param measure the measure read again
     * @param before  the time of each chord before
     * @return false if more of them have moved than kept their time
     */
    private static boolean keepsMost (Measure measure,
                                      Map<AbstractChordInter, Rational> before)
    {
        final int sameX = sameAbscissa(measure);
        final List<AbstractChordInter> chords = voicedChords(measure);

        for (int i = 0; i < chords.size(); i++) {
            final int x = chords.get(i).getCenter().x;
            final Set<Rational> times = new LinkedHashSet<>();
            int kept = 0;
            int moved = 0;

            for (AbstractChordInter chord : chords.subList(i, chords.size())) {
                if ((chord.getCenter().x - x) > sameX) {
                    break;
                }

                final Rational time = before.get(chord);

                if (time != null) {
                    times.add(time);

                    if (time.equals(chord.getTimeOffset())) {
                        kept++;
                    } else {
                        moved++;
                    }
                }
            }

            if ((times.size() > 1) && (moved > kept)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Report the chords of a measure, measure rests apart, from left to right.
     */
    private static List<AbstractChordInter> voicedChords (Measure measure)
    {
        final List<AbstractChordInter> chords = new ArrayList<>();

        for (Voice voice : measure.getVoices()) {
            if (!voice.isMeasureRest()) {
                chords.addAll(voice.getChords());
            }
        }

        chords.sort(Comparator.comparingInt(chord -> chord.getCenter().x));

        return chords;
    }

    private static int sameAbscissa (Measure measure)
    {
        return (int) Math.rint(
                SAME_ABSCISSA * measure.getStack().getSystem().getSheet().getInterline());
    }

    private static void setRetry (Set<Measure> set,
                                  Measure measure,
                                  boolean on)
    {
        if (on) {
            set.add(measure);
        } else {
            set.remove(measure);
        }
    }

    //----------//
    // disorder //
    //----------//
    /**
     * Tell how the chords of a measure go against the page.
     *
     * @return 0 if they are in order, 1 if chords at one abscissa differ in time, 2 if a chord
     *         further right than another starts before it
     */
    private static int disorder (Measure measure)
    {
        final int sameX = sameAbscissa(measure);
        Rational latest = null;
        int latestX = 0;
        int disorder = 0;

        for (AbstractChordInter chord : voicedChords(measure)) {
            final Rational start = chord.getTimeOffset();

            if (start != null) {
                final int x = chord.getCenter().x;
                final int order = (latest != null) ? start.compareTo(latest) : 0;

                if ((order != 0) && ((x - latestX) <= sameX)) {
                    disorder = 1;
                } else if (order < 0) {
                    return 2;
                }

                if (order >= 0) {
                    latest = start;
                    latestX = x;
                }
            }
        }

        return disorder;
    }

    //-------------//
    // retryStrict //
    //-------------//
    /**
     * Read again, mapping each chord to the voice ending first, the measures still off
     * their time.
     * <p>
     * Voice mapping favors a voice of the same staff, even one that ends later. Across an
     * arpeggio going from one hand to the other, or a voice with gaps and no rest printed, that
     * puts chords too late. In a measure that comes out long, the beam groups of 3 or 6 are also
     * read as tuplets, whose sign may be printed on the first group only.
     * Each measure is kept that way only if it then fits.
     *
     * @return true if some measure is to be read again
     */
    private boolean retryStrict (Collection<Measure> measures)
    {
        final Rational expected = stack.getExpectedDuration();

        if ((expected == null) || stack.isImplicit()) {
            return false;
        }

        final TimeRational current = currentTime();
        final Rational beat = ((current != null) && isCompound(current)) ? new Rational(
                3,
                current.den) : null;

        boolean added = false;

        for (Measure measure : measures) {
            if (!fits(measure, expected) && !measure.getStandardChords().isEmpty()) {
                strict.add(measure);
                added = true;

                if (isLong(measure, expected) && !forced.containsKey(measure)) {
                    final List<List<AbstractChordInter>> groups = new ArrayList<>();

                    for (List<AbstractChordInter> group : plainGroups(measure)) {
                        final Rational total = group.get(0).getDurationSansTuplet().times(group
                                .size());

                        if (!total.equals(beat)) {
                            groups.add(group);
                        }
                    }

                    if (!groups.isEmpty()) {
                        forced.put(measure, groups);
                        strictForced.add(measure);
                    }
                }
            }
        }

        return added;
    }

    //-----------------//
    // dropUnfitStrict //
    //-----------------//
    /**
     * Give up the strict reading of a measure that still does not fit its time with it.
     *
     * @return true if some measure gave it up, and the stack must be read again
     */
    private boolean dropUnfitStrict ()
    {
        final Rational expected = stack.getExpectedDuration();
        boolean dropped = false;

        for (Measure measure : new ArrayList<>(strict)) {
            if (fits(measure, expected) && isSequential(measure)) {
                logger.info("{} off its time, read with strict voices", measure);
            } else {
                strict.remove(measure);

                if (strictForced.remove(measure)) {
                    forced.remove(measure);
                }

                dropped = true;
            }
        }

        return dropped;
    }

    //--------------//
    // isSequential //
    //--------------//
    /**
     * Whether, in every voice of a measure, each chord starts once the previous one has ended.
     * Otherwise two chords can share a slot of the voice, and one of them gets lost.
     */
    private static boolean isSequential (Measure measure)
    {
        for (Voice voice : measure.getVoices()) {
            Rational end = null;

            for (AbstractChordInter chord : voice.getChords()) {
                final Rational start = chord.getTimeOffset();

                if ((start == null) || ((end != null) && (start.compareTo(end) < 0))) {
                    return false;
                }

                end = chord.getEndTime();
            }
        }

        return true;
    }

    //-----------------//
    // dropUnfitForced //
    //-----------------//
    /**
     * Give up the tuplets added to a measure that still does not fit its time with them, unless
     * they are all of 6 chords, a less doubtful reading than 3, and make it better: it ends no
     * later, no more head chords are left without a time, and either fewer are or its excess is
     * at least halved.
     * A chord without a time is not exported at all, and a misread chord of the other hand, say,
     * should not cost the tuplets of this one.
     *
     * @param endBefore     the end of each measure before
     * @param untimedBefore the count of untimed head chords of each measure before
     * @return true if some measure lost them, and the stack must be read again
     */
    private boolean dropUnfitForced (Map<Measure, Rational> endBefore,
                                     Map<Measure, Integer> untimedBefore)
    {
        final Rational expected = stack.getExpectedDuration();
        boolean dropped = false;

        for (Measure measure : new ArrayList<>(forced.keySet())) {
            if (fits(measure, expected)) {
                logger.info("{} off its time without tuplets, read with {}", measure, forced.get(measure)
                        .size());
            } else if (forced.get(measure).stream().allMatch(group -> group.size() == 6) && isBetter(
                    measure,
                    expected,
                    endBefore.get(measure),
                    untimedBefore.get(measure))) {
                logger.info("{} still off its time, better read with {}", measure, forced.get(
                        measure).size());
            } else {
                forced.remove(measure);
                dropped = true;
            }
        }

        return dropped;
    }

    private static boolean isBetter (Measure measure,
                                     Rational expected,
                                     Rational endBefore,
                                     int untimedBefore)
    {
        final Rational end = longestEnd(measure);
        final int untimed = untimedHeadChords(measure);

        if ((end == null) || (endBefore == null) || (end.compareTo(endBefore) > 0)
                || (untimed > untimedBefore)) {
            return false;
        }

        return (untimed < untimedBefore) || (end.minus(expected).times(2).compareTo(
                endBefore.minus(expected)) <= 0);
    }

    private static int untimedHeadChords (Measure measure)
    {
        int count = 0;

        for (AbstractChordInter chord : measure.getStandardChords()) {
            if (!chord.isRest() && ((chord.getVoice() == null) || (chord.getTimeOffset() == null))) {
                count++;
            }
        }

        return count;
    }

    private static Rational longestEnd (Measure measure)
    {
        Rational longest = null;

        for (Voice voice : measure.getVoices()) {
            final AbstractChordInter last = voice.isMeasureRest() ? null : voice.getLastChord();
            final Rational end = (last != null) ? last.getEndTime() : null;

            if ((end != null) && ((longest == null) || (end.compareTo(longest) > 0))) {
                longest = end;
            }
        }

        return longest;
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

    //---------------//
    // completesBeat //
    //---------------//
    /**
     * Whether a group starts off the grid of twice its chord value, as after a rest of one
     * value, and ends on the grid of four: it then completes a plain beat and is no tuplet,
     * which would start on the grid.
     * In Chopin's Fantaisie-Impromptu, three sixteenths after a sixteenth rest make a beat
     * against the sextuplets of the other hand.
     */
    private static boolean completesBeat (List<AbstractChordInter> group)
    {
        final Rational start = group.get(0).getTimeOffset();
        final Rational dur = group.get(0).getDurationSansTuplet();

        if ((start == null) || (dur == null)) {
            return false;
        }

        final Rational end = start.plus(dur.times(group.size()));

        return (start.divides(dur.times(2)).den != 1) && (end.divides(dur.times(4)).den == 1);
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
     * <p>
     * In a beam group of 4, three chords of equal duration and a fourth of twice it, first or
     * last, the three could make a triplet that, with the fourth, fills a beat: three sixteenths
     * and an eighth, as in Debussy's second Arabesque.
     */
    private static List<List<AbstractChordInter>> plainGroups (Measure measure)
    {
        final Set<AbstractChordInter> chords = measure.getStandardChords();
        final List<List<AbstractChordInter>> groups = new ArrayList<>();

        for (BeamGroupInter bg : measure.getBeamGroups()) {
            List<AbstractChordInter> group = new ArrayList<>(bg.getChords());

            if (group.size() == 4) {
                Collections.sort(group, Inters.byCenterAbscissa);
                group = tripletOfFour(group);

                if (group == null) {
                    continue;
                }
            }

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

    //--------------------//
    // beatTripletsOfFour //
    //--------------------//
    /**
     * The beam groups of 4 chords of a measure, three of equal duration and a fourth of twice
     * it, that start on a beat and fill it only if the three make a triplet: three sixteenths
     * and an eighth in a quarter beat.
     * <p>
     * Read plain, they last a beat and a quarter, and the next notes of the line may go to
     * another voice, so that the measure fits with notes played at the wrong time.
     *
     * @param measure the measure to inspect
     * @param beat    the beat duration of the simple time
     * @return the three chords of each such group
     */
    private static List<List<AbstractChordInter>> beatTripletsOfFour (Measure measure,
                                                                     Rational beat)
    {
        final Set<AbstractChordInter> chords = measure.getStandardChords();
        final List<List<AbstractChordInter>> triplets = new ArrayList<>();

        for (BeamGroupInter bg : measure.getBeamGroups()) {
            final List<AbstractChordInter> four = new ArrayList<>(bg.getChords());

            if ((four.size() != 4) || !chords.containsAll(four)
                    || four.stream().anyMatch(c -> c.getTuplet() != null)) {
                continue;
            }

            Collections.sort(four, Inters.byCenterAbscissa);
            final List<AbstractChordInter> three = tripletOfFour(four);
            final Rational start = four.get(0).getTimeOffset();

            if ((three == null) || (start == null)) {
                continue;
            }

            final Rational d = three.get(0).getDurationSansTuplet();

            // The three as a triplet, with the fourth, fill the beat they start on
            if (d.times(4).equals(beat) && (start.divides(beat).den == 1)) {
                triplets.add(three);
            }
        }

        return triplets;
    }

    //---------------//
    // tripletOfFour //
    //---------------//
    /**
     * In 4 chords ordered by abscissa, the 3 of equal duration next to a fourth of twice it.
     *
     * @return the 3 chords, or null
     */
    private static List<AbstractChordInter> tripletOfFour (List<AbstractChordInter> four)
    {
        final Rational[] d = new Rational[4];

        for (int i = 0; i < 4; i++) {
            d[i] = four.get(i).getDurationSansTuplet();

            if (d[i] == null) {
                return null;
            }
        }

        if (d[0].equals(d[1]) && d[1].equals(d[2]) && d[3].equals(d[0].times(2))) {
            return new ArrayList<>(four.subList(0, 3));
        }

        if (d[1].equals(d[2]) && d[2].equals(d[3]) && d[0].equals(d[1].times(2))) {
            return new ArrayList<>(four.subList(1, 4));
        }

        return null;
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
