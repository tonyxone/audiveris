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

import org.audiveris.omr.math.Rational;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.TupletInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
            final boolean measureOk = new MeasureRhythm(measure, tuning, !noImplicit.contains(measure))
                    .process();

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
     * Determine stack rhythm data (voices and timing).
     *
     * @param expectedDuration The expected duration for this stack, null if not known
     */
    public void process (Rational expectedDuration)
    {
        stack.setExpectedDuration(expectedDuration);

        try {
            boolean ok = doProcess();

            if (dropUnfitTuplets()) {
                ok = doProcess();
            }

            if (!ok) {
                logger.info("{}{} no correct rhythm", stack.getSystem().getLogPrefix(), stack);
            }
        } catch (Exception ex) {
            logger.warn("StackRhythm error processing {} {}", stack, ex.toString(), ex);
        }
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
     */
    private static boolean fitsWithout (Measure measure,
                                        Collection<TupletInter> tuplets,
                                        Rational expected)
    {
        Rational longest = null;
        final Map<AbstractChordInter, Rational> starts = new LinkedHashMap<>();

        for (Voice voice : measure.getVoices()) {
            if (voice.isMeasureRest()) {
                continue;
            }

            final List<AbstractChordInter> chords = voice.getChords();

            if (chords.isEmpty() || (chords.get(0).getTimeOffset() == null)) {
                return false;
            }

            Rational end = chords.get(0).getTimeOffset();

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
