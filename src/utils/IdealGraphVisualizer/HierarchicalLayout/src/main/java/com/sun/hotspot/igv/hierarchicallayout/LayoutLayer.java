/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
package com.sun.hotspot.igv.hierarchicallayout;

import static com.sun.hotspot.igv.hierarchicallayout.LayoutManager.*;
import static com.sun.hotspot.igv.hierarchicallayout.LayoutNode.NODE_X_COMPARATOR;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Represents a layer in a hierarchical graph layout.
 * Each LayoutLayer contains a collection of LayoutNodes positioned at the same vertical level.
 * Provides methods to manage the nodes within the layer, including positioning, sorting,
 * and adjusting the layout to minimize overlaps and improve visual clarity.
 */
public class LayoutLayer extends ArrayList<LayoutNode> {

    private int height = 0;
    private int y = 0;

    /**
     * Adds all LayoutNodes from the specified collection to this layer.
     * Updates the layer's height based on the nodes added.
     *
     * @param c The collection of LayoutNodes to be added.
     * @return true if this layer changed as a result of the call.
     */
    @Override
    public boolean addAll(Collection<? extends LayoutNode> c) {
        c.forEach(this::updateLayerHeight);
        return super.addAll(c);
    }

    /**
     * Adds a single LayoutNode to this layer.
     * Updates the layer's height based on the node added.
     *
     * @param n The LayoutNode to be added.
     * @return true if the node was added successfully.
     */
    @Override
    public boolean add(LayoutNode n) {
        updateLayerHeight(n);
        return super.add(n);
    }

    /**
     * Updates the layer's height if the outer height of the given node exceeds the current height.
     *
     * @param n The LayoutNode whose height is to be considered.
     */
    private void updateLayerHeight(LayoutNode n) {
        height = Math.max(height, n.getOuterHeight());
    }

    /**
     * Calculates and returns the maximum height among the nodes in this layer, including their margins.
     * Adjusts the top and bottom margins of non-dummy nodes to be equal, effectively centering them vertically.
     *
     * @return The maximum outer height of nodes in this layer.
     */
    public int calculateMaxLayerHeight() {
        int maxLayerHeight = 0;
        for (LayoutNode layoutNode : this) {
            if (!layoutNode.isDummy()) {
                // Center the node by setting equal top and bottom margins
                int offset = Math.max(layoutNode.getTopMargin(), layoutNode.getBottomMargin());
                layoutNode.setTopMargin(offset);
                layoutNode.setBottomMargin(offset);
            }
            maxLayerHeight = Math.max(maxLayerHeight, layoutNode.getOuterHeight());
        }
        return maxLayerHeight;
    }

    /**
     * Calculates and returns the total height of this layer, including additional padding
     * based on the maximum horizontal offset among the edges of its nodes.
     * This padding helps in scaling the layer vertically to accommodate edge bends and crossings.
     *
     * @return The total padded height of the layer.
     */
    public int calculatePaddedHeight() {
        int maxXOffset = 0;

        for (LayoutNode layoutNode : this) {
            for (LayoutEdge succEdge : layoutNode.getSuccessors()) {
                maxXOffset = Math.max(Math.abs(succEdge.getStartX() - succEdge.getEndX()), maxXOffset);
            }
        }

        int scalePaddedBottom = this.getHeight();
        scalePaddedBottom += (int) (SCALE_LAYER_PADDING * Math.max((int) (Math.sqrt(maxXOffset) * 2), LAYER_OFFSET * 3));
        return scalePaddedBottom;
    }

    /**
     * Centers all nodes in this layer vertically within the layer's assigned space.
     * Adjusts each node's Y-coordinate so that it is centered based on the layer's top and height.
     */
    public void centerNodesVertically() {
        for (LayoutNode layoutNode : this) {
            int centeredY = getTop() + (getHeight() - layoutNode.getOuterHeight()) / 2;
            layoutNode.setY(centeredY);
        }
    }

    /**
     * Sets the top Y-coordinate of this layer.
     *
     * @param top The Y-coordinate representing the top of the layer.
     */
    public void setTop(int top) {
        y = top;
    }

    /**
     * Shifts the top Y-coordinate of this layer by the specified amount.
     * Useful for moving the entire layer up or down.
     *
     * @param shift The amount to shift the layer's top position. Positive values move it down.
     */
    public void moveLayerVertically(int shift) {
        y += shift;
    }

    /**
     * Gets the top Y-coordinate of this layer.
     *
     * @return The Y-coordinate representing the top of the layer.
     */
    public int getTop() {
        return y;
    }

