//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        R e s t I n t e r                                       //
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
package org.audiveris.omr.sig.inter;

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.math.GeoUtil;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sig.relation.Containment;
import org.audiveris.omr.sig.relation.Link;
import org.audiveris.omr.sig.ui.AdditionTask;
import org.audiveris.omr.sig.ui.UITask;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.omr.util.WrappedBoolean;
import org.audiveris.omr.util.Wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class <code>RestInter</code> represents a rest note.
 *
 * @author Hervé Bitteur
 */
@XmlRootElement(name = "rest")
public class RestInter
        extends AbstractNoteInter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(RestInter.class);

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * No-argument constructor meant for JAXB (and dummy measure).
     */
    protected RestInter ()
    {
    }

    /**
     * Creates a new RestInter object.
     *
     * @param glyph underlying glyph
     * @param shape rest shape
     * @param grade evaluation value
     * @param staff the related staff
     * @param pitch the rest pitch
     */
    public RestInter (Glyph glyph,
                      Shape shape,
                      Double grade,
                      Staff staff,
                      Double pitch)
    {
        super(glyph, null, shape, grade, staff, pitch);
    }

    //~ Methods ------------------------------------------------------------------------------------

    //--------//
    // accept //
    //--------//
    @Override
    public void accept (InterVisitor visitor)
    {
        visitor.visit(this);
    }

    //----------//
    // getChord //
    //----------//
    /**
     * Report the containing rest chord.
     *
     * @return containing rest chord
     */
    @Override
    public RestChordInter getChord ()
    {
        return (RestChordInter) getEnsemble();
    }

    //--------//
    // preAdd //
    //--------//
    @Override
    public List<? extends UITask> preAdd (WrappedBoolean cancel,
                                          Wrapper<Inter> toPublish)
    {
        // Standard addition task for this rest
        final List<UITask> tasks = new ArrayList<>(super.preAdd(cancel, toPublish));

        // Wrap this rest within a rest chord
        final RestChordInter restChord = new RestChordInter(null);
        restChord.setManual(true);
        restChord.setStaff(staff);

        tasks.add(
                new AdditionTask(
                        staff.getSystem().getSig(),
                        restChord,
                        getBounds(),
                        Arrays.asList(new Link(this, new Containment(), true))));

        return tasks;
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //-------------//
    // createValid //
    //-------------//
    /**
     * Try to create a Rest inter.
     * <p>
     * A rest cannot be too close abscissa-wise to a head-chord.
     *
     * @param glyph            underlying glyph
     * @param shape            precise shape
     * @param grade            evaluation value
     * @param system           the related system
     * @param systemHeadChords abscissa-ordered list of head-chords in this system
     * @return the created instance or null if failed
     */
    public static RestInter createValid (Glyph glyph,
                                         Shape shape,
                                         double grade,
                                         SystemInfo system,
                                         List<Inter> systemHeadChords)
    {
        final Point centroid = glyph.getCentroid();

        // Pick up the closest staff, or between two staves the one of the heads nearby
        final Staff restStaff = gapStaff(glyph, system, systemHeadChords);

        if ((restStaff == null) || restStaff.isTablature()) {
            return null;
        }

        final Measure measure = restStaff.getPart().getMeasureAt(centroid);

        if (measure == null) {
            return null;
        }

        // Check horizontal position WRT head-chords
        final int minDx = system.getSheet().getScale().toPixels(constants.minInterChordDx);
        final Rectangle glyphBox = glyph.getBounds();
        final Point2D restCenter = GeoUtil.center2D(glyphBox);
        final Rectangle fatBox = new Rectangle(glyphBox);
        fatBox.grow(minDx, 0);

        // All head-chords in staff measure
        final int left = measure.getAbscissa(HorizontalSide.LEFT, restStaff);
        final int right = measure.getAbscissa(HorizontalSide.RIGHT, restStaff);
        final List<Inter> measureChords = Inters.inters(systemHeadChords, (Inter inter) -> {
            if (inter.getStaff() != restStaff) {
                return false;
            }

            final Point center = inter.getCenter();

            return (center.x > left) && (center.x <= right); // Away from left, include right
        });

        for (Inter chord : measureChords) {
            if (fatBox.intersects(chord.getBounds())) {
                double dx = chord.getCenter2D().getX() - restCenter.getX();

                if (Math.abs(dx) < minDx) {
                    if (glyph.isVip() || logger.isDebugEnabled()) {
                        logger.info("Discarded stuck rest candidate glyph#{}", glyph.getId());
                    }

                    return null; // Failure
                }
            }
        }

        // Pitch value WRT staff
        final double restPitch = restStaff.pitchPositionOf(centroid);

        RestInter restInter = new RestInter(glyph, shape, grade, restStaff, restPitch);
        restStaff.addNote(restInter);

        return restInter;
    }

    //----------//
    // gapStaff //
    //----------//
    /**
     * Report the staff of a rest, the closest one, unless the rest lies between two staves
     * about as far from each, as a tenor rest drawn high above the bass staff.
     * <p>
     * The rest then goes with the staff whose heads around are closer to it, ordinate-wise.
     *
     * @param glyph            the rest glyph
     * @param system           the related system
     * @param systemHeadChords the head-chords in this system
     * @return the rest staff, or null
     */
    private static Staff gapStaff (Glyph glyph,
                                   SystemInfo system,
                                   List<Inter> systemHeadChords)
    {
        final Point centroid = glyph.getCentroid();
        final Staff closest = system.getClosestStaff(centroid);
        final List<Staff> around = system.getStavesAround(centroid);

        if ((closest == null) || (around.size() != 2)) {
            return closest;
        }

        final Scale scale = system.getSheet().getScale();
        final Staff other = (around.get(0) == closest) ? around.get(1) : around.get(0);

        if ((other.doubleDistanceTo(centroid) - closest.doubleDistanceTo(centroid)) > scale
                .toPixels(constants.gapAmbiguity)) {
            return closest;
        }

        final Rectangle box = glyph.getBounds();
        final double toClosest = headGap(box, closest, systemHeadChords, scale);
        final double toOther = headGap(box, other, systemHeadChords, scale);

        return (toOther < toClosest) ? other : closest;
    }

    //---------//
    // headGap //
    //---------//
    /**
     * Report the ordinate gap between a rest box and the closest heads of a staff around it.
     *
     * @param box              the rest bounds
     * @param staff            the staff at hand
     * @param systemHeadChords the head-chords in system
     * @param scale            the sheet scale
     * @return the smallest gap, or infinity when no head of this staff is around
     */
    private static double headGap (Rectangle box,
                                   Staff staff,
                                   List<Inter> systemHeadChords,
                                   Scale scale)
    {
        final int dx = scale.toPixels(constants.gapHeadDx);
        final int maxDy = scale.toPixels(constants.gapHeadDy);
        double best = Double.POSITIVE_INFINITY;

        for (Inter inter : systemHeadChords) {
            if ((inter.getStaff() != staff) || !(inter instanceof HeadChordInter)) {
                continue;
            }

            final Rectangle heads = ((HeadChordInter) inter).getHeadsBounds();

            if ((heads.x > (box.x + box.width + dx)) || ((heads.x + heads.width) < (box.x
                    - dx))) {
                continue;
            }

            final double gap = Math.max(0, Math.max(heads.y - (box.y + box.height), box.y
                    - (heads.y + heads.height)));

            if (gap <= maxDy) {
                best = Math.min(best, gap);
            }
        }

        return best;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Double suspiciousPitchPosition = new Constant.Double(
                "PitchPosition",
                2.0,
                "Maximum absolute pitch position for a rest to avoid additional checks");

        private final Scale.Fraction minInterChordDx = new Scale.Fraction(
                0.5,
                "Minimum horizontal delta between two chords");

        private final Scale.Fraction gapAmbiguity = new Scale.Fraction(
                2.0,
                "Maximum difference in distance to two staves for a rest between them");

        private final Scale.Fraction gapHeadDx = new Scale.Fraction(
                3.0,
                "Maximum horizontal distance to heads that tell the staff of a rest");

        private final Scale.Fraction gapHeadDy = new Scale.Fraction(
                2.0,
                "Maximum vertical gap to heads that tell the staff of a rest");
    }
}
