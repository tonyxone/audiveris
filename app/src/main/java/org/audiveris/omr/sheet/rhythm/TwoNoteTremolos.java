//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      T w o N o t e T r e m o l o s                               //
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

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sig.inter.AbstractBeamInter;
import org.audiveris.omr.sig.inter.AlterInter;
import org.audiveris.omr.sig.inter.HeadChordInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.RestChordInter;
import org.audiveris.omr.sig.inter.StemInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.process.ByteProcessor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class <code>TwoNoteTremolos</code> finds the two-note tremolos of a measure: two chords
 * of one staff, written with the whole duration of the tremolo, and alternated over it.
 * <p>
 * The strokes drawn between them, like short beams touching no stem, are not recognized as
 * such. Each chord of a pair is read with half its written duration, so that the pair fills
 * the time it is written for, the second chord starting halfway, where it is printed.
 */
public abstract class TwoNoteTremolos
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(TwoNoteTremolos.class);

    //~ Constructors -------------------------------------------------------------------------------

    private TwoNoteTremolos ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //--------//
    // detect //
    //--------//
    /**
     * Flag the chords of a measure that alternate in a two-note tremolo.
     * <p>
     * Strokes between stems can be mistaken for something else, so stemmed chords are looked at
     * only on request, for a measure off its time. Strokes between stemless heads are not.
     *
     * @param measure the measure to process
     * @param stemmed true to look at chords with stems as well
     * @param strokes (output) the whole or half rests read on the strokes of the flagged chords
     * @return true if some chord is flagged
     */
    public static boolean detect (Measure measure,
                                  boolean stemmed,
                                  List<RestChordInter> strokes)
    {
        boolean found = false;
        final Sheet sheet = measure.getStack().getSystem().getSheet();
        final ByteProcessor buf = sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        final Map<Staff, List<HeadChordInter>> byStaff = new LinkedHashMap<>();

        for (HeadChordInter chord : measure.getHeadChords()) {
            chord.setTremoloHalf(false);

            if (!chord.isRemoved() && (chord.getStaff() != null)) {
                byStaff.computeIfAbsent(chord.getStaff(), s -> new ArrayList<>()).add(chord);
            }
        }

        if (buf == null) {
            return false;
        }

        final int interline = sheet.getScale().getInterline();
        final List<Inter> beams = measure.getStack().getSystem().getSig().inters(
                AbstractBeamInter.class);

        for (List<HeadChordInter> chords : byStaff.values()) {
            chords.sort(Inters.byCenterAbscissa);

            for (int i = 0; i + 1 < chords.size(); i++) {
                final HeadChordInter c1 = chords.get(i);
                final HeadChordInter c2 = chords.get(i + 1);

                if ((stemmed || ((c1.getStem() == null) && (c2.getStem() == null)))
                        && isCandidate(c1) && isCandidate(c2) && c1.getDurationSansTuplet().equals(
                        c2.getDurationSansTuplet()) && hasStrokes(buf, c1, c2, interline, beams)) {
                    logger.debug("{} two-note tremolo {} {}", measure, c1, c2);
                    c1.setTremoloHalf(true);
                    c2.setTremoloHalf(true);
                    found = true;
                    strokes.addAll(strokeRests(measure, c1, c2));
                    i++;
                }
            }
        }

        return found;
    }

    //-------------//
    // strokeRests //
    //-------------//
    /**
     * Report the whole or half rests read between the two chords of a tremolo: they are
     * its strokes, which look just like them.
     */
    private static List<RestChordInter> strokeRests (Measure measure,
                                                     HeadChordInter c1,
                                                     HeadChordInter c2)
    {
        final List<RestChordInter> rests = new ArrayList<>();
        final int x1 = c1.getCenter().x;
        final int x2 = c2.getCenter().x;

        for (RestChordInter chord : measure.getRestChords()) {
            final Point center = chord.getCenter();

            if ((center.x > x1) && (center.x < x2) && (chord.getStaff() == c1.getStaff())) {
                final List<? extends Inter> notes = chord.getNotes();

                if (!notes.isEmpty() && ((notes.get(0).getShape() == Shape.WHOLE_REST) || (notes
                        .get(0).getShape() == Shape.HALF_REST))) {
                    rests.add(chord);
                }
            }
        }

        return rests;
    }

    //-------------//
    // isCandidate //
    //-------------//
    /**
     * A chord of a two-note tremolo has no beam nor flag, the strokes stand in their stead.
     */
    private static boolean isCandidate (HeadChordInter chord)
    {
        return !chord.isTremoloHalf() && (chord.getBeamsOrFlagsNumber() == 0)
                && (chord.getDurationSansTuplet() != null) && !chord.getNotes().isEmpty()
                && !chord.getNotes().get(0).getShape().isSmallHead();
    }

    //------------//
    // hasStrokes //
    //------------//
    /**
     * Tell whether 2 to 4 strokes, of beam thickness and close on top of one another, run
     * over some length between the stems of two chords (or their heads for stemless ones),
     * where no beam was recognized.
     */
    private static boolean hasStrokes (ByteProcessor buf,
                                       HeadChordInter c1,
                                       HeadChordInter c2,
                                       int interline,
                                       List<Inter> beams)
    {
        final StemInter s1 = c1.getStem();
        final StemInter s2 = c2.getStem();
        final Rectangle b1 = c1.getBoundsWithDots();
        final Rectangle b2 = c2.getBounds();
        int left = (s1 != null) ? s1.getBounds().x + s1.getBounds().width : b1.x + b1.width;
        int right = (s2 != null) ? s2.getBounds().x : b2.x;

        for (Inter note : c2.getNotes()) {
            final AlterInter alter = ((HeadInter) note).getAccidental();

            if ((alter != null) && (s2 == null)) {
                right = Math.min(right, alter.getBounds().x);
            }
        }

        final int gap = right - left;

        if ((gap < interline) || (gap > constants.maxGap.getValue() * interline)) {
            return false;
        }

        final int y0 = Math.max(0, Math.min(b1.y, b2.y) - interline);
        final int y1 = Math.min(buf.getHeight() - 1, Math.max(b1.y + b1.height, b2.y
                + b2.height) + interline);
        final Rectangle area = new Rectangle(left, y0, gap, y1 - y0 + 1);

        for (Inter beam : beams) {
            if (beam.getBounds().intersects(area)) {
                return false;
            }
        }

        final List<Rectangle> heads = new ArrayList<>();

        for (Inter note : c1.getNotes()) {
            heads.add(note.getBounds());
        }

        for (Inter note : c2.getNotes()) {
            heads.add(note.getBounds());
        }

        final double minRun = constants.minStroke.getValue() * interline;
        final double maxRun = constants.maxStroke.getValue() * interline;
        final double maxSpacing = constants.maxSpacing.getValue() * interline;
        int span = 0;
        int best = 0;

        for (int x = left + 1; x < right; x++) {
            int strokes = 0;
            int run = 0;
            int lastEnd = -1;
            boolean close = true;

            for (int y = y0; y <= y1 + 1; y++) {
                final boolean ink = (y <= y1) && (buf.get(x, y) == 0) && !inHeads(heads, x, y);

                if (ink) {
                    run++;
                } else {
                    if ((run >= minRun) && (run <= maxRun)) {
                        if ((lastEnd >= 0) && ((y - run - lastEnd) > maxSpacing)) {
                            close = false;
                        }

                        strokes++;
                        lastEnd = y;
                    }

                    run = 0;
                }
            }

            span = (close && (strokes >= 2) && (strokes <= 4)) ? span + 1 : 0;
            best = Math.max(best, span);
        }

        return best >= constants.minSpan.getValue() * interline;
    }

    //---------//
    // inHeads //
    //---------//
    private static boolean inHeads (List<Rectangle> heads,
                                    int x,
                                    int y)
    {
        for (Rectangle head : heads) {
            if (head.contains(x, y)) {
                return true;
            }
        }

        return false;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Ratio maxGap = new Constant.Ratio(
                11.0,
                "Maximum gap between the chords of a tremolo, in interlines");

        private final Constant.Ratio minStroke = new Constant.Ratio(
                0.3,
                "Minimum thickness of a tremolo stroke, in interlines");

        private final Constant.Ratio maxStroke = new Constant.Ratio(
                0.7,
                "Maximum thickness of a tremolo stroke, in interlines");

        private final Constant.Ratio maxSpacing = new Constant.Ratio(
                0.5,
                "Maximum white between two tremolo strokes, in interlines");

        private final Constant.Ratio minSpan = new Constant.Ratio(
                1.0,
                "Minimum length of the strokes on top of one another, in interlines");
    }
}