    /**
     * Gets the bottom Y-coordinate of this layer.
     *
     * @return The Y-coordinate representing the bottom of the layer.
     */
    public int getBottom() {
        return y + height;
    }

    /**
     * Gets the height of this layer.
     *
     * @return The height of the layer.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Sets the height of this layer.
     *
     * @param height The height to set for the layer.
     */
    public void setHeight(int height) {
        this.height = height;
    }

    /**
     * Checks if this layer contains only dummy nodes.
     *
     * @return true if all nodes in the layer are dummy nodes; false otherwise.
     */
    public boolean containsOnlyDummyNodes() {
        for (LayoutNode node : this) {
            if (!node.isDummy()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sorts the nodes in this layer by their X-coordinate in increasing order.
     * Assigns position indices to nodes based on the sorted order.
     * Adjusts the X-coordinates of nodes to ensure minimum spacing between them.
     */
    public void sortNodesByX() {
        if (isEmpty()) return;

        sort(NODE_X_COMPARATOR); // Sort nodes in the layer increasingly by x

        updateNodeIndices();
        updateMinXSpacing();
    }

    /**
     * Adjusts the X-coordinates of the nodes to ensure a minimum spacing between them.
     * The spacing is determined by each node's outer width and a predefined node offset.
     * This method ensures that nodes do not overlap and are positioned correctly along the X-axis.
     */
    public void updateMinXSpacing() {
        if (isEmpty()) {
            return; // If the list is empty, there's no need to adjust spacing.
        }

        // Starting X position for the first node.
        int minX = this.get(0).getX();

        // Iterate over each node in the layer.
        for (LayoutNode node : this) {
            // Calculate the new X position, ensuring it's at least minX.
            int x = Math.max(node.getX(), minX);
            node.setX(x); // Set the adjusted X position for the node.

            // Update minX for the next node.
            // The new minX is the current node's X position plus its width and the node offset.
            minX = x + node.getOuterWidth() + NODE_OFFSET;
        }
    }

    /**
     * Updates the position indices of the nodes in this layer based on their order in the list.
     * Useful after nodes have been added or removed to ensure position indices are consistent.
     */
    public void updateNodeIndices() {
        int pos = 0;
        for (LayoutNode layoutNode : this) {
            layoutNode.setPos(pos);
            pos++;
        }
    }

    /**
     * Updates position indices and adjusts X-coordinates to ensure minimum spacing.
     */
    public void updateIndicesAndSpacing() {
        updateNodeIndices();
        updateMinXSpacing();
    }

    /**
     * Attempts to move the specified node to the right within the layer to the given X-coordinate.
     * Ensures that the node does not overlap with its right neighbor by checking required spacing.
     * If movement is possible without causing overlap, the node's X-coordinate is updated.
     *
     * @param layoutNode The node to move.
     * @param newX       The desired new X-coordinate for the node.
     */
    public void tryShiftNodeRight(LayoutNode layoutNode, int newX) {
        int currentX = layoutNode.getX();
        int shiftAmount = newX - currentX;
        int rightPos = layoutNode.getPos() + 1;

        if (rightPos < size()) {
            // There is a right neighbor
            LayoutNode rightNeighbor = get(rightPos);
            int proposedRightEdge = layoutNode.getRight() + shiftAmount;
            int requiredLeftEdge = rightNeighbor.getOuterLeft() - NODE_OFFSET;

            if (proposedRightEdge <= requiredLeftEdge) {
                layoutNode.setX(newX);
            }
        } else {
            // No right neighbor; safe to move freely to the right
            layoutNode.setX(newX);
        }
    }

    /**
     * Attempts to move the specified node to the left within the layer to the given X-coordinate.
     * Ensures that the node does not overlap with its left neighbor by checking required spacing.
     * If movement is possible without causing overlap, the node's X-coordinate is updated.
     *
     * @param layoutNode The node to move.
     * @param newX       The desired new X-coordinate for the node.
     */
    public void tryShiftNodeLeft(LayoutNode layoutNode, int newX) {
        int currentX = layoutNode.getX();
        int shiftAmount = currentX - newX;
        int leftPos = layoutNode.getPos() - 1;

        if (leftPos >= 0) {
            // There is a left neighbor
            LayoutNode leftNeighbor = get(leftPos);
            int proposedLeftEdge = layoutNode.getLeft() - shiftAmount;
            int requiredRightEdge = leftNeighbor.getOuterRight() + NODE_OFFSET;

            if (requiredRightEdge <= proposedLeftEdge) {
                layoutNode.setX(newX);
            }
        } else {
            // No left neighbor; safe to move freely to the left
            layoutNode.setX(newX);
        }
    }
}
