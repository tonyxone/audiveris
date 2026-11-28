//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                 H e a d S t e m s C l e a n e r                                //
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
package org.audiveris.omr.sheet.stem;

import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.relation.Exclusion;
import org.audiveris.omr.sig.relation.HeadStemRelation;
import org.audiveris.omr.sig.relation.Relation;
import static org.audiveris.omr.util.HorizontalSide.LEFT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Class <code>HeadStemsCleaner</code> checks and cleans up an ensemble of stems around a head.
 *
 * @author Hervé Bitteur
 */
public class HeadStemsCleaner
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(HeadStemsCleaner.class);

    //~ Instance fields ----------------------------------------------------------------------------

    private final HeadInter head;

    private final SIGraph sig;

    private final List<HeadStemRelation> rels = new ArrayList<>();

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a <code>HeadStemsCleaner</code> object.
     *
     * @param head the underlying head
     */
    public HeadStemsCleaner (HeadInter head)
    {
        this.head = head;

        sig = head.getSig();
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-------//
    // check //
    //-------//
    /**
     * Check the collection of stems around this head.
     *
     * @param stems the stems to check and clean
     */
    public void check (List<Inter> stems)
    {
        // Retrieve stem relations
        rels.clear();

        for (Inter stem : stems) {
            HeadStemRelation rel = (HeadStemRelation) sig.getRelation(
                    head,
                    stem,
                    HeadStemRelation.class);

            if (rel != null) {
                rels.add(rel);
            }
        }

        if (head.isVip() && (rels.size() > 1)) {
            logger.info("VIP {} with multiple stems {}", head, stems);
        }

        // Get down to a maximum of 2 stems
        while (rels.size() > 2) {
            // Discard the relation with smallest contribution
            HeadStemRelation discarded = discardWorstContribution(rels);

            if (discarded != null) {
                sig.removeEdge(discarded);
                rels.remove(discarded);
            }
        }

        // Do we still have a conflict to solve?
        if (rels.size() == 2) {
            // If not canonical, try to discard one of the stem links
            if (!isCanonicalShare()) {
                // Discard the link on the abnormal side, if only one is, else the relation with
                // smallest contribution
                HeadStemRelation discarded = discardAbnormalSide();

                if (discarded == null) {
                    discarded = discardWorstContribution(rels);
                }

                if (discarded != null) {
                    sig.removeEdge(discarded);
                    rels.remove(discarded);
                }
            }
        }
    }

    //---------------------//
    // discardAbnormalSide //
    //---------------------//
    /**
     * Discard the only link with the head on the abnormal side of its stem, unless another
     * head one step away lies on the normal side, as in a chord second.
     * <p>
     * Otherwise the stronger link may be the wrong one, which the reducer cuts later on,
     * leaving the head with no stem at all: the middle note of the grace arpeggio that starts
     * the 25th bar of Mozart's Rondo alla Turca reached the stem of its left neighbor.
     *
     * @return the link to discard, or null
     */
    private HeadStemRelation discardAbnormalSide ()
    {
        HeadStemRelation abnormal = null;

        for (HeadStemRelation rel : rels) {
            if (!isNormalSide(rel)) {
                if (abnormal != null) {
                    return null; // Both abnormal
                }

                abnormal = rel;
            }
        }

        if (abnormal == null) {
            return null;
        }

        final StemInter stem = (StemInter) sig.getEdgeTarget(abnormal);
        final int pitch = head.getIntegerPitch();

        for (Relation rel : sig.getRelations(stem, HeadStemRelation.class)) {
            final HeadStemRelation hsRel = (HeadStemRelation) rel;
            final HeadInter other = (HeadInter) sig.getEdgeSource(rel);

            if ((other != head) && (hsRel.getHeadSide() != abnormal.getHeadSide()) && (other
                    .getStaff() == head.getStaff()) && (Math.abs(other.getIntegerPitch()
                            - pitch) <= 1)) {
                return null; // A second
            }
        }

        if (head.isVip()) {
            logger.info("VIP {} discarding abnormal side {}", head, stem);
        }

        return abnormal;
    }

    //--------------//
    // isNormalSide //
    //--------------//
    /**
     * Tell whether the head lies on the normal side of the stem: left of a stem going up,
     * right of a stem going down, as the reducer will check it.
     * <p>
     * The relation side is the side of the head where the stem lies. The stem direction comes
     * from what is found on its ends, not from its location around the head: the top head of
     * an up chord lies above the stem center.
     * A stem of unknown direction is taken as normal.
     */
    private boolean isNormalSide (HeadStemRelation rel)
    {
        final StemInter stem = (StemInter) sig.getEdgeTarget(rel);
        final int dir = stem.computeDirection();

        if (dir == 0) {
            return true;
        }

        return (rel.getHeadSide() == LEFT) == (dir > 0);
    }

    //-----------------//
    // discardLargeGap //
    //-----------------//
    /**
     * Discard the stem link with largest significant y gap, if any.
     *
     * @param relations the set of head-stem relations to purge
     * @return the link to stem with largest significant y gap,or null
     */
    private HeadStemRelation discardLargeGap (List<HeadStemRelation> relations)
    {
        double worstGap = 0;
        HeadStemRelation worstRel = null;

        for (HeadStemRelation rel : relations) {
            double yGap = rel.getDy();

            if (worstGap < yGap) {
                worstGap = yGap;
                worstRel = rel;
            }
        }

        if (worstGap > constants.yGapTiny.getValue()) {
            if (head.isVip()) {
                logger.info("VIP {} discarding gap {}", head, sig.getEdgeTarget(worstRel));
            }

            return worstRel;
        } else {
            return null;
        }
    }

    //-------------------//
    // discardWeakerStem //
    //-------------------//
    /**
     * Discard the link to the stem with lower intrinsic grade.
     *
     * @return the link to discard
     */
    private HeadStemRelation discardWeakerStem ()
    {
        double worstGrade = Double.MAX_VALUE;
        HeadStemRelation worstRel = null;
        StemInter worstStem = null;

        for (HeadStemRelation rel : rels) {
            StemInter stem = (StemInter) sig.getEdgeTarget(rel);
            double grade = stem.getGrade();

            if (grade < worstGrade) {
                worstGrade = grade;
                worstStem = stem;
                worstRel = rel;
            }
        }

        if (worstRel != null) {
            if (head.isVip()) {
                logger.info("VIP {} discarding weaker {}", head, sig.getEdgeTarget(worstRel));
            }

            // If this false relation is really invading, use exclusion
            if (worstRel.isInvading()) {
                if (worstStem.isVip() || head.isVip()) {
                    logger.info("VIP invasion between {} & {}", head, worstStem);
                }

                sig.insertExclusion(head, worstStem, Exclusion.ExclusionCause.OVERLAP);
            }
        }

        return worstRel;
    }

    //--------------------------//
    // discardWorstContribution //
    //--------------------------//
    private HeadStemRelation discardWorstContribution (List<HeadStemRelation> relations)
    {
        double worstContrib = Double.MAX_VALUE;
        HeadStemRelation worstRel = null;

        for (HeadStemRelation rel : relations) {
            final StemInter stem = (StemInter) sig.getEdgeTarget(rel);
            final double ratio = rel.getTargetRatio();
            final double contrib = stem.getGrade() * (ratio - 1);

            if (worstContrib > contrib) {
                worstContrib = contrib;
                worstRel = rel;
            }
        }

        return worstRel;
    }

    //------------------//
    // isCanonicalShare //
    //------------------//
    /**
     * Check whether this is the canonical "shared" configuration.
     * <p>
     * For this test, we cannot trust stem extensions and must stay with physical stem
     * limits.
     *
     * @return true if canonical
     */
    private boolean isCanonicalShare ()
    {
        StemInter leftStem = null;
        StemInter rightStem = null;

        for (HeadStemRelation rel : rels) {
            if (rel.getDy() > constants.yGapTiny.getValue()) {
                return false;
            }

            if (rel.getHeadSide() == LEFT) {
                leftStem = (StemInter) sig.getOppositeInter(head, rel);
            } else {
                rightStem = (StemInter) sig.getOppositeInter(head, rel);
            }
        }

        if ((leftStem == null) || (rightStem == null)) {
            return false;
        }

        return HeadStemRelation.isCanonicalShare(leftStem, head, rightStem);
    }

    //------------//
    // preferBeam //
    //------------//
    /**
     * Give preference to a stem linked to a beam, if any.
     *
     * @return stem link to be discarded if such was found
     */
    private HeadStemRelation preferBeam ()
    {
        final List<HeadStemRelation> toBeam = new ArrayList<>();
        final List<HeadStemRelation> notToBeam = new ArrayList<>();

        for (HeadStemRelation rel : rels) {
            StemInter stem = (StemInter) sig.getEdgeTarget(rel);

            if (stem.getBeams().isEmpty()) {
                notToBeam.add(rel);
            } else {
                toBeam.add(rel);
            }
        }

        if (toBeam.isEmpty() || notToBeam.isEmpty()) {
            return null;
        }

        // Remove one relation out of 'notToBeam' collection
        HeadStemRelation discarded;

        if (notToBeam.size() > 1) {
            discarded = discardLargeGap(notToBeam);

            if (discarded == null) {
                discarded = discardWeakerStem();
            }
        } else {
            discarded = notToBeam.get(0);
        }

        return discarded;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Scale.Fraction yGapTiny = new Scale.Fraction(
                0.2, // 0.1,
                "Maximum vertical tiny gap between stem & head");
    }
}
